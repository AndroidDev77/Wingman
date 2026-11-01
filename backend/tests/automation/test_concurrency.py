"""Concurrency with separate DB sessions on a committing scratch database: duplicate
events, repeated dispatch, double approve, two devices claiming at once. Each case runs
real transactions in parallel (``asyncio.gather``) and checks that exactly one send or
lease happened."""

import asyncio
import uuid
from collections.abc import Awaitable, Callable
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.exc import DBAPIError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.connectors.events import ConnectorEvent
from app.db.enums import (
    Actor,
    DeliveryState,
    MessageDirection,
    MessageOrigin,
    OutboxInstructionState,
)
from app.db.models import Approval, Message, OutboxInstruction
from app.db.repositories import ConnectionRepository, ConversationRepository
from app.services.automation import ApprovalNotPending
from app.services.ingest import IngestOutcome
from tests.automation.conftest import World, make_world, set_mode

Factory = async_sessionmaker[AsyncSession]


async def tx[T](factory: Factory, fn: Callable[[AsyncSession], Awaitable[T]]) -> T:
    """Run ``fn`` in its own committed transaction (retry once on a deadlock/serialization)."""
    for attempt in range(3):
        async with factory() as session:
            try:
                async with session.begin():
                    return await fn(session)
            except DBAPIError as exc:  # pragma: no cover - only under heavy contention
                if attempt == 2 or "deadlock" not in str(exc).lower():
                    raise
    raise AssertionError("unreachable")


async def setup(factory: Factory, mode: str = "autopilot", **kw: Any) -> World:
    async def go(s: AsyncSession) -> World:
        world = await make_world(s, **kw)
        await set_mode(s, world.scope, mode)
        return world

    return await tx(factory, go)


async def ingest_and_run(factory: Factory, world: World, raw: dict[str, Any]) -> IngestOutcome:
    async def step(s: AsyncSession) -> tuple[IngestOutcome, uuid.UUID | None]:
        connection = await ConnectionRepository(s, world.scope).require(world.connection_id)
        r = await world.services.ingest.ingest(
            s,
            world.scope,
            connection,
            ConnectorEvent.model_validate(raw),
            request_hash=world.services.ingest.request_hash(raw),
        )
        return r.outcome, r.new_inbound_message_id

    outcome, message_id = await tx(factory, step)
    if message_id is not None:
        await tx(
            factory, lambda s: world.controller.handle_inbound_message(s, world.scope, message_id)
        )
    return outcome


async def count(factory: Factory, stmt: Any) -> int:
    async with factory() as s:
        return int(await s.scalar(stmt) or 0)


async def warm(factory: Factory, world: World) -> None:
    """Get the conversation past first_message (approve the first reply)."""
    await ingest_and_run(factory, world, world.event("hi", external_message_id="warm"))

    async def approve(s: AsyncSession) -> uuid.UUID:
        approval = await s.scalar(select(Approval).where(Approval.user_id == world.scope.user_id))
        assert approval is not None
        _, m = await world.controller.approve(s, world.scope, approval.id, edited_text=None)
        await world.dispatcher.dispatch(s, world.scope, m.id)
        return m.id

    await tx(factory, approve)


def outbound(world: World) -> Any:
    return (
        select(func.count())
        .select_from(Message)
        .where(
            Message.user_id == world.scope.user_id, Message.direction == MessageDirection.OUTBOUND
        )
    )


async def test_concurrent_duplicate_events_send_once(committed: Factory) -> None:
    world = await setup(committed)
    await warm(committed, world)
    assert world.connector is not None
    before = len(world.connector.transport_log)
    raw = world.event("Back from Lisbon!", external_message_id="same-msg")
    # Same envelope x5 plus the same message under 5 new event_ids, all at once.
    events = [raw] * 5 + [
        world.event("Back from Lisbon!", external_message_id="same-msg") for _ in range(5)
    ]
    outcomes = await asyncio.gather(*(ingest_and_run(committed, world, e) for e in events))
    assert outcomes.count(IngestOutcome.ACCEPTED) == 1
    assert len(world.connector.transport_log) == before + 1
    assert await count(committed, outbound(world)) == 2  # warm-up + exactly one reply


async def test_sequential_duplicate_events_send_once(committed: Factory) -> None:
    world = await setup(committed)
    await warm(committed, world)
    assert world.connector is not None
    raw = world.event("again", external_message_id="seq-msg")
    for _ in range(3):
        await ingest_and_run(committed, world, raw)
        await ingest_and_run(committed, world, world.event("again", external_message_id="seq-msg"))
    assert await count(committed, outbound(world)) == 2


async def test_concurrent_dispatch_of_one_message_sends_once(committed: Factory) -> None:
    world = await setup(committed, mode="manual")
    await ingest_and_run(committed, world, world.event("hi", external_message_id="d1"))

    async def queue(s: AsyncSession) -> uuid.UUID:
        inbound = await s.scalar(select(Message).where(Message.user_id == world.scope.user_id))
        assert inbound is not None
        conv = await ConversationRepository(s, world.scope).require(inbound.conversation_id)
        m = await world.dispatcher.queue(
            s,
            world.scope,
            conversation=conv,
            origin=MessageOrigin.USER_MANUAL,
            text="once",
            actor=Actor.USER,
        )
        return m.id

    message_id = await tx(committed, queue)
    results = await asyncio.gather(
        *(
            tx(committed, lambda s: world.dispatcher.dispatch(s, world.scope, message_id))
            for _ in range(8)
        )
    )
    assert sorted(r.outcome.value for r in results).count("sent") == 1
    assert world.connector is not None
    assert [r.text for r in world.connector.transport_log] == ["once"]


async def test_concurrent_approve_sends_once(committed: Factory) -> None:
    world = await setup(committed, mode="assisted")
    await ingest_and_run(committed, world, world.event("hi"))
    async with committed() as s:
        approval = await s.scalar(select(Approval).where(Approval.user_id == world.scope.user_id))
    assert approval is not None

    async def approve(s: AsyncSession) -> str:
        try:
            _, m = await world.controller.approve(s, world.scope, approval.id, edited_text=None)
        except ApprovalNotPending:
            return "409"
        await world.dispatcher.dispatch(s, world.scope, m.id)
        return "ok"

    results = await asyncio.gather(*(tx(committed, approve) for _ in range(6)))
    assert results.count("ok") == 1 and results.count("409") == 5
    assert world.connector is not None and len(world.connector.transport_log) == 1


async def test_two_devices_claiming_get_one_lease(committed: Factory) -> None:
    world = await setup(committed, mode="manual", platform="sms", device_side=True)
    await ingest_and_run(committed, world, world.event("hi"))

    async def queue(s: AsyncSession) -> list[uuid.UUID]:
        inbound = await s.scalar(select(Message).where(Message.user_id == world.scope.user_id))
        assert inbound is not None
        conv = await ConversationRepository(s, world.scope).require(inbound.conversation_id)
        ids = []
        for i in range(3):
            m = await world.dispatcher.queue(
                s,
                world.scope,
                conversation=conv,
                origin=MessageOrigin.USER_MANUAL,
                text=f"m{i}",
                actor=Actor.USER,
            )
            await world.dispatcher.dispatch(s, world.scope, m.id)
            ids.append(m.id)
        return ids

    ids = await tx(committed, queue)
    devices = [uuid.uuid4() for _ in range(6)]

    async def claim(device: uuid.UUID) -> list[uuid.UUID]:
        async def go(s: AsyncSession) -> list[uuid.UUID]:
            claimed, _ = await world.services.outbox.claim(
                s, world.scope, world.connection_id, device_install_id=device, max_instructions=3
            )
            return [c.instruction.id for c in claimed]

        return await tx(committed, go)

    results = await asyncio.gather(*(claim(d) for d in devices))
    leased = [i for r in results for i in r]
    assert len(leased) == 3 and len(set(leased)) == 3  # every instruction leased exactly once
    async with committed() as s:
        rows = (
            await s.scalars(
                select(OutboxInstruction).where(OutboxInstruction.user_id == world.scope.user_id)
            )
        ).all()
        assert {r.state for r in rows} == {OutboxInstructionState.LEASED}
        assert len({r.device_install_id for r in rows if r.device_install_id}) >= 1
        msgs = (await s.scalars(select(Message).where(Message.id.in_(ids)))).all()
        assert {m.delivery_state for m in msgs} == {DeliveryState.SENDING}


async def test_takeover_racing_autopilot_never_sends_after_takeover(committed: Factory) -> None:
    """A takeover and an inbound message race. Whatever the interleaving, nothing may be
    transmitted after the takeover committed, and no approval stays pending."""
    world = await setup(committed, mode="autopilot")
    await warm(committed, world)
    assert world.connector is not None
    async with committed() as s:
        approval = await s.scalar(select(Approval).where(Approval.user_id == world.scope.user_id))
    assert approval is not None
    contact_id = approval.contact_id
    from app.db.enums import ContactAutomationStatus

    takeover_done: list[int] = []

    async def takeover(s: AsyncSession) -> None:
        await world.controller.set_contact_status(
            s, world.scope, contact_id, ContactAutomationStatus.TAKEN_OVER
        )

    async def do_takeover() -> None:
        await tx(committed, takeover)
        takeover_done.append(len(world.connector.transport_log))  # type: ignore[union-attr]

    await asyncio.gather(
        ingest_and_run(committed, world, world.event("race")),
        do_takeover(),
    )
    after = takeover_done[0]
    # Later inbound messages never produce a send.
    await ingest_and_run(committed, world, world.event("after"))
    assert len(world.connector.transport_log) == after
    async with committed() as s:
        pending = await s.scalar(
            select(func.count())
            .select_from(Approval)
            .where(Approval.user_id == world.scope.user_id, Approval.status == "pending")
        )
        queued = await s.scalar(
            select(func.count())
            .select_from(Message)
            .where(
                Message.user_id == world.scope.user_id,
                Message.delivery_state == DeliveryState.QUEUED,
            )
        )
    assert pending == 0 and queued == 0
