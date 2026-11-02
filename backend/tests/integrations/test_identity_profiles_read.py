"""Contract 1.3.0 (ADR 0008): mark-read (I-022), user-only identity links, profiles,
decisions and the swipe execution gate, and cross-user 404s on every new route."""

import dataclasses
import uuid
from datetime import timedelta
from typing import Any

import httpx
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import (
    AgentActionType,
    CapabilityVerification,
    ConnectionTransport,
    IntentMode,
    ProfileActionExecution,
)
from app.db.models import AgentAction, Conversation, IdentityLink, ProfileAction
from app.db.repositories import (
    ConnectionRepository,
    ContactRepository,
    ProfileEvaluationRepository,
)
from app.services.connections import ConnectionService
from tests.automation.conftest import (
    World,
    assert_contract,
    capabilities_doc,
    headers,
    make_world,
    problem,
)
from tests.automation.test_pipeline import inbound
from tests.db.conftest import make_user
from tests.integrations.helpers import EXPERIMENTAL_INSTALL

# --------------------------------------------------------------------------- mark read


async def test_mark_read_is_idempotent_and_scoped(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="manual")
    client = make_v13_client(world)
    await inbound(session, world, "hi")
    await inbound(session, world, "you there?")
    conv = await session.scalar(
        select(Conversation).where(Conversation.user_id == world.scope.user_id)
    )
    assert conv is not None and conv.unread_count == 2
    resp = await client.post(
        f"/v1/conversations/{conv.id}/read", headers=headers(world.scope, "read-key-0001")
    )
    assert resp.status_code == 200, resp.text
    assert_contract("ConversationDetail", resp.json())
    assert resp.json()["unread_count"] == 0
    again = await client.post(
        f"/v1/conversations/{conv.id}/read", json={}, headers=headers(world.scope)
    )
    assert again.status_code == 200 and again.json()["unread_count"] == 0
    replay = await client.post(
        f"/v1/conversations/{conv.id}/read", headers=headers(world.scope, "read-key-0001")
    )
    assert replay.headers.get("Idempotent-Replayed") == "true"
    problem(
        await client.post(
            f"/v1/conversations/{conv.id}/read",
            json={"unread_count": 5},
            headers=headers(world.scope),
        ),
        422,
        "validation_error",
    )
    stranger = await make_user(session)
    problem(
        await client.post(f"/v1/conversations/{conv.id}/read", headers=headers(stranger)),
        404,
        "not_found",
    )
    # A new inbound message counts again.
    await inbound(session, world, "hello?")
    await session.refresh(conv)
    assert conv.unread_count == 1


# --------------------------------------------------------------------------- identity links


async def two_contacts(session: AsyncSession, world: World) -> tuple[uuid.UUID, uuid.UUID]:
    contacts = ContactRepository(session, world.scope)
    a = await contacts.get_or_create(
        connection_id=world.connection_id, external_contact_id="hinge-1", display_name="Riley"
    )
    b = await contacts.get_or_create(
        connection_id=world.connection_id, external_contact_id="phone-1", display_name="Riley"
    )
    return a.id, b.id


async def test_identity_links_are_user_made_only(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="autopilot")
    client = make_v13_client(world)
    # Two contacts with the same display name, and inbound traffic: nothing links them.
    a, b = await two_contacts(session, world)
    await inbound(session, world, "hi", sender="hinge-1")
    await inbound(session, world, "hi", sender="phone-1", thread="thread-2")
    assert await session.scalar(select(func.count()).select_from(IdentityLink)) == 0

    resp = await client.post(
        "/v1/identity-links",
        json={"contact_id": str(b), "linked_contact_id": str(a)},
        headers=headers(world.scope),
    )
    assert resp.status_code == 201, resp.text
    link = resp.json()
    assert_contract("IdentityLink", link)
    assert link["origin"] == "user" and link["contact_ids"] == sorted([str(a), str(b)])
    same = await client.post(
        "/v1/identity-links",
        json={"contact_id": str(a), "linked_contact_id": str(b)},
        headers=headers(world.scope),
    )
    assert same.status_code == 200 and same.json()["id"] == link["id"]
    page = await client.get(f"/v1/contacts/{a}/identity-links", headers=headers(world.scope))
    assert_contract("IdentityLinkPage", page.json())
    assert [i["id"] for i in page.json()["items"]] == [link["id"]]
    page_b = await client.get(f"/v1/contacts/{b}/identity-links", headers=headers(world.scope))
    assert [i["id"] for i in page_b.json()["items"]] == [link["id"]]
    actions = (
        await session.scalars(
            select(AgentAction).where(
                AgentAction.user_id == world.scope.user_id,
                AgentAction.type == AgentActionType.IDENTITY_LINK_CREATED,
            )
        )
    ).all()
    assert len(actions) == 1 and actions[0].actor.value == "user"

    problem(
        await client.post(
            "/v1/identity-links",
            json={"contact_id": str(a), "linked_contact_id": str(a)},
            headers=headers(world.scope),
        ),
        422,
        "validation_error",
    )
    problem(
        await client.post(
            "/v1/identity-links",
            json={"contact_id": str(a), "linked_contact_id": str(b), "confidence": 0.9},
            headers=headers(world.scope),
        ),
        422,
        "validation_error",
    )
    stranger = await make_user(session)
    problem(
        await client.post(
            "/v1/identity-links",
            json={"contact_id": str(a), "linked_contact_id": str(b)},
            headers=headers(stranger),
        ),
        404,
        "not_found",
    )
    problem(
        await client.get(f"/v1/contacts/{a}/identity-links", headers=headers(stranger)),
        404,
        "not_found",
    )
    problem(
        await client.delete(f"/v1/identity-links/{link['id']}", headers=headers(stranger)),
        404,
        "not_found",
    )
    gone = await client.delete(f"/v1/identity-links/{link['id']}", headers=headers(world.scope))
    assert gone.status_code == 204
    problem(
        await client.delete(f"/v1/identity-links/{link['id']}", headers=headers(world.scope)),
        404,
        "not_found",
    )


def test_no_code_outside_the_link_service_creates_identity_links() -> None:
    """Static guard (ADR 0008): only the user-facing ``IdentityLinkService`` creates
    links. No other module (ingest, the pipeline, the AI layer) even imports the
    repository, so nothing can link people by inference."""
    from pathlib import Path

    app = Path(__file__).resolve().parents[2] / "app"
    users = {
        path.relative_to(app.parent).as_posix()
        for path in app.rglob("*.py")
        if "IdentityLinkRepository" in path.read_text()
    }
    assert users == {
        "app/db/repositories/__init__.py",
        "app/db/repositories/integrations.py",
        "app/services/integrations.py",  # IdentityLinkService (user calls only)
        "app/api/integrations.py",  # listing for the user
    }
    api = (app / "api" / "integrations.py").read_text()
    assert "IdentityLinkRepository(session, scope).create_once" not in api


# --------------------------------------------------------------------------- profiles and the gate


def profile_body(**kw: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "provider": "hinge",
        "external_profile_id": "hinge-profile-1",
        "source": "manual",
        "display_name": "Riley",
        "bio": "Runner.",
        "prompts": [{"prompt": "Typical Sunday", "answer": "Long run, then brunch"}],
        "attributes": {"age": 31, "interests": ["running"]},
        "source_observed_at": "2026-11-02T14:00:00Z",
    }
    body.update(kw)
    return body


async def swipe_world(
    session: AsyncSession, *, swipe: str = "VERIFIED", experimental: bool = False
) -> World:
    world = await make_world(session, mode="manual")
    doc = capabilities_doc("hinge")
    doc["capabilities"]["swipe"] = (
        {"status": "VERIFIED", "evidence": "operator run #1", "verified_at": "2026-11-01T00:00:00Z"}
        if swipe == "VERIFIED"
        else {"status": swipe}
    )
    conn = await ConnectionRepository(session, world.scope).create(
        platform="hinge",
        display_name="hinge",
        capabilities=doc,
        device_install_id=EXPERIMENTAL_INSTALL,
        paired_at=world.clock.now,
        transport=ConnectionTransport.UNOFFICIAL_API
        if experimental
        else ConnectionTransport.NOTIFICATION,
        consent_text_version="d021-v1" if experimental else None,
        consent_accepted_at=world.clock.now if experimental else None,
    )
    return dataclasses.replace(world, connection_id=conn.id, platform="hinge")


async def decide(
    client: httpx.AsyncClient, world: World, pid: str, decision: str = "like"
) -> httpx.Response:
    return await client.post(
        f"/v1/profiles/{pid}/decision",
        json={"decision": decision, "connection_id": str(world.connection_id)},
        headers=headers(world.scope),
    )


async def execute(client: httpx.AsyncClient, world: World, aid: str) -> httpx.Response:
    return await client.post(
        f"/v1/profile-actions/{aid}/execute", json={}, headers=headers(world.scope)
    )


async def test_profile_import_queue_decision_shapes(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    world = await swipe_world(session)
    client = make_v13_client(world)
    resp = await client.post("/v1/profiles", json=profile_body(), headers=headers(world.scope))
    assert resp.status_code == 201, resp.text
    prof = resp.json()
    assert_contract("Profile", prof)
    assert prof["stale"] is False and prof["latest_evaluation"] is None
    again = await client.post(
        "/v1/profiles", json=profile_body(bio="Runner and cook."), headers=headers(world.scope)
    )
    assert (
        again.status_code == 200
        and again.json()["id"] == prof["id"]
        and again.json()["bio"] == "Runner and cook."
    )
    # WING-019 writes evaluations; the queue shows the latest.
    await ProfileEvaluationRepository(session, world.scope).create(
        profile_id=uuid.UUID(prof["id"]),
        score=70,
        reasons=[
            {
                "factor": "interests",
                "effect": "positive",
                "detail": "Shares running",
                "evidence": ["attributes/interests"],
            }
        ],
        known_unknowns=["intent"],
        intent_mode=IntentMode.SERIOUS,
        dealbreakers_hit=[],
        confidence=0.5,
        evaluator_version="test",
    )
    queue = await client.get("/v1/profiles/review-queue", headers=headers(world.scope))
    assert_contract("ProfilePage", queue.json())
    assert queue.json()["items"][0]["latest_evaluation"]["score"] == 70
    skip = await decide(client, world, prof["id"], "skip")
    assert skip.status_code == 201, skip.text
    assert_contract("ProfileAction", skip.json())
    assert skip.json()["execution"] == "not_executable"
    still = await client.get("/v1/profiles/review-queue", headers=headers(world.scope))
    assert [p["id"] for p in still.json()["items"]] == [prof["id"]]  # skip stays queued
    liked = await decide(client, world, prof["id"], "like")
    assert liked.json()["execution"] == "not_executable"
    gone = await client.get("/v1/profiles/review-queue", headers=headers(world.scope))
    assert gone.json()["items"] == []
    # Validation: device import needs a profile_read connection; no credentials.
    problem(
        await client.post(
            "/v1/profiles",
            json=profile_body(source="device", connection_id=str(world.connection_id)),
            headers=headers(world.scope),
        ),
        422,
        "validation_error",
    )
    problem(
        await client.post(
            "/v1/profiles", json=profile_body(cookie="x"), headers=headers(world.scope)
        ),
        422,
        "validation_error",
    )
    problem(
        await client.post(
            "/v1/profiles", json=profile_body(provider="mock"), headers=headers(world.scope)
        ),
        422,
        "validation_error",
    )


async def test_execute_gate(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    world = await swipe_world(session)
    client = make_v13_client(world)
    pid = (
        await client.post("/v1/profiles", json=profile_body(), headers=headers(world.scope))
    ).json()["id"]
    aid = (await decide(client, world, pid)).json()["id"]
    # Auto-swipe off: refused.
    body = problem(await execute(client, world, aid), 422, "swipe_execution_disabled")
    assert "auto-swipe" in body["errors"][0]["message"]
    on = await client.put(
        f"/v1/connections/{world.connection_id}/settings",
        json={"auto_swipe_enabled": True},
        headers=headers(world.scope),
    )
    assert on.status_code == 200, on.text
    assert_contract("Connection", on.json())
    # Global pause: refused.
    await client.post("/v1/automation/pause", json={}, headers=headers(world.scope))
    problem(await execute(client, world, aid), 422, "swipe_execution_disabled")
    await client.post("/v1/automation/resume", json={}, headers=headers(world.scope))
    ok = await execute(client, world, aid)
    assert ok.status_code == 200, ok.text
    assert_contract("ProfileAction", ok.json())
    assert ok.json()["execution"] == "queued"  # accepted, NOT executed
    again = await execute(client, world, aid)
    assert again.status_code == 200 and again.json()["execution"] == "queued"
    # A second decision on the same profile cannot also be queued.
    aid2 = (await decide(client, world, pid, "superlike")).json()["id"]
    problem(await execute(client, world, aid2), 422, "swipe_execution_disabled")
    # Nothing ever claims execution happened.
    rows = (
        await session.scalars(
            select(ProfileAction).where(ProfileAction.user_id == world.scope.user_id)
        )
    ).all()
    assert ProfileActionExecution.EXECUTED not in {r.execution for r in rows}


async def test_execute_gate_refusals(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    # Swipe not VERIFIED: cannot even enable auto-swipe.
    world = await swipe_world(session, swipe="UNVERIFIED")
    client = make_v13_client(world)
    problem(
        await client.put(
            f"/v1/connections/{world.connection_id}/settings",
            json={"auto_swipe_enabled": True},
            headers=headers(world.scope),
        ),
        422,
        "swipe_execution_disabled",
    )
    pid = (
        await client.post("/v1/profiles", json=profile_body(), headers=headers(world.scope))
    ).json()["id"]
    aid = (await decide(client, world, pid)).json()["id"]
    problem(await execute(client, world, aid), 422, "swipe_execution_disabled")
    off = await client.put(
        f"/v1/connections/{world.connection_id}/settings",
        json={"auto_swipe_enabled": False},
        headers=headers(world.scope),
    )
    assert off.status_code == 200


async def test_experimental_connection_never_swipes_until_operator_verified(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await swipe_world(session, experimental=True)
    client = make_v13_client(world)
    body = problem(
        await client.put(
            f"/v1/connections/{world.connection_id}/settings",
            json={"auto_swipe_enabled": True},
            headers=headers(world.scope),
        ),
        422,
        "swipe_execution_disabled",
    )
    assert "experimental" in body["errors"][0]["message"]
    pid = (
        await client.post("/v1/profiles", json=profile_body(), headers=headers(world.scope))
    ).json()["id"]
    aid = (await decide(client, world, pid)).json()["id"]
    problem(await execute(client, world, aid), 422, "swipe_execution_disabled")
    # The operator path (no HTTP route) lifts the experimental gate.
    await ConnectionService(env="operator").record_experimental_verification(
        session,
        world.scope,
        world.connection_id,
        evidence="owner live test 2026-11-03",
        verified_at=world.clock.now,
    )
    on = await client.put(
        f"/v1/connections/{world.connection_id}/settings",
        json={"auto_swipe_enabled": True},
        headers=headers(world.scope),
    )
    assert on.status_code == 200, on.text
    assert on.json()["experimental_verified_at"] is not None
    assert (await execute(client, world, aid)).json()["execution"] == "queued"


async def test_skip_stale_and_wrong_platform_never_execute(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await swipe_world(session)
    client = make_v13_client(world)
    await client.put(
        f"/v1/connections/{world.connection_id}/settings",
        json={"auto_swipe_enabled": True},
        headers=headers(world.scope),
    )
    pid = (
        await client.post("/v1/profiles", json=profile_body(), headers=headers(world.scope))
    ).json()["id"]
    skip = (await decide(client, world, pid, "skip")).json()["id"]
    problem(await execute(client, world, skip), 422, "swipe_execution_disabled")
    old = (
        await client.post(
            "/v1/profiles",
            json=profile_body(
                external_profile_id="old-1", source_observed_at="2026-10-01T00:00:00Z"
            ),
            headers=headers(world.scope),
        )
    ).json()
    assert old["stale"] is True
    stale_action = (await decide(client, world, old["id"])).json()["id"]
    body = problem(await execute(client, world, stale_action), 422, "swipe_execution_disabled")
    assert "stale" in body["errors"][0]["message"]
    tinder = (
        await client.post(
            "/v1/profiles",
            json=profile_body(provider="tinder", external_profile_id="t-1"),
            headers=headers(world.scope),
        )
    ).json()
    wrong = await client.post(
        f"/v1/profiles/{tinder['id']}/decision",
        json={"decision": "like", "connection_id": str(world.connection_id)},
        headers=headers(world.scope),
    )
    problem(wrong, 422, "validation_error")
    no_conn = await client.post(
        f"/v1/profiles/{pid}/decision", json={"decision": "like"}, headers=headers(world.scope)
    )
    problem(await execute(client, world, no_conn.json()["id"]), 422, "swipe_execution_disabled")
    world.clock.advance(days=30)
    fresh_then_old = (await decide(client, world, pid)).json()["id"]
    problem(await execute(client, world, fresh_then_old), 422, "swipe_execution_disabled")
    _ = timedelta


async def test_profile_routes_are_user_scoped(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    world = await swipe_world(session)
    client = make_v13_client(world)
    pid = (
        await client.post("/v1/profiles", json=profile_body(), headers=headers(world.scope))
    ).json()["id"]
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
    problem(
        await client.put(
            f"/v1/connections/{world.connection_id}/settings",
            json={"auto_swipe_enabled": False},
            headers=headers(stranger),
        ),
        404,
        "not_found",
    )
    assert (await client.get("/v1/profiles/review-queue", headers=headers(stranger))).json()[
        "items"
    ] == []
    # A stranger's import of the same external ID is their own row.
    theirs = await client.post("/v1/profiles", json=profile_body(), headers=headers(stranger))
    assert theirs.status_code == 201 and theirs.json()["id"] != pid
    # Their decision cannot name our connection.
    problem(
        await client.post(
            f"/v1/profiles/{theirs.json()['id']}/decision",
            json={"decision": "like", "connection_id": str(world.connection_id)},
            headers=headers(stranger),
        ),
        422,
        "validation_error",
    )
    _ = CapabilityVerification
