"""Device connector routes over HTTP (contract v1.1.0, ADR 0006): ingest, outbox list,
claim, lease expiry, send outcomes. Every response validates against the contract."""

import uuid
from typing import Any

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import DeliveryState, MessageDirection, OutboxInstructionState
from app.db.models import Message, OutboxInstruction
from tests.automation.conftest import (
    DEVICE_INSTALL,
    World,
    assert_contract,
    headers,
    make_world,
    problem,
    reply,
    set_mode,
)

DEVICE = str(DEVICE_INSTALL)  # the install the test connections are paired with
OTHER_DEVICE = "00000000-0000-4000-8000-0000000000bb"


async def post_event(
    client: httpx.AsyncClient, world: World, raw: dict[str, Any]
) -> httpx.Response:
    return await client.post(
        f"/v1/connections/{world.connection_id}/events",
        json=raw,
        headers=headers(world.scope, raw["event_id"]),
    )


async def claim(
    client: httpx.AsyncClient,
    world: World,
    device: str = DEVICE,
    key: str | None = None,
    n: int = 5,
) -> httpx.Response:
    resp = await client.post(
        f"/v1/connections/{world.connection_id}/outbox/claim",
        json={"device_install_id": device, "max_instructions": n},
        headers=headers(world.scope, key),
    )
    if resp.status_code == 200:
        assert_contract("OutboxClaimResult", resp.json())
    return resp


async def sms_world(session: AsyncSession, mode: str = "autopilot") -> World:
    """A real-platform device-side connection (SMS) with VERIFIED send for the test."""
    world = await make_world(session, platform="sms", device_side=True, mode=mode)
    return world


async def queued_device_send(
    session: AsyncSession, client: httpx.AsyncClient, world: World
) -> Message:
    """Warm up past first_message by sending manually, then let autopilot reply once."""
    resp = await post_event(client, world, world.event("hi"))
    assert resp.status_code == 202, resp.text
    conv_id = (await session.get(Message, uuid.UUID(resp.json()["message_id"]))).conversation_id  # type: ignore[union-attr]
    sent = await client.post(
        f"/v1/conversations/{conv_id}/messages", json={"text": "hey!"}, headers=headers(world.scope)
    )
    assert sent.status_code == 202, sent.text
    # The user's own message goes through the outbox too: lease and acknowledge it.
    c = (await claim(client, world)).json()["instructions"]
    assert len(c) == 1 and c[0]["authorization"] == {"origin": "user_manual", "approval_id": None}
    ack = await post_event(
        client,
        world,
        world.outcome(
            "ack", message_id=uuid.UUID(c[0]["outbound_message_id"]), key=c[0]["idempotency_key"]
        ),
    )
    assert ack.status_code == 202, ack.text
    resp = await post_event(client, world, world.event("how are you?"))
    assert resp.status_code == 202
    rows = (
        await session.scalars(
            select(Message).where(
                Message.user_id == world.scope.user_id,
                Message.direction == MessageDirection.OUTBOUND,
                Message.delivery_state == DeliveryState.QUEUED,
            )
        )
    ).all()
    assert len(rows) == 1
    return rows[0]


# --------------------------------------------------------------------------- ingest


async def test_ingest_202_then_duplicates(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="manual")
    client = make_client(world)
    raw = world.event("hello", external_message_id="ext-dup")
    first = await post_event(client, world, raw)
    assert first.status_code == 202
    assert_contract("ConnectorEventIngestResult", first.json())
    assert first.json()["outcome"] == "accepted"

    again = await post_event(client, world, raw)
    assert again.status_code == 200 and again.headers["Idempotent-Replayed"] == "true"
    assert again.json() == first.json() | {"outcome": "duplicate_event"}
    assert_contract("ConnectorEventIngestResult", again.json())

    redelivered = await post_event(
        client, world, world.event("hello", external_message_id="ext-dup")
    )
    assert redelivered.status_code == 200 and "Idempotent-Replayed" not in redelivered.headers
    assert redelivered.json()["outcome"] == "duplicate_message"
    assert redelivered.json()["message_id"] == first.json()["message_id"]

    changed = dict(raw, occurred_at="2026-11-02T14:00:00Z")
    problem(await post_event(client, world, changed), 422, "idempotency_key_mismatch")


async def test_ingest_errors(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    other = await make_world(session)
    # Another user's connection, or another user's envelope: 404, body not inspected.
    raw = world.event()
    resp = await client.post(
        f"/v1/connections/{other.connection_id}/events",
        json=raw,
        headers=headers(world.scope, raw["event_id"]),
    )
    problem(resp, 404, "not_found")
    foreign = dict(raw, user_id=str(other.scope.user_id))
    problem(await post_event(client, world, foreign), 404, "not_found")
    # Missing key; key != event_id; path mismatch; schema-invalid.
    resp = await client.post(
        f"/v1/connections/{world.connection_id}/events",
        json=raw,
        headers={"X-Test-User": str(world.scope.user_id)},
    )
    problem(resp, 400, "idempotency_key_required")
    resp = await client.post(
        f"/v1/connections/{world.connection_id}/events",
        json=raw,
        headers=headers(world.scope, "not-the-event-id"),
    )
    problem(resp, 422, "validation_error")
    body = problem(
        await post_event(client, world, dict(raw, connection_id=str(uuid.uuid4()))),
        422,
        "validation_error",
    )
    assert body["errors"][0]["pointer"] == "/connection_id"
    problem(
        await post_event(client, world, dict(raw, payload={"text": 1})), 422, "validation_error"
    )


async def test_mock_event_on_real_connection_is_422(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    raw = world.event()
    raw.update(type="mock.inbound_message_injected", platform="mock")
    raw["payload"]["mock"] = {"scenario_id": "x", "sequence": 1}
    body = problem(await post_event(client, world, raw), 422, "validation_error")
    assert body["errors"][0]["pointer"] in ("/type", "/platform")
    # Same envelope with platform "sms": the schema itself rejects mock.* off mock.
    raw["platform"] = "sms"
    body = problem(await post_event(client, world, raw), 422, "validation_error")


async def test_send_outcome_must_match_a_leased_instruction(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    queued = await queued_device_send(session, client, world)
    key = f"send:{queued.id}:1"
    # Pending (never claimed): 422 under /payload.
    body = problem(
        await post_event(client, world, world.outcome("ack", message_id=queued.id, key=key)),
        422,
        "validation_error",
    )
    assert body["errors"][0]["pointer"].startswith("/payload")
    # Unknown message id.
    problem(
        await post_event(client, world, world.outcome("ack", message_id=uuid.uuid4(), key=key)),
        422,
        "validation_error",
    )
    # Receipt without a leased instruction.
    body = problem(
        await post_event(client, world, world.outcome("receipt", message_id=queued.id)),
        422,
        "validation_error",
    )
    assert body["errors"][0]["pointer"] == "/payload/outbound_message_id"
    # Leased on another connection of the same user: still 422 here.
    other_conn = await make_world(
        session,
        platform="sms",
        device_side=True,
        scope=world.scope,
        device_install=uuid.UUID(OTHER_DEVICE),
    )
    c = (await claim(client, world)).json()["instructions"]
    assert len(c) == 1
    raw = other_conn.outcome("ack", message_id=queued.id, key=key)
    resp = await client.post(
        f"/v1/connections/{other_conn.connection_id}/events",
        json=raw,
        headers=headers(world.scope, raw["event_id"]),
    )
    problem(resp, 422, "validation_error")


# --------------------------------------------------------------------------- outbox


async def test_claim_ack_receipt_full_device_cycle(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    queued = await queued_device_send(session, client, world)
    listing = await client.get(
        f"/v1/connections/{world.connection_id}/outbox", headers=headers(world.scope)
    )
    assert listing.status_code == 200
    assert_contract("OutboxInstructionPage", listing.json())
    assert [i["outbound_message_id"] for i in listing.json()["items"]] == [str(queued.id)]
    assert "text" not in listing.json()["items"][0]

    resp = await claim(client, world)
    body = resp.json()
    [ins] = body["instructions"]
    assert ins["state"] == "leased" and ins["attempt"] == 1
    assert ins["idempotency_key"] == f"send:{queued.id}:1"
    assert ins["authorization"] == {"origin": "autopilot", "approval_id": None}
    assert ins["external_thread_id"] == "thread-1"
    lease = (
        __import__("datetime").datetime.fromisoformat(ins["lease_expires_at"])
        - __import__("datetime").datetime.fromisoformat(ins["leased_at"])
    ).total_seconds()
    assert lease == 120
    msg = await session.get(Message, queued.id)
    assert msg is not None and msg.delivery_state is DeliveryState.SENDING

    # Receipt before the ack: stored, not applied.
    early = await post_event(client, world, world.outcome("receipt", message_id=queued.id))
    assert early.status_code == 202
    msg = await session.get(Message, queued.id)
    assert (
        msg is not None and msg.delivery_state is DeliveryState.SENDING and msg.delivered_at is None
    )
    ack = await post_event(
        client, world, world.outcome("ack", message_id=queued.id, key=ins["idempotency_key"])
    )
    assert ack.status_code == 202
    assert ack.json()["message_id"] == str(queued.id)
    await session.refresh(msg)
    assert msg.delivery_state is DeliveryState.DELIVERED
    assert msg.sent_at is not None and msg.delivered_at is not None
    instruction = await session.scalar(
        select(OutboxInstruction).where(OutboxInstruction.outbound_message_id == queued.id)
    )
    assert instruction is not None and instruction.state is OutboxInstructionState.COMPLETED
    # Nothing left to claim.
    assert (await claim(client, world)).json()["instructions"] == []


async def test_claim_replay_and_own_leases(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    await queued_device_send(session, client, world)
    first = await claim(client, world, key="claim-key-0001")
    replay = await claim(client, world, key="claim-key-0001")
    assert replay.headers["Idempotent-Replayed"] == "true"
    assert replay.json() == first.json()
    # A fresh key from the same install recovers its unexpired lease (same lease).
    again = await claim(client, world, key="claim-key-0002")
    assert [i["id"] for i in again.json()["instructions"]] == [
        i["id"] for i in first.json()["instructions"]
    ]
    assert (
        again.json()["instructions"][0]["lease_expires_at"]
        == first.json()["instructions"][0]["lease_expires_at"]
    )
    # Another install may not claim at all (I-018): 422, and it recovers nothing.
    foreign = problem(await claim(client, world, device=OTHER_DEVICE), 422, "validation_error")
    assert foreign["errors"][0]["pointer"] == "/device_install_id"
    # Same key, different body: mismatch.
    resp = await client.post(
        f"/v1/connections/{world.connection_id}/outbox/claim",
        json={"device_install_id": OTHER_DEVICE},
        headers=headers(world.scope, "claim-key-0001"),
    )
    problem(resp, 422, "idempotency_key_mismatch")
    problem(
        await client.post(
            f"/v1/connections/{world.connection_id}/outbox/claim",
            json={"device_install_id": DEVICE, "max_instructions": 21},
            headers=headers(world.scope),
        ),
        422,
        "validation_error",
    )


async def test_lease_expiry_is_expired_never_sent_never_retried(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    queued = await queued_device_send(session, client, world)
    [ins] = (await claim(client, world)).json()["instructions"]
    world.clock.advance(seconds=121)
    expired = await world.services.outbox.expire(session, world.scope)
    assert [e.id for e in expired] == [uuid.UUID(ins["id"])]
    msg = await session.get(Message, queued.id)
    assert msg is not None and msg.delivery_state is DeliveryState.SENDING and msg.sent_at is None
    # Never re-leased and never retried automatically.
    assert (await claim(client, world)).json()["instructions"] == []
    problem(await claim(client, world, device=OTHER_DEVICE), 422, "validation_error")
    page = await client.get(
        f"/v1/connections/{world.connection_id}/outbox",
        params={"state": "expired"},
        headers=headers(world.scope),
    )
    assert_contract("OutboxInstructionPage", page.json())
    assert [i["id"] for i in page.json()["items"]] == [ins["id"]]
    # A late outcome is still accepted: the device may really have sent.
    late = await post_event(
        client, world, world.outcome("ack", message_id=queued.id, key=ins["idempotency_key"])
    )
    assert late.status_code == 202
    await session.refresh(msg)
    assert msg.delivery_state is DeliveryState.SENT


async def test_device_failure_retry_creates_new_instruction(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    queued = await queued_device_send(session, client, world)
    [ins] = (await claim(client, world)).json()["instructions"]
    failed = await post_event(
        client,
        world,
        world.outcome("failed", message_id=queued.id, key=ins["idempotency_key"], retryable=True),
    )
    assert failed.status_code == 202
    msg = await session.get(Message, queued.id)
    assert msg is not None and msg.delivery_state is DeliveryState.QUEUED and msg.attempt_count == 1
    assert (await claim(client, world)).json()["instructions"] == []  # backoff
    world.clock.advance(seconds=31)
    [retry] = (await claim(client, world)).json()["instructions"]
    assert retry["attempt"] == 2 and retry["idempotency_key"] == f"send:{queued.id}:2"
    assert retry["id"] != ins["id"]
    # Non-retryable failure on attempt 2: dead letter, no third instruction.
    await post_event(
        client,
        world,
        world.outcome(
            "failed",
            message_id=queued.id,
            key=retry["idempotency_key"],
            retryable=False,
            code="rejected_by_platform",
        ),
    )
    await session.refresh(msg)
    assert msg.delivery_state is DeliveryState.DEAD_LETTERED
    world.clock.advance(hours=1)
    assert (await claim(client, world)).json()["instructions"] == []


async def test_claim_recheck_cancels_after_takeover_and_unverified(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    queued = await queued_device_send(session, client, world)
    # Capability drops to UNVERIFIED: an autopilot instruction is canceled at claim.
    from tests.db.conftest import capabilities_doc

    raw = world.event()
    raw.update(
        type="connection.capabilities_changed",
        external_message_id=None,
        payload={"capabilities": capabilities_doc("sms", send="UNVERIFIED")},
    )
    assert (await post_event(client, world, raw)).status_code == 202
    assert (await claim(client, world)).json()["instructions"] == []
    msg = await session.get(Message, queued.id)
    assert msg is not None and msg.delivery_state is DeliveryState.CANCELED
    ins = await session.scalar(
        select(OutboxInstruction).where(OutboxInstruction.outbound_message_id == queued.id)
    )
    assert ins is not None and ins.state is OutboxInstructionState.CANCELED


async def test_takeover_cancels_unclaimed_instruction(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    queued = await queued_device_send(session, client, world)
    conv = await session.get(
        __import__("app.db.models", fromlist=["Conversation"]).Conversation, queued.conversation_id
    )
    resp = await client.post(
        f"/v1/contacts/{conv.contact_id}/automation/takeover",
        json={},
        headers=headers(world.scope),  # type: ignore[union-attr]
    )
    assert resp.status_code == 200, resp.text
    assert_contract("ContactAutomationState", resp.json())
    assert resp.json()["canceled_message_count"] == 1
    ins = await session.scalar(
        select(OutboxInstruction).where(OutboxInstruction.outbound_message_id == queued.id)
    )
    assert ins is not None and ins.state is OutboxInstructionState.CANCELED
    assert (await claim(client, world)).json()["instructions"] == []


async def test_backend_connection_claim_is_empty(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="autopilot")
    client = make_client(world)
    await post_event(client, world, world.event())
    assert (await claim(client, world)).json()["instructions"] == []


async def test_inbound_ingest_runs_pipeline_over_http(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    await set_mode(session, world.scope, "assisted")
    world.llm.replies.append(reply("Welcome back!"))
    client = make_client(world)
    resp = await post_event(client, world, world.event())
    assert resp.status_code == 202
    approvals = await client.get("/v1/approvals", headers=headers(world.scope))
    assert approvals.status_code == 200
    assert_contract("ApprovalPage", approvals.json())
    [item] = approvals.json()["items"]
    assert set(item["reasons"]) >= {"assisted_mode", "first_message"}
