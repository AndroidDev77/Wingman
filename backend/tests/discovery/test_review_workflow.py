"""WING-031 review workflow over HTTP (real PostgreSQL): the ranked queue from mock
profiles, decisions with stable idempotency keys, the audited execute gate (unsupported
swipe, offline device, canceled auto-swipe, pause, stale and superseded profiles),
invalidation, rate limits, privacy and cross-user isolation.

Nothing here executes a swipe: ``queued`` is the furthest any action gets, and only on a
connection whose VERIFIED swipe is a test fixture (no real platform has one)."""

import uuid
from datetime import timedelta
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import ConnectionStatus, IntentMode, ProfileActionExecution
from app.db.errors import NotFoundError
from app.db.models import AgentAction, ProfileAction
from app.db.repositories import ConnectionRepository, ProfileEvaluationRepository
from app.discovery import DiscoveryLimits, DiscoveryService
from app.services.profiles import ProfileMatchingService
from tests.automation.conftest import assert_contract, headers, problem
from tests.db.conftest import make_user
from tests.discovery.conftest import (
    MakeClient,
    dating_world,
    decide,
    denials,
    enable_auto_swipe,
    execute,
    new_id,
    post_profile,
    set_prefs,
)

QUEUE = "/v1/profiles/review-queue"


async def actions_of(session: AsyncSession, world: Any) -> list[ProfileAction]:
    rows = await session.scalars(
        select(ProfileAction)
        .where(ProfileAction.user_id == world.scope.user_id)
        .order_by(ProfileAction.created_at, ProfileAction.id)
    )
    return list(rows)


# --------------------------------------------------------------------------- queue


async def test_review_queue_from_mock_profiles_is_ranked_and_explained(
    session: AsyncSession, make_client: MakeClient
) -> None:
    world = await dating_world(session)
    await set_prefs(session, world, "serious", ["smoking"])
    service = ProfileMatchingService(now=world.clock)
    await service.import_fixture_feed(session, world.scope)
    client = make_client(world)
    page = (await client.get(QUEUE, headers=headers(world.scope))).json()
    assert_contract("ProfilePage", page)
    items = page["items"]
    assert items, "the fixture feed fills the queue"
    assert all(p["provider"] == "mock" and p["provenance"]["source"] == "fixture" for p in items)
    # Ranked: scored first (high to low), unscored last; every item explained.
    scores = [p["latest_evaluation"]["score"] for p in items]
    scored = [s for s in scores if s is not None]
    assert scored == sorted(scored, reverse=True)
    assert scores.index(None) >= len(scored) if None in scores else True
    for p in items:
        ev = p["latest_evaluation"]
        assert ev["intent_mode"] == "serious"
        assert 0 <= ev["confidence"] <= 1
        assert isinstance(ev["known_unknowns"], list)
        for reason in ev["reasons"]:
            # Reasons cite only fields the profile actually has.
            for pointer in reason.get("evidence", []):
                head, _, rest = pointer.partition("/")
                assert head in {"bio", "prompts", "attributes", "display_name"}
                if head == "attributes":
                    assert rest in p["attributes"], (pointer, p["attributes"])
        assert p["latest_action"] is None
    # The profile with no stated attributes lists them as unknowns, never inferred.
    empty = next(p for p in items if p["attributes"] == {})
    assert empty["latest_evaluation"]["known_unknowns"]
    # The smoker-free dealbreaker: nothing in the feed says "smoking: Yes", so nothing is
    # excluded; include_excluded=true lists the same set.
    everything = (
        await client.get(QUEUE, params={"include_excluded": "true"}, headers=headers(world.scope))
    ).json()["items"]
    assert {p["id"] for p in everything} >= {p["id"] for p in items}


async def test_show_excluded_lists_dealbreaker_hits_last_with_the_reason(
    session: AsyncSession, make_client: MakeClient
) -> None:
    world = await dating_world(session)
    await set_prefs(session, world, "serious", ["smoking"])
    client = make_client(world)
    keep = await post_profile(client, world, attributes={"smoking": "No"})
    hit = await post_profile(client, world, attributes={"smoking": "Yes"})
    default = (await client.get(QUEUE, headers=headers(world.scope))).json()["items"]
    assert [p["id"] for p in default] == [keep["id"]]
    shown = (
        await client.get(QUEUE, params={"include_excluded": "true"}, headers=headers(world.scope))
    ).json()["items"]
    assert [p["id"] for p in shown] == [keep["id"], hit["id"]]
    ev = shown[-1]["latest_evaluation"]
    assert ev["dealbreakers_hit"] == ["smoking"] and ev["score"] == 0
    assert any(r["factor"] == "dealbreaker" and r["effect"] == "negative" for r in ev["reasons"])
    assert await actions_of(session, world) == []  # excluding is not a decision


async def test_preference_change_never_rewrites_prior_decisions_or_evaluations(
    session: AsyncSession, make_client: MakeClient
) -> None:
    world = await dating_world(session)
    v1 = await set_prefs(session, world, "serious")
    client = make_client(world)
    decided = await post_profile(client, world, attributes={"looking_for": "Something casual"})
    waiting = await post_profile(client, world, attributes={"looking_for": "Something casual"})
    first = {
        p["id"]: p["latest_evaluation"]
        for p in (await client.get(QUEUE, headers=headers(world.scope))).json()["items"]
    }
    liked = await decide(client, world, decided["id"], "pass")
    assert liked.status_code == 201, liked.text
    before = (await actions_of(session, world))[0]
    snapshot = (before.id, before.decision, before.execution, before.decided_at)

    v2 = await set_prefs(session, world, "casual_dating")
    assert v2 == v1 + 1
    items = (await client.get(QUEUE, headers=headers(world.scope))).json()["items"]
    assert [p["id"] for p in items] == [waiting["id"]]  # the decided one stays out
    assert items[0]["latest_evaluation"]["intent_mode"] == "casual_dating"
    assert items[0]["latest_evaluation"]["id"] != first[waiting["id"]]["id"]

    evals = ProfileEvaluationRepository(session, world.scope)
    decided_history = await evals.history(uuid.UUID(decided["id"]))
    assert [(h.preferences_version, h.intent_mode) for h in decided_history] == [
        (v1, IntentMode.SERIOUS)
    ]
    assert str(decided_history[0].id) == first[decided["id"]]["id"]
    waiting_history = await evals.history(uuid.UUID(waiting["id"]))
    assert [h.preferences_version for h in waiting_history] == [v1, v2]
    after = await actions_of(session, world)
    assert len(after) == 1
    assert (after[0].id, after[0].decision, after[0].execution, after[0].decided_at) == snapshot


# --------------------------------------------------------------------------- decisions


async def test_repeated_like_with_one_key_records_one_action(
    session: AsyncSession, make_client: MakeClient
) -> None:
    world = await dating_world(session)
    client = make_client(world)
    pid = (await post_profile(client, world))["id"]
    key = f"decide-{uuid.uuid4().hex}"
    first = await decide(client, world, pid, key=key)
    again = await decide(client, world, pid, key=key)
    assert first.status_code == 201 and again.status_code == 201, again.text
    assert again.headers.get("Idempotent-Replayed") == "true"
    assert first.json() == again.json()
    body = first.json()
    assert_contract("ProfileAction", body)
    assert body["idempotency_key"] == key and body["execution"] == "not_executable"
    assert len(await actions_of(session, world)) == 1
    # Same key, other decision: refused, still one action.
    problem(await decide(client, world, pid, "pass", key=key), 422, "idempotency_key_mismatch")
    assert len(await actions_of(session, world)) == 1
    # Decision audit carries IDs only.
    audits = (
        await session.scalars(
            select(AgentAction).where(
                AgentAction.user_id == world.scope.user_id,
                AgentAction.type == "profile_decision_recorded",
            )
        )
    ).all()
    assert len(audits) == 1
    assert set(audits[0].details) == {"profile_id", "profile_action_id", "decision"}


async def test_two_likes_with_two_keys_can_never_both_be_queued(
    session: AsyncSession, make_client: MakeClient
) -> None:
    world = await dating_world(session, swipe="VERIFIED")
    client = make_client(world)
    await enable_auto_swipe(client, world)
    pid = (await post_profile(client, world))["id"]
    a1 = (await decide(client, world, pid)).json()["id"]
    a2 = (await decide(client, world, pid, "superlike")).json()["id"]
    ok = await execute(client, world, a1)
    assert ok.status_code == 200 and ok.json()["execution"] == "queued", ok.text
    world.clock.advance(minutes=5)
    body = problem(await execute(client, world, a2), 422, "swipe_execution_disabled")
    assert "already queued" in body["errors"][0]["message"]
    executions = [a.execution for a in await actions_of(session, world)]
    assert executions.count(ProfileActionExecution.QUEUED) == 1
    assert ProfileActionExecution.EXECUTED not in executions


# --------------------------------------------------------------------------- the gate


async def test_unsupported_swipe_is_422_audited_and_replayed_without_a_second_audit(
    session: AsyncSession, make_client: MakeClient
) -> None:
    world = await dating_world(session, swipe="UNSUPPORTED")
    client = make_client(world)
    prof = await post_profile(client, world, bio="Private bio text 7731")
    aid = (await decide(client, world, prof["id"])).json()["id"]
    key = f"exec-{uuid.uuid4().hex}"
    body = problem(await execute(client, world, aid, key=key), 422, "swipe_execution_disabled")
    assert body["errors"] == [
        {"pointer": "/connection_id", "message": "swipe capability is not VERIFIED"}
    ]
    replay = await execute(client, world, aid, key=key)
    problem(replay, 422, "swipe_execution_disabled")
    assert replay.headers.get("Idempotent-Replayed") == "true"
    rows = await denials(session, world)
    assert len(rows) == 1
    audit = rows[0]
    assert audit.reason_code == "swipe_execution_disabled"
    assert audit.details["check"] == "/connection_id"
    assert audit.details["profile_action_id"] == aid
    assert "7731" not in str(audit.details) and prof["display_name"] not in str(audit.details)
    # Auto-swipe cannot even be turned on while swipe is not VERIFIED.
    refused = await client.put(
        f"/v1/connections/{world.connection_id}/settings",
        json={"auto_swipe_enabled": True},
        headers=headers(world.scope),
    )
    problem(refused, 422, "swipe_execution_disabled")
    (action,) = await actions_of(session, world)
    assert action.execution is ProfileActionExecution.NOT_EXECUTABLE


async def test_experimental_unverified_connection_never_executes(
    session: AsyncSession, make_client: MakeClient
) -> None:
    from app.db.enums import ConnectionTransport

    world = await dating_world(
        session, swipe="VERIFIED", transport=ConnectionTransport.UNOFFICIAL_API
    )
    client = make_client(world)
    pid = (await post_profile(client, world))["id"]
    aid = (await decide(client, world, pid)).json()["id"]
    body = problem(await execute(client, world, aid), 422, "swipe_execution_disabled")
    assert "experimental" in body["errors"][0]["message"]


async def test_device_offline_and_canceled_auto_swipe_refuse_execution(
    session: AsyncSession, make_client: MakeClient
) -> None:
    world = await dating_world(session, swipe="VERIFIED")
    client = make_client(world)
    await enable_auto_swipe(client, world)
    p1 = (await post_profile(client, world))["id"]
    a1 = (await decide(client, world, p1)).json()["id"]
    # The device goes offline: the connection is no longer connected.
    await ConnectionRepository(session, world.scope).set_status(
        world.connection_id, ConnectionStatus.DISCONNECTED
    )
    body = problem(await execute(client, world, a1), 422, "swipe_execution_disabled")
    assert "paired and connected" in body["errors"][0]["message"]
    await ConnectionRepository(session, world.scope).set_status(
        world.connection_id, ConnectionStatus.CONNECTED
    )
    # The user turns auto-swipe off (the "cancel" control): refused again.
    off = await client.put(
        f"/v1/connections/{world.connection_id}/settings",
        json={"auto_swipe_enabled": False},
        headers=headers(world.scope),
    )
    assert off.status_code == 200 and off.json()["auto_swipe_enabled"] is False
    body = problem(await execute(client, world, a1), 422, "swipe_execution_disabled")
    assert "auto-swipe" in body["errors"][0]["message"]
    # Unpaired: refused.
    await enable_auto_swipe(client, world)
    unpaired = await client.post(
        f"/v1/connections/{world.connection_id}/unpair", json={}, headers=headers(world.scope)
    )
    assert unpaired.status_code == 200, unpaired.text
    problem(await execute(client, world, a1), 422, "swipe_execution_disabled")
    checks = [d.details["reason"] for d in await denials(session, world)]
    assert len(checks) == 3
    (action,) = await actions_of(session, world)
    assert action.execution is ProfileActionExecution.NOT_EXECUTABLE
    assert action.executed_at is None and action.execution_requested_at is None


async def test_no_outcome_route_exists_so_queued_is_the_end(
    session: AsyncSession, make_client: MakeClient
) -> None:
    """Contract 1.3.1 has no device-report path for swipe outcomes, and this PR adds none:
    nothing can claim ``executed``, and a missing acknowledgment cannot turn into one."""
    world = await dating_world(session, swipe="VERIFIED")
    client = make_client(world)
    await enable_auto_swipe(client, world)
    pid = (await post_profile(client, world))["id"]
    aid = (await decide(client, world, pid)).json()["id"]
    assert (await execute(client, world, aid)).json()["execution"] == "queued"
    served = client._transport.app.openapi()["paths"]  # type: ignore[attr-defined]
    action_paths = [p for p in served if "profile-actions" in p]
    assert action_paths == ["/v1/profile-actions/{profile_action_id}/execute"]
    world.clock.advance(days=3)
    (action,) = await actions_of(session, world)
    await session.refresh(action)
    assert action.execution is ProfileActionExecution.QUEUED
    assert action.executed_at is None and action.execution_detail is None


async def test_pause_still_records_decisions_but_never_queues(
    session: AsyncSession, make_client: MakeClient
) -> None:
    world = await dating_world(session, swipe="VERIFIED")
    client = make_client(world)
    await enable_auto_swipe(client, world)
    pid = (await post_profile(client, world))["id"]
    await client.post("/v1/automation/pause", json={}, headers=headers(world.scope))
    recorded = await decide(client, world, pid)
    assert recorded.status_code == 201  # the user's own decision is not automation
    body = problem(
        await execute(client, world, recorded.json()["id"]), 422, "swipe_execution_disabled"
    )
    assert body["errors"][0]["message"] == "automation is paused"
    await client.post("/v1/automation/resume", json={}, headers=headers(world.scope))
    assert (await execute(client, world, recorded.json()["id"])).json()["execution"] == "queued"


async def test_skip_is_never_executed(session: AsyncSession, make_client: MakeClient) -> None:
    world = await dating_world(session, swipe="VERIFIED")
    client = make_client(world)
    await enable_auto_swipe(client, world)
    pid = (await post_profile(client, world))["id"]
    aid = (await decide(client, world, pid, "skip")).json()["id"]
    body = problem(await execute(client, world, aid), 422, "swipe_execution_disabled")
    assert body["errors"][0]["pointer"] == "/decision"


# --------------------------------------------------------------------------- invalidation


async def test_provider_update_invalidates_and_a_newer_import_supersedes_the_decision(
    session: AsyncSession, make_client: MakeClient
) -> None:
    world = await dating_world(session, swipe="VERIFIED")
    client = make_client(world)
    await enable_auto_swipe(client, world)
    prof = await post_profile(client, world)
    aid = (await decide(client, world, prof["id"])).json()["id"]
    service = DiscoveryService(now=world.clock)
    invalidated = await service.invalidate_profile(session, world.scope, uuid.UUID(prof["id"]))
    first_at = invalidated.invalidated_at
    world.clock.advance(minutes=1)
    again = await service.invalidate_profile(session, world.scope, uuid.UUID(prof["id"]))
    assert again.invalidated_at == first_at  # idempotent
    body = problem(await execute(client, world, aid), 422, "swipe_execution_disabled")
    assert "stale" in body["errors"][0]["message"]
    # A skip keeps the profile in the queue, shown with the stale badge.
    other = await post_profile(client, world)
    await decide(client, world, other["id"], "skip")
    await service.invalidate_profile(session, world.scope, uuid.UUID(other["id"]))
    queued = (await client.get(QUEUE, headers=headers(world.scope))).json()["items"]
    assert [(p["id"], p["stale"]) for p in queued] == [(other["id"], True)]
    # The source shows the profile again (new content): fresh, but the earlier decision was
    # made on other content and is never executed.
    world.clock.advance(minutes=1)
    fresh = await post_profile(
        client,
        world,
        external_profile_id=prof["external_profile_id"],
        bio="Changed bio",
        source_observed_at=world.clock.now.isoformat().replace("+00:00", "Z"),
    )
    assert fresh["id"] == prof["id"] and fresh["stale"] is False
    body = problem(await execute(client, world, aid), 422, "swipe_execution_disabled")
    assert "imported again" in body["errors"][0]["message"]
    world.clock.advance(minutes=1)
    redo = (await decide(client, world, prof["id"])).json()["id"]
    assert (await execute(client, world, redo)).json()["execution"] == "queued"


# --------------------------------------------------------------------------- limits


async def test_decision_rate_limit_is_429_and_never_blocks_a_replay(
    session: AsyncSession, make_client: MakeClient
) -> None:
    world = await dating_world(session)
    client = make_client(world, limits=DiscoveryLimits(decisions_per_minute=2))
    pids = [(await post_profile(client, world))["id"] for _ in range(3)]
    first_key = f"decide-{uuid.uuid4().hex}"
    assert (await decide(client, world, pids[0], key=first_key)).status_code == 201
    assert (await decide(client, world, pids[1])).status_code == 201
    limited_key = f"decide-{uuid.uuid4().hex}"
    limited = await decide(client, world, pids[2], key=limited_key)
    problem(limited, 429, "rate_limited")
    assert 1 <= int(limited.headers["Retry-After"]) <= 61
    # A replay of an earlier key is answered from the store, not limited.
    replay = await decide(client, world, pids[0], key=first_key)
    assert replay.status_code == 201 and replay.headers.get("Idempotent-Replayed") == "true"
    assert len(await actions_of(session, world)) == 2
    # The limited key was not stored: the same key works once the window has passed.
    world.clock.advance(seconds=61)
    later = await decide(client, world, pids[2], key=limited_key)
    assert later.status_code == 201, later.text
    assert later.headers.get("Idempotent-Replayed") is None


async def test_daily_decision_limit(session: AsyncSession, make_client: MakeClient) -> None:
    world = await dating_world(session)
    client = make_client(world, limits=DiscoveryLimits(decisions_per_day=2))
    pid = (await post_profile(client, world))["id"]
    for _ in range(2):
        assert (await decide(client, world, pid, "skip")).status_code == 201
        world.clock.advance(minutes=5)
    limited = await decide(client, world, pid, "skip")
    problem(limited, 429, "rate_limited")
    assert int(limited.headers["Retry-After"]) > 3600
    world.clock.advance(days=1)
    assert (await decide(client, world, pid, "skip")).status_code == 201


async def test_execution_daily_cap_and_spacing(
    session: AsyncSession, make_client: MakeClient
) -> None:
    world = await dating_world(session, swipe="VERIFIED")
    client = make_client(
        world,
        limits=DiscoveryLimits(executions_per_day=2, min_execution_interval=timedelta(seconds=30)),
    )
    await enable_auto_swipe(client, world)
    aids = []
    for _ in range(3):
        pid = (await post_profile(client, world))["id"]
        aids.append((await decide(client, world, pid)).json()["id"])
    assert (await execute(client, world, aids[0])).json()["execution"] == "queued"
    body = problem(await execute(client, world, aids[1]), 422, "swipe_execution_disabled")
    assert "too soon" in body["errors"][0]["message"]
    world.clock.advance(seconds=31)
    assert (await execute(client, world, aids[1])).json()["execution"] == "queued"
    world.clock.advance(minutes=10)
    body = problem(await execute(client, world, aids[2]), 422, "swipe_execution_disabled")
    assert "daily swipe limit" in body["errors"][0]["message"]
    world.clock.advance(days=1)
    assert (await execute(client, world, aids[2])).json()["execution"] == "queued"


# --------------------------------------------------------------------------- privacy


async def test_private_fields_are_absent_and_never_inferred(
    session: AsyncSession, make_client: MakeClient
) -> None:
    world = await dating_world(session)
    await set_prefs(session, world, "serious", ["smoking"])
    client = make_client(world)
    # Credential-like or unknown top-level fields are refused (extra="forbid").
    for extra in ({"phone_number": "+15550100"}, {"session_token": "x"}, {"photos": ["a.jpg"]}):
        resp = await client.post(
            "/v1/profiles", json={**_body(), **extra}, headers=headers(world.scope)
        )
        problem(resp, 422, "validation_error")
    prof = await post_profile(client, world, attributes={"looking_for": "Long-term relationship"})
    assert_contract("Profile", prof)
    assert prof["attributes"] == {"looking_for": "Long-term relationship"}
    items = (await client.get(QUEUE, headers=headers(world.scope))).json()["items"]
    assert items[0]["attributes"] == {"looking_for": "Long-term relationship"}
    ev = items[0]["latest_evaluation"]
    # The dealbreaker attribute is not stated: listed as unknown, never filled in.
    assert any("smoking" in u for u in ev["known_unknowns"]), ev["known_unknowns"]
    assert "smoking" not in items[0]["attributes"]
    aid = (await decide(client, world, prof["id"])).json()["id"]
    await execute(client, world, aid)
    rows = (
        await session.scalars(select(AgentAction).where(AgentAction.user_id == world.scope.user_id))
    ).all()
    for row in rows:
        text = str(row.details)
        assert prof["bio"] not in text and prof["display_name"] not in text
        assert "Long-term" not in text


def _body() -> dict[str, Any]:
    from tests.matching.conftest import import_body

    return import_body()


# --------------------------------------------------------------------------- isolation


async def test_cross_user_isolation(session: AsyncSession, make_client: MakeClient) -> None:
    world = await dating_world(session, swipe="VERIFIED")
    client = make_client(world)
    pid = (await post_profile(client, world))["id"]
    aid = (await decide(client, world, pid)).json()["id"]
    stranger = await make_user(session)
    problem(
        await client.post(
            f"/v1/profiles/{pid}/decision", json={"decision": "like"}, headers=headers(stranger)
        ),
        404,
        "not_found",
    )
    problem(
        await client.post(f"/v1/profile-actions/{aid}/execute", json={}, headers=headers(stranger)),
        404,
        "not_found",
    )
    assert (await client.get(QUEUE, headers=headers(stranger))).json()["items"] == []
    service = DiscoveryService(now=world.clock)
    try:
        await service.invalidate_profile(session, stranger, uuid.UUID(pid))
    except NotFoundError:
        pass
    else:  # pragma: no cover
        raise AssertionError("another user's profile was invalidated")
    # Limits are per user: the stranger deciding does not use up our allowance.
    limited = make_client(world, limits=DiscoveryLimits(decisions_per_minute=2))
    theirs = (await post_profile(limited, world))["id"]
    assert theirs != pid
    their_profile = await limited.post("/v1/profiles", json=_body(), headers=headers(stranger))
    for _ in range(2):
        resp = await limited.post(
            f"/v1/profiles/{their_profile.json()['id']}/decision",
            json={"decision": "skip"},
            headers=headers(stranger),
        )
        assert resp.status_code == 201, resp.text
    assert (await decide(limited, world, theirs)).status_code == 201
    ours = await session.scalar(
        select(func.count())
        .select_from(ProfileAction)
        .where(ProfileAction.user_id == world.scope.user_id)
    )
    assert ours == 2
    assert new_id() != pid
