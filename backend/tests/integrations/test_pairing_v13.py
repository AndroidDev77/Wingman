"""Contract 1.3.0 pairing (ADR 0008): dating and iMessage platforms, transports, the
experimental consent, revoking it, and the no-credentials rule. Bodies are validated
against the contract; errors are problem+json."""

import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import (
    AgentActionType,
    ApprovalStatus,
    AssistedDeliveryStatus,
    CapabilityVerification,
    ConnectionTransport,
)
from app.db.models import AgentAction, Approval, AssistedDelivery, PlatformConnection
from tests.automation.conftest import assert_contract, headers, make_world, problem
from tests.db.conftest import make_user
from tests.integrations.conftest import consent, inbound13, pair_body


async def test_experimental_pairing_without_consent_is_422(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_v13_client(world)
    resp = await client.post(
        "/v1/connections", json=pair_body(with_consent=False), headers=headers(world.scope)
    )
    body = problem(resp, 422, "experimental_consent_required")
    assert body["errors"][0]["pointer"] == "/experimental_consent"
    count = await session.scalar(
        select(func.count())
        .select_from(PlatformConnection)
        .where(
            PlatformConnection.user_id == world.scope.user_id,
            PlatformConnection.experimental.is_(True),
        )
    )
    assert count == 0


async def test_experimental_pairing_records_consent_and_never_verified(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_v13_client(world)
    resp = await client.post(
        "/v1/connections",
        json=pair_body(send="VERIFIED", swipe="VERIFIED", profile_read="VERIFIED"),
        headers=headers(world.scope),
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert_contract("Connection", body)
    assert body["transport"] == "unofficial_api" and body["experimental"] is True
    assert body["experimental_consent"]["consent_text_version"] == "d021-v1"
    assert body["experimental_consent"]["revoked_at"] is None
    assert body["experimental_verified_at"] is None
    caps = body["capabilities"]["capabilities"]
    # ADR 0007 still applies: nothing the client claims is stored VERIFIED.
    assert {caps[n]["status"] for n in caps} <= {"UNVERIFIED", "UNSUPPORTED"}
    assert caps["profile_read"]["status"] == "UNVERIFIED"
    row = await session.get(PlatformConnection, uuid.UUID(body["id"]))
    assert row is not None and row.profile_read_capability is CapabilityVerification.UNVERIFIED
    types = (
        await session.scalars(
            select(AgentAction.type).where(AgentAction.user_id == world.scope.user_id)
        )
    ).all()
    assert AgentActionType.EXPERIMENTAL_CONSENT_RECORDED in types


async def test_consent_rules(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_v13_client(world)
    wrong = pair_body()
    wrong["experimental_consent"] = consent("tinder")
    body = problem(
        await client.post("/v1/connections", json=wrong, headers=headers(world.scope)),
        422,
        "validation_error",
    )
    assert body["errors"][0]["pointer"] == "/experimental_consent/platform"
    not_flagged = pair_body()
    not_flagged["experimental"] = False
    problem(
        await client.post("/v1/connections", json=not_flagged, headers=headers(world.scope)),
        422,
        "validation_error",
    )
    unack = pair_body()
    unack["experimental_consent"]["ban_risk_acknowledged"] = False
    problem(
        await client.post("/v1/connections", json=unack, headers=headers(world.scope)),
        422,
        "validation_error",
    )
    on_notification = pair_body(transport="notification")
    on_notification["experimental_consent"] = consent()
    problem(
        await client.post("/v1/connections", json=on_notification, headers=headers(world.scope)),
        422,
        "validation_error",
    )
    imessage_api = pair_body("imessage")
    problem(
        await client.post("/v1/connections", json=imessage_api, headers=headers(world.scope)),
        422,
        "validation_error",
    )
    no_transport = pair_body("bumble", transport=None)
    body = problem(
        await client.post("/v1/connections", json=no_transport, headers=headers(world.scope)),
        422,
        "validation_error",
    )
    assert body["errors"][0]["pointer"] == "/transport"


async def test_credentials_are_rejected_on_pairing(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_v13_client(world)
    for field in ("session_token", "password", "auth_token", "cookie", "otp"):
        req = pair_body()
        req[field] = "redacted"
        problem(
            await client.post("/v1/connections", json=req, headers=headers(world.scope)),
            422,
            "validation_error",
        )


async def test_notification_and_companion_pairing(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_v13_client(world)
    tinder = await client.post(
        "/v1/connections",
        json=pair_body("tinder", transport="notification"),
        headers=headers(world.scope),
    )
    assert tinder.status_code == 201, tinder.text
    assert_contract("Connection", tinder.json())
    assert tinder.json()["experimental"] is False and tinder.json()["experimental_consent"] is None
    again = await client.post(
        "/v1/connections",
        json=pair_body("tinder", transport="notification"),
        headers=headers(world.scope),
    )
    assert again.status_code == 200 and again.json()["id"] == tinder.json()["id"]
    # Same install and platform through another transport while paired: refused.
    other = await client.post(
        "/v1/connections", json=pair_body("tinder"), headers=headers(world.scope)
    )
    assert problem(other, 422, "validation_error")["errors"][0]["pointer"] == "/transport"
    mac = await client.post(
        "/v1/connections", json=pair_body("imessage", transport=None), headers=headers(world.scope)
    )
    assert mac.status_code == 201, mac.text
    assert mac.json()["transport"] == "companion"
    # Pre-1.3.0 connections report their implied transport.
    sms = await client.get(f"/v1/connections/{world.connection_id}", headers=headers(world.scope))
    assert sms.json()["transport"] == "simulator"
    assert_contract("Connection", sms.json())


async def test_revoking_consent_disables_and_cancels_pending_work(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="autopilot")
    client = make_v13_client(world)
    paired = await client.post(
        "/v1/connections", json=pair_body(send="VERIFIED"), headers=headers(world.scope)
    )
    conn_id = paired.json()["id"]
    import dataclasses

    exp_world = dataclasses.replace(world, connection_id=uuid.UUID(conn_id), platform="hinge")
    world.registry.mark_device_side(exp_world.connection_id)
    ev = inbound13(exp_world, source="unofficial_api", kind="chat_message", identity="linked")
    resp = await client.post(
        f"/v1/connections/{conn_id}/events", json=ev, headers=headers(world.scope, ev["event_id"])
    )
    assert resp.status_code == 202, resp.text
    pending = (
        await session.scalars(
            select(Approval).where(
                Approval.user_id == world.scope.user_id, Approval.status == ApprovalStatus.PENDING
            )
        )
    ).all()
    # Experimental: approval-only, never autopilot, even with a claimed VERIFIED send.
    assert len(pending) == 1
    assert "capability_unverified" in [r.value for r in pending[0].reasons]

    # Another user cannot revoke it.
    stranger = await make_user(session)
    problem(
        await client.post(
            f"/v1/connections/{conn_id}/experimental-consent/revoke",
            json={},
            headers=headers(stranger),
        ),
        404,
        "not_found",
    )
    revoked = await client.post(
        f"/v1/connections/{conn_id}/experimental-consent/revoke",
        json={},
        headers=headers(world.scope),
    )
    assert revoked.status_code == 200, revoked.text
    body = revoked.json()
    assert_contract("ConnectionUnpairResult", body)
    assert body["canceled_approval_count"] == 1
    conn = body["connection"]
    assert conn["status"] == "disconnected" and conn["unpaired_at"] is not None
    assert conn["experimental_consent"]["revoked_at"] is not None
    assert {c["status"] for c in conn["capabilities"]["capabilities"].values()} == {"UNSUPPORTED"}
    await session.refresh(pending[0])
    assert pending[0].status is ApprovalStatus.CANCELED
    types = (
        await session.scalars(
            select(AgentAction.type).where(AgentAction.user_id == world.scope.user_id)
        )
    ).all()
    assert AgentActionType.EXPERIMENTAL_CONSENT_REVOKED in types
    # Idempotent on state; inbound events on it are now refused.
    twice = await client.post(
        f"/v1/connections/{conn_id}/experimental-consent/revoke",
        json={},
        headers=headers(world.scope),
    )
    assert twice.status_code == 200 and twice.json()["canceled_approval_count"] == 0
    late = inbound13(exp_world)
    problem(
        await client.post(
            f"/v1/connections/{conn_id}/events",
            json=late,
            headers=headers(world.scope, late["event_id"]),
        ),
        422,
        "validation_error",
    )
    # Not an experimental connection: nothing to revoke.
    problem(
        await client.post(
            f"/v1/connections/{world.connection_id}/experimental-consent/revoke",
            json={},
            headers=headers(world.scope),
        ),
        422,
        "validation_error",
    )


async def test_revoking_consent_cancels_pending_assisted_delivery(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    from tests.integrations.helpers import experimental_world

    world = await experimental_world(session, send="UNSUPPORTED")
    client = make_v13_client(world)
    ev = inbound13(world, source="unofficial_api")
    assert (
        await client.post(
            f"/v1/connections/{world.connection_id}/events",
            json=ev,
            headers=headers(world.scope, ev["event_id"]),
        )
    ).status_code == 202
    row = await session.scalar(
        select(AssistedDelivery).where(AssistedDelivery.user_id == world.scope.user_id)
    )
    assert row is not None and row.status is AssistedDeliveryStatus.PENDING
    resp = await client.post(
        f"/v1/connections/{world.connection_id}/experimental-consent/revoke",
        json={},
        headers=headers(world.scope),
    )
    assert resp.status_code == 200, resp.text
    await session.refresh(row)
    assert row.status is AssistedDeliveryStatus.CANCELED
    assert row.cancel_reason == "connection_unavailable"
    conn = await session.get(PlatformConnection, world.connection_id)
    assert conn is not None and conn.transport is ConnectionTransport.UNOFFICIAL_API
