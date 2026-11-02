"""D-030: a connection that reports ``error`` or ``disconnected`` holds its sends.

While held: the dispatcher creates no outbox instruction and calls no backend connector,
a claim leases nothing new (only the install's own unexpired leases come back, ADR 0006),
the worker leaves the connection's messages alone, nothing is canceled by the hold, and
the hold is audited (``policy_blocked`` / ``connection_unavailable``). When the connection
reports ``connected`` again, held work resumes through the normal dispatcher re-checks,
exactly once (D-006 keys), also across a restart during the hold.
"""

import asyncio
import uuid
from typing import Any

import httpx
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import (
    AgentActionType,
    ApprovalStatus,
    ConnectionStatus,
    DeliveryState,
    MessageDirection,
    MessageOrigin,
    OutboxInstructionState,
)
from app.db.models import AgentAction, Approval, Message, OutboxInstruction, PlatformConnection
from app.services.dispatcher import ConnectorRegistry, Dispatcher, DispatchOutcome
from app.services.ingest import IngestService
from app.services.outbox import OutboxService
from app.worker.jobs import dispatch_due_messages, due_message_ids
from tests.automation.conftest import DEVICE_INSTALL, World, headers, make_world
from tests.automation.test_device_routes import claim, post_event, sms_world
from tests.worker.conftest import Factory
from tests.worker.helpers import get_message, ingest, queue_manual, setup, tx


def status_event(world: World, status: str) -> dict[str, Any]:
    raw = world.event()
    raw.update(
        type="connection.status_changed",
        external_message_id=None,
        payload={"status": status, "detail": None},
    )
    return raw


async def _set_status(client: httpx.AsyncClient, world: World, status: str) -> None:
    resp = await post_event(client, world, status_event(world, status))
    assert resp.status_code == 202, resp.text


async def _approve_one(
    session: AsyncSession, client: httpx.AsyncClient, world: World, text: str
) -> uuid.UUID:
    """Inbound ``text`` -> pending approval -> approve; returns the outbound message id."""
    assert (await post_event(client, world, world.event(text))).status_code == 202
    approval = await session.scalar(
        select(Approval)
        .where(Approval.user_id == world.scope.user_id, Approval.status == ApprovalStatus.PENDING)
        .order_by(Approval.created_at.desc())
    )
    assert approval is not None
    resp = await client.post(
        f"/v1/approvals/{approval.id}/approve", json={}, headers=headers(world.scope)
    )
    assert resp.status_code == 200, resp.text
    return uuid.UUID(resp.json()["message"]["id"])


async def _instructions(session: AsyncSession, message_id: uuid.UUID) -> list[OutboxInstruction]:
    return list(
        (
            await session.scalars(
                select(OutboxInstruction)
                .where(OutboxInstruction.outbound_message_id == message_id)
                .execution_options(populate_existing=True)
            )
        ).all()
    )


async def _holds(session: AsyncSession, world: World) -> list[AgentAction]:
    return list(
        (
            await session.scalars(
                select(AgentAction).where(
                    AgentAction.user_id == world.scope.user_id,
                    AgentAction.type == AgentActionType.POLICY_BLOCKED,
                    AgentAction.reason_code == "connection_unavailable",
                )
            )
        ).all()
    )


# --------------------------------------------------------------------------- device (SMS)


async def test_status_error_stops_new_instructions_and_claims_then_recovery_resumes_once(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session, mode="assisted")
    client = make_client(world)
    # Approved before the hold: its instruction is pending (unclaimed).
    before = await _approve_one(session, client, world, "hi")
    assert [i.state for i in await _instructions(session, before)] == [
        OutboxInstructionState.PENDING
    ]

    await _set_status(client, world, "error")
    conn = await session.get(PlatformConnection, world.connection_id)
    assert conn is not None and conn.status is ConnectionStatus.ERROR
    # Claims lease nothing new; the pending instruction stays pending.
    assert (await claim(client, world)).json()["instructions"] == []
    assert [i.state for i in await _instructions(session, before)] == [
        OutboxInstructionState.PENDING
    ]

    # Approved during the hold, and the user's own send: held, no instruction, not canceled.
    during = await _approve_one(session, client, world, "are you there?")
    conv_id = (await session.get(Message, during)).conversation_id  # type: ignore[union-attr]
    mine = await client.post(
        f"/v1/conversations/{conv_id}/messages", json={"text": "mine"}, headers=headers(world.scope)
    )
    assert mine.status_code == 202, mine.text
    manual = uuid.UUID(mine.json()["id"])
    for mid in (during, manual):
        assert await _instructions(session, mid) == []
        msg = await session.get(Message, mid, populate_existing=True)
        assert msg is not None and msg.delivery_state is DeliveryState.QUEUED
    assert (await claim(client, world)).json()["instructions"] == []
    # A repeated error report does not add a second "hold started" entry.
    await _set_status(client, world, "disconnected")
    holds = await _holds(session, world)
    started = [h for h in holds if h.details.get("hold") == "started"]
    assert len(started) == 1 and started[0].details["connection_status"] == "error"
    assert started[0].details["held_queued_messages"] == 1
    per_message = {h.message_id for h in holds if h.details.get("hold") is True}
    assert per_message == {during, manual}

    # Recovery: the next claim dispatches the held messages and leases all three once.
    await _set_status(client, world, "connected")
    leased = (await claim(client, world)).json()["instructions"]
    assert sorted(uuid.UUID(i["outbound_message_id"]) for i in leased) == sorted(
        [before, during, manual]
    )
    assert all(i["attempt"] == 1 for i in leased)
    origins = sorted(i["authorization"]["origin"] for i in leased)
    assert origins == ["approved_draft", "approved_draft", "user_manual"]
    for ins in leased:
        ack = await post_event(
            client,
            world,
            world.outcome(
                "ack",
                message_id=uuid.UUID(ins["outbound_message_id"]),
                key=ins["idempotency_key"],
            ),
        )
        assert ack.status_code == 202, ack.text
    assert (await claim(client, world)).json()["instructions"] == []
    for mid in (before, during, manual):
        assert len(await _instructions(session, mid)) == 1
        msg = await session.get(Message, mid, populate_existing=True)
        assert msg is not None and msg.delivery_state is DeliveryState.SENT


async def test_existing_lease_follows_adr_0006_during_the_hold(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session, mode="assisted")
    client = make_client(world)
    mid = await _approve_one(session, client, world, "hi")
    [ins] = (await claim(client, world)).json()["instructions"]
    await _set_status(client, world, "error")
    # The install's own unexpired lease is still returned (recovery after a lost response).
    again = (await claim(client, world)).json()["instructions"]
    assert [i["id"] for i in again] == [ins["id"]]
    # Its outcome is still accepted.
    ack = await post_event(
        client, world, world.outcome("ack", message_id=mid, key=ins["idempotency_key"])
    )
    assert ack.status_code == 202
    msg = await session.get(Message, mid, populate_existing=True)
    assert msg is not None and msg.delivery_state is DeliveryState.SENT


async def test_lease_expiry_during_the_hold_is_outcome_unknown_and_never_retried(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session, mode="assisted")
    client = make_client(world)
    mid = await _approve_one(session, client, world, "hi")
    [ins] = (await claim(client, world)).json()["instructions"]
    await _set_status(client, world, "disconnected")
    world.clock.advance(seconds=121)
    expired = await world.services.outbox.expire(session, world.scope)
    assert [e.id for e in expired] == [uuid.UUID(ins["id"])]
    await _set_status(client, world, "connected")
    assert (await claim(client, world)).json()["instructions"] == []
    msg = await session.get(Message, mid, populate_existing=True)
    assert msg is not None and msg.delivery_state is DeliveryState.SENDING
    assert len(await _instructions(session, mid)) == 1


async def test_pause_semantics_still_apply_to_held_sends(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    """D-014 is unchanged: takeover still cancels a held approved send; the hold itself
    cancels nothing."""
    world = await sms_world(session, mode="assisted")
    client = make_client(world)
    await _set_status(client, world, "error")
    mid = await _approve_one(session, client, world, "hi")
    msg = await session.get(Message, mid)
    assert msg is not None and msg.delivery_state is DeliveryState.QUEUED
    conv = await session.get(
        __import__("app.db.models", fromlist=["Conversation"]).Conversation, msg.conversation_id
    )
    resp = await client.post(
        f"/v1/contacts/{conv.contact_id}/automation/takeover",  # type: ignore[union-attr]
        json={},
        headers=headers(world.scope),
    )
    assert resp.status_code == 200 and resp.json()["canceled_message_count"] == 1
    await session.refresh(msg)
    assert msg.delivery_state is DeliveryState.CANCELED


# --------------------------------------------------------------------------- backend (mock)


async def test_mock_backend_connector_holds_direct_sends_until_connected(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="assisted")
    client = make_client(world)
    assert world.connector is not None
    resp = await post_event(client, world, world.event("hi"))
    conv_id = (await session.get(Message, uuid.UUID(resp.json()["message_id"]))).conversation_id  # type: ignore[union-attr]
    await _set_status(client, world, "error")
    sent = await client.post(
        f"/v1/conversations/{conv_id}/messages", json={"text": "mine"}, headers=headers(world.scope)
    )
    assert sent.status_code == 202, sent.text
    mid = uuid.UUID(sent.json()["id"])
    assert world.connector.transport_log == []
    msg = await session.get(Message, mid)
    assert msg is not None and msg.delivery_state is DeliveryState.QUEUED
    held = await world.dispatcher.dispatch(session, world.scope, mid)
    assert held.outcome is DispatchOutcome.HELD and world.connector.transport_log == []

    await _set_status(client, world, "connected")
    results = await world.dispatcher.dispatch_due(session, world.scope)
    assert [r.outcome for r in results] == [DispatchOutcome.SENT]
    assert await world.dispatcher.dispatch_due(session, world.scope) == []
    assert [r.text for r in world.connector.transport_log] == ["mine"]
    await session.refresh(msg)
    assert msg.delivery_state in (DeliveryState.SENT, DeliveryState.DELIVERED)


# --------------------------------------------------------------------------- worker / restart


async def _ingest_status(factory: Factory, world: World, status: str) -> None:
    assert await ingest(factory, world, status_event(world, status)) is None


async def test_worker_skips_held_connections_and_resumes_on_recovery(committed: Factory) -> None:
    world = await setup(committed, mode="manual")  # mock backend connector
    assert world.connector is not None
    await ingest(committed, world, world.event("hi"))
    await _ingest_status(committed, world, "error")
    [mid] = await queue_manual(committed, world, "mine")
    async with committed() as s:
        assert await due_message_ids(s, now=world.clock(), limit=50, include_mock=True) == []
    tick = await dispatch_due_messages(
        committed, world.dispatcher, now=world.clock, limit=50, include_mock=True
    )
    assert tick.attempted == 0 and world.connector.transport_log == []

    await _ingest_status(committed, world, "connected")
    async with committed() as s:
        due = await due_message_ids(s, now=world.clock(), limit=50, include_mock=True)
    assert due == [(world.scope.user_id, mid)]
    tick = await dispatch_due_messages(
        committed, world.dispatcher, now=world.clock, limit=50, include_mock=True
    )
    assert tick.outcomes["sent"] == 1
    assert [r.text for r in world.connector.transport_log] == ["mine"]
    assert (await get_message(committed, mid)).delivery_state is DeliveryState.SENT


async def test_restart_during_the_hold_gives_no_duplicate(committed: Factory) -> None:
    """Device connection: messages held across a simulated process restart (fresh
    dispatcher, registry, ingest and outbox services); after recovery two workers and two
    claims race, and each message gets exactly one instruction, one lease and one send."""
    world = await setup(committed, mode="manual", platform="sms", device_side=True)
    await ingest(committed, world, world.event("hi"))
    await _ingest_status(committed, world, "error")
    held = await queue_manual(committed, world, "held", count=2)

    async def dispatch_now(s: AsyncSession) -> list[DispatchOutcome]:
        return [(await world.dispatcher.dispatch(s, world.scope, m)).outcome for m in held]

    assert await tx(committed, dispatch_now) == [DispatchOutcome.HELD, DispatchOutcome.HELD]

    # "Restart": new services that share nothing in memory with the old ones.
    def fresh() -> tuple[Dispatcher, OutboxService, IngestService]:
        d = Dispatcher(ConnectorRegistry(), now=world.clock)
        return d, OutboxService(d, now=world.clock), IngestService(d, now=world.clock)

    (d1, o1, i1), (d2, o2, _) = fresh(), fresh()
    tick = await dispatch_due_messages(committed, d1, now=world.clock, limit=50, include_mock=False)
    assert tick.attempted == 0

    async def recover(s: AsyncSession) -> None:
        from app.connectors.events import ConnectorEvent
        from app.db.repositories import ConnectionRepository

        raw = status_event(world, "connected")
        conn = await ConnectionRepository(s, world.scope).require(world.connection_id)
        await i1.ingest(
            s,
            world.scope,
            conn,
            ConnectorEvent.model_validate(raw),
            request_hash=i1.request_hash(raw),
        )

    await tx(committed, recover)
    await asyncio.gather(
        dispatch_due_messages(committed, d1, now=world.clock, limit=50, include_mock=False),
        dispatch_due_messages(committed, d2, now=world.clock, limit=50, include_mock=False),
    )

    async def claim_with(o: OutboxService) -> list[uuid.UUID]:
        async def go(s: AsyncSession) -> list[uuid.UUID]:
            got, _ = await o.claim(
                s,
                world.scope,
                world.connection_id,
                device_install_id=DEVICE_INSTALL,
                max_instructions=10,
            )
            return [c.instruction.id for c in got]

        return await tx(committed, go)

    first = await claim_with(o1)
    second = await claim_with(o2)  # own unexpired leases come back, nothing new
    assert len(first) == 2 and sorted(second) == sorted(first)
    async with committed() as s:
        rows = (
            await s.scalars(
                select(OutboxInstruction).where(OutboxInstruction.outbound_message_id.in_(held))
            )
        ).all()
        attempted = await s.scalar(
            select(func.count())
            .select_from(AgentAction)
            .where(
                AgentAction.user_id == world.scope.user_id,
                AgentAction.type == AgentActionType.SEND_ATTEMPTED,
                AgentAction.message_id.in_(held),
            )
        )
        outbound = await s.scalar(
            select(func.count())
            .select_from(Message)
            .where(
                Message.user_id == world.scope.user_id,
                Message.direction == MessageDirection.OUTBOUND,
                Message.origin == MessageOrigin.USER_MANUAL,
            )
        )
    assert sorted(r.outbound_message_id for r in rows) == sorted(held)
    assert all(r.attempt == 1 and r.state is OutboxInstructionState.LEASED for r in rows)
    assert attempted == 2 and outbound == 2
