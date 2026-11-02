"""WING-019 review queue over HTTP and the service (real PostgreSQL): ranking, dealbreaker
exclusion, stale flagging, lazy versioned re-evaluation, isolation, contract shapes."""

import dataclasses
import uuid
from typing import Any

import httpx
import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.integrations import get_match_evaluator
from app.db.enums import ConnectionTransport, IntentMode, ProfileProvider
from app.db.errors import NotFoundError
from app.db.models import ProfileAction
from app.db.pagination import CursorPosition, encode_cursor
from app.db.repositories import (
    ConnectionRepository,
    ProfileEvaluationRepository,
    ProfileRepository,
)
from app.services.preferences import PreferencesService, PreferencesUpdate
from app.services.profiles import ProfileMatchingService
from tests.automation.conftest import (
    World,
    assert_contract,
    capabilities_doc,
    headers,
    make_world,
    problem,
)
from tests.db.conftest import make_user
from tests.integrations.helpers import EXPERIMENTAL_INSTALL
from tests.matching.conftest import import_body, scripted_evaluator

QUEUE = "/v1/profiles/review-queue"


async def set_prefs(
    session: AsyncSession,
    world: World,
    mode: str = "serious",
    dealbreakers: list[str] | None = None,
) -> int:
    update = PreferencesUpdate.model_validate(
        {
            "intent": {"mode": mode},
            "dealbreakers": dealbreakers or [],
            "style": {},
            "automation": {"mode": "manual"},
        }
    )
    saved = await PreferencesService(session, world.scope).put_user_preferences(update)
    return saved.version


async def swipe_world(session: AsyncSession) -> World:
    world = await make_world(session, mode="manual")
    doc = capabilities_doc("hinge")
    doc["capabilities"]["swipe"] = {
        "status": "VERIFIED",
        "evidence": "operator run #1",
        "verified_at": "2026-11-01T00:00:00Z",
    }
    conn = await ConnectionRepository(session, world.scope).create(
        platform="hinge",
        display_name="hinge",
        capabilities=doc,
        device_install_id=EXPERIMENTAL_INSTALL,
        paired_at=world.clock.now,
        transport=ConnectionTransport.NOTIFICATION,
    )
    return dataclasses.replace(world, connection_id=conn.id, platform="hinge")


async def post_profile(client: httpx.AsyncClient, world: World, **kw: Any) -> dict[str, Any]:
    resp = await client.post("/v1/profiles", json=import_body(**kw), headers=headers(world.scope))
    assert resp.status_code in (200, 201), resp.text
    body: dict[str, Any] = resp.json()
    return body


async def queue(client: httpx.AsyncClient, world: World, **params: Any) -> dict[str, Any]:
    resp = await client.get(QUEUE, params=params, headers=headers(world.scope))
    assert resp.status_code == 200, resp.text
    body: dict[str, Any] = resp.json()
    assert_contract("ProfilePage", body)
    return body


async def seed(client: httpx.AsyncClient, world: World) -> dict[str, str]:
    """Five profiles whose ranking under 'serious' + 'smoking' is known."""
    ids: dict[str, str] = {}
    ids["fit"] = (
        await post_profile(
            client, world, attributes={"looking_for": "Long-term relationship", "smoking": "No"}
        )
    )["id"]
    ids["unknown"] = (await post_profile(client, world, attributes={}))["id"]
    ids["conflict"] = (
        await post_profile(client, world, attributes={"looking_for": "Hookups", "smoking": "No"})
    )["id"]
    ids["smoker"] = (
        await post_profile(
            client, world, attributes={"looking_for": "Long-term", "smoking": "Regularly"}
        )
    )["id"]
    ids["stale"] = (
        await post_profile(
            client,
            world,
            attributes={"looking_for": "Long-term relationship", "smoking": "No"},
            source_observed_at="2026-10-01T00:00:00Z",
        )
    )["id"]
    return ids


async def test_ranking_order_exclusion_and_stale_flag(
    session: AsyncSession,
    make_v13_client,  # type: ignore[no-untyped-def]
) -> None:
    world = await swipe_world(session)
    await set_prefs(session, world, "serious", ["smoking"])
    client = make_v13_client(world)
    ids = await seed(client, world)
    page = await queue(client, world)
    order = [p["id"] for p in page["items"]]
    # Fresh by score (fit > unknown > conflict), then stale; the smoker is excluded.
    assert order == [ids["fit"], ids["unknown"], ids["conflict"], ids["stale"]]
    by_id = {p["id"]: p for p in page["items"]}
    assert by_id[ids["stale"]]["stale"] is True
    assert all(not by_id[i]["stale"] for i in (ids["fit"], ids["unknown"], ids["conflict"]))
    for item in page["items"]:
        ev = item["latest_evaluation"]
        assert ev is not None and ev["intent_mode"] == "serious" and ev["dealbreakers_hit"] == []
        assert_contract("Evaluation", ev)
    assert "relationship intent" in by_id[ids["unknown"]]["latest_evaluation"]["known_unknowns"]
    # The excluded profile is listed on request (service level), last, with its reason.
    matching = ProfileMatchingService(now=world.clock)
    full = await matching.review_queue(session, world.scope, include_excluded=True)
    assert [str(v.profile.id) for v in full.items][-1] == ids["smoker"]
    hit = full.items[-1].latest_evaluation
    assert hit.dealbreakers_hit == ["smoking"] and hit.score == 0
    assert any(
        r["factor"] == "dealbreaker" and r["evidence"] == ["attributes/smoking"]
        for r in hit.reasons
    )
    # Decided profiles leave the queue; skip keeps them.
    await client.post(
        f"/v1/profiles/{ids['fit']}/decision",
        json={"decision": "skip"},
        headers=headers(world.scope),
    )
    await client.post(
        f"/v1/profiles/{ids['conflict']}/decision",
        json={"decision": "pass"},
        headers=headers(world.scope),
    )
    after = [p["id"] for p in (await queue(client, world))["items"]]
    assert after == [ids["fit"], ids["unknown"], ids["stale"]]


async def test_stale_profile_is_flagged_and_never_executed(
    session: AsyncSession,
    make_v13_client,  # type: ignore[no-untyped-def]
) -> None:
    world = await swipe_world(session)
    client = make_v13_client(world)
    await client.put(
        f"/v1/connections/{world.connection_id}/settings",
        json={"auto_swipe_enabled": True},
        headers=headers(world.scope),
    )
    fresh = await post_profile(client, world)
    stale = await post_profile(client, world, source_observed_at="2026-10-01T00:00:00Z")
    items = (await queue(client, world))["items"]
    assert [i["id"] for i in items] == [fresh["id"], stale["id"]]
    assert items[1]["stale"] is True and items[1]["latest_evaluation"] is not None
    action = await client.post(
        f"/v1/profiles/{stale['id']}/decision",
        json={"decision": "like", "connection_id": str(world.connection_id)},
        headers=headers(world.scope),
    )
    assert action.json()["execution"] == "not_executable"
    body = problem(
        await client.post(
            f"/v1/profile-actions/{action.json()['id']}/execute",
            json={},
            headers=headers(world.scope),
        ),
        422,
        "swipe_execution_disabled",
    )
    assert "stale" in body["errors"][0]["message"]
    # Invalidated by the source: stale too.
    fresh_row = await ProfileRepository(session, world.scope).require(_uuid(fresh["id"]))
    fresh_row.invalidated_at = world.clock.now
    await session.flush()
    again = (await queue(client, world))["items"]
    assert [i["id"] for i in again] == [fresh["id"]] and again[0]["stale"] is True


async def test_preference_change_adds_a_new_version_and_keeps_history(
    session: AsyncSession,
    make_v13_client,  # type: ignore[no-untyped-def]
) -> None:
    world = await swipe_world(session)
    v1 = await set_prefs(session, world, "serious")
    client = make_v13_client(world)
    prof = await post_profile(client, world, attributes={"looking_for": "Something casual"})
    first = (await queue(client, world))["items"][0]["latest_evaluation"]
    assert first["intent_mode"] == "serious"
    # Reading again with unchanged inputs does not re-evaluate.
    assert (await queue(client, world))["items"][0]["latest_evaluation"]["id"] == first["id"]
    decision = await client.post(
        f"/v1/profiles/{prof['id']}/decision",
        json={"decision": "skip"},
        headers=headers(world.scope),
    )
    v2 = await set_prefs(session, world, "casual_dating")
    assert v2 == v1 + 1
    second = (await queue(client, world))["items"][0]["latest_evaluation"]
    assert second["id"] != first["id"] and second["intent_mode"] == "casual_dating"
    assert second["score"] > first["score"]
    history = await ProfileEvaluationRepository(session, world.scope).history(_uuid(prof["id"]))
    assert [(h.preferences_version, h.intent_mode) for h in history] == [
        (v1, IntentMode.SERIOUS),
        (v2, IntentMode.CASUAL_DATING),
    ]
    old = history[0]
    assert str(old.id) == first["id"] and old.score == first["score"]  # unchanged
    assert old.reasons == first["reasons"]
    actions = (
        await session.scalars(
            select(ProfileAction).where(ProfileAction.user_id == world.scope.user_id)
        )
    ).all()
    assert [(str(a.id), a.decision.value) for a in actions] == [(decision.json()["id"], "skip")]
    # A re-import with new content is evaluated again, too.
    await post_profile(
        client,
        world,
        external_profile_id=prof["external_profile_id"],
        attributes={"looking_for": "Long-term relationship"},
    )
    third = (await queue(client, world))["items"][0]["latest_evaluation"]
    assert third["id"] not in (first["id"], second["id"])
    assert len(await ProfileEvaluationRepository(session, world.scope).history(old.profile_id)) == 3


async def test_foreign_evaluations_are_not_superseded(
    session: AsyncSession,
    make_v13_client,  # type: ignore[no-untyped-def]
) -> None:
    world = await swipe_world(session)
    client = make_v13_client(world)
    prof = await post_profile(client, world)
    await ProfileEvaluationRepository(session, world.scope).create(
        profile_id=_uuid(prof["id"]),
        score=12,
        reasons=[],
        known_unknowns=[],
        intent_mode=IntentMode.OPEN,
        dealbreakers_hit=[],
        confidence=0.4,
        evaluator_version="manual-review",
    )
    ev = (await queue(client, world))["items"][0]["latest_evaluation"]
    assert ev["evaluator_version"] == "manual-review" and ev["score"] == 12


async def test_pagination_and_provider_filter(
    session: AsyncSession,
    make_v13_client,  # type: ignore[no-untyped-def]
) -> None:
    world = await swipe_world(session)
    await set_prefs(session, world, "serious")
    client = make_v13_client(world)
    looking = ["Long-term relationship", "Figuring out", None, "Hookups", "Life partner"]
    for i, lf in enumerate(looking):
        attrs = {"looking_for": lf} if lf else {}
        await post_profile(client, world, attributes=attrs, external_profile_id=f"p-{i}")
    tinder = await post_profile(client, world, provider="tinder", external_profile_id="t-1")
    whole = [p["id"] for p in (await queue(client, world))["items"]]
    seen: list[str] = []
    cursor = None
    while True:
        params: dict[str, Any] = {"limit": 2}
        if cursor:
            params["cursor"] = cursor
        page = await queue(client, world, **params)
        seen += [p["id"] for p in page["items"]]
        cursor = page["next_cursor"]
        if cursor is None:
            break
    assert seen == whole and len(whole) == 6
    scores = [p["latest_evaluation"]["score"] for p in (await queue(client, world))["items"]]
    assert scores == sorted(scores, reverse=True)
    only_tinder = await queue(client, world, provider="tinder")
    assert [p["id"] for p in only_tinder["items"]] == [tinder["id"]]
    bad = await client.get(
        QUEUE, params={"cursor": page_cursor_of_other_listing()}, headers=headers(world.scope)
    )
    assert bad.status_code in (400, 422)


def page_cursor_of_other_listing() -> str:
    return encode_cursor("profiles:queue", CursorPosition(1, uuid.UUID(int=1)))


async def test_cross_user_isolation(
    session: AsyncSession,
    make_v13_client,  # type: ignore[no-untyped-def]
) -> None:
    world = await swipe_world(session)
    await set_prefs(session, world, "serious", ["smoking"])
    client = make_v13_client(world)
    mine = await post_profile(client, world, attributes={"smoking": "No"})
    await queue(client, world)
    stranger = await make_user(session)
    other = dataclasses.replace(world, scope=stranger)
    # The stranger has other preferences: none of ours leak into their evaluation.
    theirs = await client.post(
        "/v1/profiles",
        json=import_body(
            external_profile_id=mine["external_profile_id"], attributes={"smoking": "Yes"}
        ),
        headers=headers(stranger),
    )
    assert theirs.status_code == 201 and theirs.json()["id"] != mine["id"]
    their_queue = await queue(client, other)
    assert [p["id"] for p in their_queue["items"]] == [theirs.json()["id"]]
    assert their_queue["items"][0]["latest_evaluation"]["dealbreakers_hit"] == []
    assert their_queue["items"][0]["latest_evaluation"]["intent_mode"] == "open"
    assert [p["id"] for p in (await queue(client, world))["items"]] == [mine["id"]]
    with pytest.raises(NotFoundError):
        await ProfileMatchingService(now=world.clock).evaluate_profile(
            session, stranger, _uuid(mine["id"])
        )
    assert await ProfileEvaluationRepository(session, stranger).latest(_uuid(mine["id"])) is None


async def test_model_pass_through_the_route_and_fallback(
    session: AsyncSession,
    make_v13_client,  # type: ignore[no-untyped-def]
) -> None:
    world = await swipe_world(session)
    await set_prefs(session, world, "serious")
    client = make_v13_client(world)
    evaluator, provider = scripted_evaluator(
        {
            "reasons": [
                {
                    "factor": "shared_interests",
                    "effect": "positive",
                    "detail": "Enjoys jazz.",
                    "evidence": ["bio"],
                    "quote": "loves jazz",
                },
                {
                    "factor": "vibe",
                    "effect": "positive",
                    "detail": "Seems to be about 30 from the photos.",
                    "evidence": ["photos/0"],
                    "quote": "jazz",
                },
            ],
            "adjustment": 8,
        },
        "malformed",
    )
    app = client._transport.app  # type: ignore[attr-defined]
    app.dependency_overrides[get_match_evaluator] = lambda: evaluator
    good = await post_profile(client, world, external_profile_id="a")
    first = (await queue(client, world))["items"][0]["latest_evaluation"]
    assert first["evaluator_version"].endswith("/rules+model")
    assert [r["evidence"] for r in first["reasons"] if r["factor"] == "shared_interests"] == [
        ["bio"]
    ]
    assert not any("photos" in e for r in first["reasons"] for e in r["evidence"])
    await post_profile(client, world, external_profile_id="b")  # model now fails
    items = {p["id"]: p for p in (await queue(client, world))["items"]}
    failed = next(v for k, v in items.items() if k != good["id"])["latest_evaluation"]
    assert "model-failed" in failed["evaluator_version"]
    assert provider.calls == 2


async def test_fixture_feed_imports_and_ranks_deterministically(session: AsyncSession) -> None:
    results = []
    for _ in range(2):
        world = await swipe_world(session)
        await set_prefs(session, world, "serious", ["smoking"])
        matching = ProfileMatchingService(now=world.clock)
        views = await matching.import_fixture_feed(session, world.scope)
        assert all(v.profile.provider is ProfileProvider.MOCK for v in views)
        page = await matching.review_queue(session, world.scope)
        results.append(
            [(v.profile.external_profile_id, v.latest_evaluation.score) for v in page.items]
        )
    assert results[0] == results[1] and len(results[0]) >= 4
    assert "fixture-002" not in [e for e, _ in results[0]]  # "smoking: Socially" excluded


def _uuid(value: str) -> uuid.UUID:
    return uuid.UUID(value)


async def test_queue_never_adds_inferred_attributes(
    session: AsyncSession,
    make_v13_client,  # type: ignore[no-untyped-def]
) -> None:
    world = await swipe_world(session)
    await set_prefs(session, world, "serious", ["under 25", "smoking"])
    client = make_v13_client(world)
    attrs = {"photos": ["https://cdn.invalid/1.jpg"], "photo_caption": "me at 24"}
    prof = await post_profile(
        client,
        world,
        bio="I'm 24, a smoker, Catholic, looking for something serious.",
        prompts=[{"prompt": "About me", "answer": "Just turned 24"}],
        attributes=attrs,
    )
    item = (await queue(client, world))["items"][0]
    assert item["id"] == prof["id"] and item["attributes"] == attrs
    ev = item["latest_evaluation"]
    assert ev["dealbreakers_hit"] == []
    assert {"age", "smoking", "relationship intent"} <= set(ev["known_unknowns"])
    assert all(e.startswith("attributes/") for r in ev["reasons"] for e in r["evidence"])
