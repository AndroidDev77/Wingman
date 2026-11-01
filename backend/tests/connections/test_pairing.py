"""Contract 1.2.0 connection pairing and capability trust (ADR 0007, I-017) over HTTP.

Every 2xx body is validated against the contract; every error is problem+json."""

import dataclasses
import uuid
from datetime import UTC, datetime
from typing import Any

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.connectors.capabilities import CapabilityName, ConnectorCapabilities
from app.db.enums import (
    AgentActionType,
    ApprovalReason,
    ApprovalStatus,
    CapabilityVerification,
    DeliveryState,
    MessageOrigin,
    OutboxInstructionState,
)
from app.db.models import AgentAction, Approval, Message, OutboxInstruction, PlatformConnection
from app.services.connections import (
    CLIENT_VERIFIED_NOTE,
    ConnectionService,
    trust_claimed_capabilities,
)
from tests.automation.conftest import (
    World,
    assert_contract,
    headers,
    make_world,
    problem,
)
from tests.automation.test_device_routes import claim, post_event, queued_device_send, sms_world
from tests.db.conftest import make_user

INSTALL = "00000000-0000-4000-8000-00000000c0de"
OTHER_INSTALL = "00000000-0000-4000-8000-00000000beef"


def sms_caps(status: str = "VERIFIED", *, version: str = "0.2.0") -> dict[str, Any]:
    """What a (possibly lying) client claims for SMS."""

    def cap(s: str, **extra: Any) -> dict[str, Any]:
        out: dict[str, Any] = {"status": s, **extra}
        if s == "VERIFIED":
            out |= {"evidence": "trust me", "verified_at": "2026-11-01T00:00:00Z"}
        return out

    return {
        "schema_version": "connector_capabilities.v1",
        "platform": "sms",
        "connector_version": version,
        "capabilities": {
            "read": cap(status),
            "draft": cap("UNSUPPORTED"),
            "send": cap(status, reports_delivery_receipts=True),
            "swipe": cap("UNSUPPORTED"),
            "calendar_write": cap("UNSUPPORTED"),
        },
        "assessed_at": "2026-11-01T00:00:00Z",
    }


async def pair(
    client: httpx.AsyncClient,
    world: World,
    *,
    platform: str = "sms",
    install: str | None = INSTALL,
    caps: dict[str, Any] | None = None,
    key: str | None = None,
    scope_user: uuid.UUID | None = None,
) -> httpx.Response:
    body: dict[str, Any] = {"platform": platform, "display_name": "My phone"}
    if platform != "mock":
        body["device_install_id"] = install
        body["capabilities"] = caps if caps is not None else sms_caps()
    hdrs = headers(world.scope, key)
    if scope_user is not None:
        hdrs["X-Test-User"] = str(scope_user)
    resp = await client.post("/v1/connections", json=body, headers=hdrs)
    if resp.status_code in (200, 201):
        assert_contract("Connection", resp.json())
    return resp


def on(world: World, connection_id: str, platform: str = "sms") -> World:
    """The same world, addressed at another of its user's connections."""
    return dataclasses.replace(world, connection_id=uuid.UUID(connection_id), platform=platform)


# --------------------------------------------------------------------------- trust


def test_trust_never_returns_client_verified() -> None:
    claimed = ConnectorCapabilities.model_validate(sms_caps("VERIFIED"))
    stored = trust_claimed_capabilities(claimed)
    for name in ("read", "send"):
        entry = stored["capabilities"][name]
        assert entry["status"] == "UNVERIFIED"
        assert "evidence" not in entry and "verified_at" not in entry
        assert entry["notes"] == CLIENT_VERIFIED_NOTE
    assert stored["capabilities"]["send"]["reports_delivery_receipts"] is True
    assert stored["capabilities"]["swipe"] == {"status": "UNSUPPORTED"}
    ConnectorCapabilities.model_validate(stored)


def test_trust_keeps_server_verified_only_for_same_connector_version() -> None:
    current = sms_caps("VERIFIED", version="0.2.0")
    current["capabilities"]["send"]["evidence"] = "docs/integrations/android_sms.md#g5"
    claimed = ConnectorCapabilities.model_validate(sms_caps("VERIFIED", version="0.2.0"))
    kept = trust_claimed_capabilities(claimed, current)
    assert kept["capabilities"]["send"]["status"] == "VERIFIED"
    assert kept["capabilities"]["send"]["evidence"] == "docs/integrations/android_sms.md#g5"
    newer = ConnectorCapabilities.model_validate(sms_caps("VERIFIED", version="0.3.0"))
    assert trust_claimed_capabilities(newer, current)["capabilities"]["send"]["status"] == (
        "UNVERIFIED"
    )
    lowered = ConnectorCapabilities.model_validate(sms_caps("UNSUPPORTED", version="0.2.0"))
    assert trust_claimed_capabilities(lowered, current)["capabilities"]["send"] == {
        "status": "UNSUPPORTED",
        "reports_delivery_receipts": True,
    }


# --------------------------------------------------------------------------- pairing


async def test_pairing_forces_capabilities_to_non_verified(
    session: AsyncSession, make_conn_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conn_client(world)
    resp = await pair(client, world)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["platform"] == "sms" and body["status"] == "connected"
    assert body["device_install_id"] == INSTALL
    assert body["paired_at"] is not None and body["unpaired_at"] is None
    caps = body["capabilities"]["capabilities"]
    assert all(c["status"] != "VERIFIED" for c in caps.values())
    assert caps["send"]["status"] == "UNVERIFIED"
    row = await session.get(PlatformConnection, uuid.UUID(body["id"]))
    assert row is not None and row.send_capability is CapabilityVerification.UNVERIFIED
    assert row.read_capability is CapabilityVerification.UNVERIFIED
    audit = (
        await session.scalars(
            select(AgentAction).where(
                AgentAction.user_id == world.scope.user_id,
                AgentAction.type == AgentActionType.CONNECTION_PAIRED,
            )
        )
    ).one()
    assert audit.details["connection_id"] == body["id"]
    assert audit.details["client_claimed_verified"] == ["read", "send"]
    assert audit.details["send_capability"] == "UNVERIFIED"
    # The new connection is listed and readable.
    listed = await client.get("/v1/connections", headers=headers(world.scope))
    assert listed.status_code == 200
    assert_contract("ConnectionPage", listed.json())
    assert body["id"] in [c["id"] for c in listed.json()["items"]]
    one = await client.get(f"/v1/connections/{body['id']}", headers=headers(world.scope))
    assert one.status_code == 200 and one.json() == body


async def test_client_claiming_verified_never_gets_autopilot_sends(
    session: AsyncSession, make_conn_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="autopilot")
    client = make_conn_client(world)
    paired = (await pair(client, world)).json()
    sms = on(world, paired["id"])

    first = await post_event(client, sms, sms.event("hi"))
    assert first.status_code == 202, first.text
    a1 = await session.scalar(
        select(Approval).where(
            Approval.user_id == world.scope.user_id, Approval.status == ApprovalStatus.PENDING
        )
    )
    assert a1 is not None and ApprovalReason.CAPABILITY_UNVERIFIED in a1.reasons
    # The user approves it: an approved_draft send is allowed on UNVERIFIED (ADR 0005).
    _, msg = await world.controller.approve(session, world.scope, a1.id, edited_text=None)
    await world.dispatcher.dispatch(session, world.scope, msg.id)
    [ins] = (await claim(client, sms, device=INSTALL)).json()["instructions"]
    assert ins["authorization"]["origin"] == "approved_draft"
    ack = await post_event(
        client,
        sms,
        sms.outcome(
            "ack", message_id=uuid.UUID(ins["outbound_message_id"]), key=ins["idempotency_key"]
        ),
    )
    assert ack.status_code == 202, ack.text

    # The device now reports VERIFIED again: still stored UNVERIFIED.
    raw = sms.event()
    raw.update(
        type="connection.capabilities_changed",
        external_message_id=None,
        payload={"capabilities": sms_caps("VERIFIED")},
    )
    assert (await post_event(client, sms, raw)).status_code == 202
    row = await session.get(PlatformConnection, uuid.UUID(paired["id"]))
    assert row is not None
    await session.refresh(row)
    assert row.send_capability is CapabilityVerification.UNVERIFIED

    # Next reply: approval (capability_unverified), never an autopilot send.
    second = await post_event(client, sms, sms.event("how are you?"))
    assert second.status_code == 202
    a2 = await session.scalar(
        select(Approval).where(
            Approval.user_id == world.scope.user_id, Approval.status == ApprovalStatus.PENDING
        )
    )
    assert a2 is not None and a2.reasons == [ApprovalReason.CAPABILITY_UNVERIFIED]
    autopilot = (
        await session.scalars(
            select(Message).where(
                Message.user_id == world.scope.user_id, Message.origin == MessageOrigin.AUTOPILOT
            )
        )
    ).all()
    assert autopilot == []
    assert (await claim(client, sms, device=INSTALL)).json()["instructions"] == []


async def test_only_operator_path_sets_verified(session: AsyncSession, make_conn_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conn_client(world)
    paired = (await pair(client, world)).json()
    service = ConnectionService(env="test")
    updated = await service.record_verification(
        session,
        world.scope,
        uuid.UUID(paired["id"]),
        CapabilityName.SEND,
        evidence="docs/integrations/android_sms.md G5 run 2026-11-05",
        verified_at=datetime(2026, 11, 5, tzinfo=UTC),
    )
    assert updated.send_capability is CapabilityVerification.VERIFIED
    assert updated.read_capability is CapabilityVerification.UNVERIFIED
    got = (await client.get(f"/v1/connections/{paired['id']}", headers=headers(world.scope))).json()
    assert_contract("Connection", got)
    assert got["capabilities"]["capabilities"]["send"]["evidence"].startswith("docs/")
    # A same-version device report keeps it; a new connector version drops it.
    sms = on(world, paired["id"])
    for version, expected in (("0.2.0", "VERIFIED"), ("0.3.0", "UNVERIFIED")):
        raw = sms.event()
        raw.update(
            type="connection.capabilities_changed",
            external_message_id=None,
            payload={"capabilities": sms_caps("VERIFIED", version=version)},
        )
        assert (await post_event(client, sms, raw)).status_code == 202
        row = await session.get(PlatformConnection, uuid.UUID(paired["id"]))
        assert row is not None
        await session.refresh(row)
        assert row.send_capability.value == expected, version


async def test_mock_pairing_refused_in_prod_allowed_elsewhere(
    session: AsyncSession, make_conn_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    prod = make_conn_client(world, env="prod")
    body = problem(await pair(prod, world, platform="mock"), 422, "validation_error")
    assert body["errors"][0]["pointer"] == "/platform"
    before = await session.scalar(
        select(PlatformConnection.id)
        .where(
            PlatformConnection.user_id == world.scope.user_id, PlatformConnection.platform == "mock"
        )
        .order_by(PlatformConnection.created_at.desc())
    )
    assert before == world.connection_id  # nothing new was created

    dev = make_conn_client(world, env="dev")
    ok = await pair(dev, world, platform="mock")
    assert ok.status_code == 201, ok.text
    assert ok.json()["device_install_id"] is None
    assert ok.json()["capabilities"]["capabilities"]["send"]["status"] == "VERIFIED"
    assert "simulat" in ok.json()["capabilities"]["capabilities"]["send"]["notes"].lower()
    # mock takes no client capabilities and no install binding.
    resp = await dev.post(
        "/v1/connections",
        json={"platform": "mock", "display_name": "x", "capabilities": sms_caps()},
        headers=headers(world.scope),
    )
    problem(resp, 422, "validation_error")


async def test_request_validation(session: AsyncSession, make_conn_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conn_client(world)
    no_install = await client.post(
        "/v1/connections",
        json={"platform": "sms", "display_name": "x", "capabilities": sms_caps()},
        headers=headers(world.scope),
    )
    assert problem(no_install, 422, "validation_error")["errors"][0]["pointer"] == (
        "/device_install_id"
    )
    wrong = sms_caps()
    wrong["platform"] = "android_notification"
    problem(await pair(client, world, caps=wrong), 422, "validation_error")
    tinder = await client.post(
        "/v1/connections",
        json={"platform": "tinder", "display_name": "x"},
        headers=headers(world.scope),
    )
    problem(tinder, 422, "validation_error")
    unknown = await client.post(
        "/v1/connections",
        json={"platform": "mock", "display_name": "x", "verified": True},
        headers=headers(world.scope),
    )
    problem(unknown, 422, "validation_error")
    missing_key = await client.post(
        "/v1/connections",
        json={"platform": "mock", "display_name": "x"},
        headers={"X-Test-User": str(world.scope.user_id)},
    )
    problem(missing_key, 400, "idempotency_key_required")


async def test_idempotency_and_repairing(session: AsyncSession, make_conn_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conn_client(world)
    first = await pair(client, world, key="pair-key-0001")
    assert first.status_code == 201
    replay = await pair(client, world, key="pair-key-0001")
    assert replay.status_code == 201 and replay.headers["Idempotent-Replayed"] == "true"
    assert replay.json() == first.json()
    mismatch = await pair(client, world, key="pair-key-0001", install=OTHER_INSTALL)
    problem(mismatch, 422, "idempotency_key_mismatch")
    # A new key for the same install and platform: the existing connection, 200.
    again = await pair(client, world)
    assert again.status_code == 200 and again.json()["id"] == first.json()["id"]
    # Another install pairs separately.
    other = await pair(client, world, install=OTHER_INSTALL)
    assert other.status_code == 201 and other.json()["id"] != first.json()["id"]
    # After unpairing, the same install pairs a NEW connection.
    unpaired = await client.post(
        f"/v1/connections/{first.json()['id']}/unpair", json={}, headers=headers(world.scope)
    )
    assert unpaired.status_code == 200
    fresh = await pair(client, world)
    assert fresh.status_code == 201 and fresh.json()["id"] != first.json()["id"]


async def test_cross_user_isolation(session: AsyncSession, make_conn_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conn_client(world)
    mine = (await pair(client, world)).json()
    intruder = await make_user(session)
    h = headers(intruder)
    problem(await client.get(f"/v1/connections/{mine['id']}", headers=h), 404, "not_found")
    problem(
        await client.post(f"/v1/connections/{mine['id']}/unpair", json={}, headers=h),
        404,
        "not_found",
    )
    listed = await client.get("/v1/connections", headers=h)
    assert listed.status_code == 200 and listed.json()["items"] == []
    # The same install id under another account is a separate connection (no leak).
    theirs = await pair(client, world, scope_user=intruder.user_id)
    assert theirs.status_code == 201 and theirs.json()["id"] != mine["id"]
    row = await session.get(PlatformConnection, uuid.UUID(mine["id"]))
    assert row is not None and row.unpaired_at is None


# --------------------------------------------------------------------------- unpair


async def test_unpair_cancels_pending_automated_work_with_audit(
    session: AsyncSession, make_conn_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_conn_client(world)
    queued = await queued_device_send(session, client, world)
    assert queued.origin is MessageOrigin.AUTOPILOT
    ins = await session.scalar(
        select(OutboxInstruction).where(OutboxInstruction.outbound_message_id == queued.id)
    )
    assert ins is not None and ins.state is OutboxInstructionState.PENDING

    resp = await client.post(
        f"/v1/connections/{world.connection_id}/unpair", json={}, headers=headers(world.scope)
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert_contract("ConnectionUnpairResult", body)
    assert body["canceled_message_count"] == 1
    assert body["canceled_instruction_count"] == 1
    assert body["in_flight_message_count"] == 0
    conn = body["connection"]
    assert conn["status"] == "disconnected" and conn["unpaired_at"] is not None
    assert {c["status"] for c in conn["capabilities"]["capabilities"].values()} == {"UNSUPPORTED"}

    await session.refresh(ins)
    msg = await session.get(Message, queued.id)
    assert ins.state is OutboxInstructionState.CANCELED
    assert msg is not None and msg.delivery_state is DeliveryState.CANCELED
    actions = (
        await session.scalars(
            select(AgentAction)
            .where(AgentAction.user_id == world.scope.user_id)
            .order_by(AgentAction.occurred_at, AgentAction.id)
        )
    ).all()
    canceled = [a for a in actions if a.type is AgentActionType.SEND_CANCELED]
    assert [(a.message_id, a.reason_code) for a in canceled] == [
        (queued.id, "connection_unavailable")
    ]
    [summary] = [a for a in actions if a.type is AgentActionType.CONNECTION_UNPAIRED]
    assert summary.details["canceled_messages"] == 1
    assert summary.details["connection_id"] == str(world.connection_id)
    audit = await client.get(
        "/v1/agent-actions", params={"type": "connection_unpaired"}, headers=headers(world.scope)
    )
    assert_contract("AgentActionPage", audit.json())
    assert len(audit.json()["items"]) == 1

    # Nothing can go out any more on this connection: an unpaired connection cannot be
    # claimed on at all, not even by the install it was paired with (I-018).
    gone = problem(await claim(client, world), 422, "validation_error")
    assert gone["errors"][0]["pointer"] == "/device_install_id"
    sent = await client.post(
        f"/v1/conversations/{queued.conversation_id}/messages",
        json={"text": "still there?"},
        headers=headers(world.scope),
    )
    problem(sent, 422, "capability_unsupported")
    inbound = await post_event(client, world, world.event("hello?"))
    assert problem(inbound, 422, "validation_error")["errors"][0]["pointer"] == "/connection_id"
    # Unpairing again changes nothing.
    again = await client.post(
        f"/v1/connections/{world.connection_id}/unpair", json={}, headers=headers(world.scope)
    )
    assert again.status_code == 200
    assert_contract("ConnectionUnpairResult", again.json())
    assert again.json()["canceled_message_count"] == 0
    assert again.json()["connection"]["unpaired_at"] == conn["unpaired_at"]


async def test_unpair_leaves_leased_send_in_flight_and_accepts_its_outcome(
    session: AsyncSession, make_conn_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_conn_client(world)
    queued = await queued_device_send(session, client, world)
    [ins] = (await claim(client, world)).json()["instructions"]
    resp = await client.post(
        f"/v1/connections/{world.connection_id}/unpair", json={}, headers=headers(world.scope)
    )
    assert resp.status_code == 200
    assert resp.json()["in_flight_message_count"] == 1
    assert resp.json()["canceled_message_count"] == 0
    late = await post_event(
        client, world, world.outcome("ack", message_id=queued.id, key=ins["idempotency_key"])
    )
    assert late.status_code == 202, late.text
    msg = await session.get(Message, queued.id)
    assert msg is not None
    await session.refresh(msg)
    assert msg.delivery_state is DeliveryState.SENT


async def test_unpair_cancels_pending_approvals(session: AsyncSession, make_conn_client) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session, mode="assisted")
    client = make_conn_client(world)
    assert (await post_event(client, world, world.event("hi"))).status_code == 202
    approval = await session.scalar(select(Approval).where(Approval.user_id == world.scope.user_id))
    assert approval is not None and approval.status is ApprovalStatus.PENDING
    resp = await client.post(
        f"/v1/connections/{world.connection_id}/unpair", json={}, headers=headers(world.scope)
    )
    assert resp.status_code == 200
    assert resp.json()["canceled_approval_count"] == 1
    await session.refresh(approval)
    assert approval.status is ApprovalStatus.CANCELED
    row = await session.scalar(
        select(AgentAction).where(
            AgentAction.approval_id == approval.id,
            AgentAction.type == AgentActionType.APPROVAL_CANCELED,
        )
    )
    assert row is not None and row.reason_code == "connection_unavailable"
    # Approving it afterwards is refused.
    late = await client.post(
        f"/v1/approvals/{approval.id}/approve", json={}, headers=headers(world.scope)
    )
    problem(late, 409, "approval_not_pending")
