"""Two workers at once never double-process: exactly one transport attempt per key, one
instruction per attempt, one expiry per approval. Checked both with the advisory locks
and without them (the services' row locks alone must already be enough)."""

import asyncio
import uuid
from collections import Counter

import pytest
from sqlalchemy import func, select

from app.connectors.mock import MockConnector
from app.db.enums import AgentActionType, DeliveryState, OutboxInstructionState
from app.db.models import AgentAction, Message, OutboxInstruction
from app.services.dispatcher import Dispatcher, send_key
from app.services.dispatcher import RetryPolicy as SendRetry
from app.worker.locks import advisory_key, advisory_lock
from app.worker.runner import Job, TickResult, Worker
from tests.worker.conftest import Factory, worker_for
from tests.worker.helpers import ingest, queue_manual, run_pipeline, setup


def two_workers(committed: Factory, world, *, advisory_locks: bool) -> list[Worker]:  # type: ignore[no-untyped-def]
    """Two replicas: separate Dispatcher objects (as in two processes) over the same
    transport (the registry holding the world's simulator, standing in for the real
    platform both replicas would reach)."""
    return [
        worker_for(
            committed,
            world,
            advisory_locks=advisory_locks,
            dispatcher=Dispatcher(world.registry, retry=SendRetry(max_attempts=3), now=world.clock),
        )
        for _ in range(2)
    ]


@pytest.mark.parametrize("advisory_locks", [False, True])
async def test_two_workers_send_each_message_exactly_once(
    committed: Factory, advisory_locks: bool
) -> None:
    world = await setup(committed)
    await ingest(committed, world, world.event("hi"))
    assert isinstance(world.connector, MockConnector)
    ids = await queue_manual(committed, world, "hello", count=12)
    workers = two_workers(committed, world, advisory_locks=advisory_locks)

    for _ in range(3):
        await asyncio.gather(*(w.run_once() for w in workers))

    keys = Counter(r.idempotency_key for r in world.connector.transport_log)
    assert keys == Counter({send_key(i, 1): 1 for i in ids})
    async with committed() as s:
        states = (await s.scalars(select(Message.delivery_state).where(Message.id.in_(ids)))).all()
        acks = await s.scalar(
            select(func.count())
            .select_from(AgentAction)
            .where(AgentAction.type == AgentActionType.SEND_ACKNOWLEDGED)
        )
    assert set(states) == {DeliveryState.SENT}
    assert acks == len(ids)


async def test_two_workers_retry_once_with_one_new_attempt(committed: Factory) -> None:
    world = await setup(committed)
    await ingest(committed, world, world.event("hi"))
    assert isinstance(world.connector, MockConnector)
    [message_id] = await queue_manual(committed, world, "retry me")
    world.connector.inject_send_failures(1, retryable=True)
    workers = two_workers(committed, world, advisory_locks=False)

    await asyncio.gather(*(w.run_once() for w in workers))
    world.clock.advance(seconds=30)
    for _ in range(2):
        await asyncio.gather(*(w.run_once() for w in workers))

    keys = [r.idempotency_key for r in world.connector.transport_log]
    assert keys == [send_key(message_id, 1), send_key(message_id, 2)]
    async with committed() as s:
        m = await s.get(Message, message_id)
    assert m is not None and m.delivery_state is DeliveryState.SENT and m.attempt_count == 2


async def test_two_workers_create_one_device_instruction(committed: Factory) -> None:
    world = await setup(committed, platform="sms", device_side=True)
    await ingest(committed, world, world.event("hi"))
    ids = await queue_manual(committed, world, "sms", count=5)
    workers = two_workers(committed, world, advisory_locks=False)
    for _ in range(3):
        await asyncio.gather(*(w.run_once() for w in workers))
    async with committed() as s:
        rows = (await s.scalars(select(OutboxInstruction))).all()
    assert sorted((r.outbound_message_id, r.attempt) for r in rows) == sorted((i, 1) for i in ids)
    assert {r.state for r in rows} == {OutboxInstructionState.PENDING}


async def test_two_workers_expire_an_approval_once(committed: Factory) -> None:
    world = await setup(committed, mode="assisted")
    inbound_id = await ingest(committed, world, world.event("hi"))
    assert inbound_id is not None
    await run_pipeline(committed, world, inbound_id)
    world.clock.advance(hours=25)
    workers = two_workers(committed, world, advisory_locks=False)
    await asyncio.gather(*(w.run_once() for w in workers))
    async with committed() as s:
        expired = await s.scalar(
            select(func.count())
            .select_from(AgentAction)
            .where(AgentAction.type == AgentActionType.APPROVAL_EXPIRED)
        )
    assert expired == 1


async def test_advisory_lock_lets_one_replica_run_a_job_at_a_time(committed: Factory) -> None:
    engine = committed.kw["bind"]
    running = 0
    peak = 0
    ran: list[int] = []
    gate = asyncio.Event()
    entered = asyncio.Event()

    def job(n: int) -> Job:
        async def tick(_: object) -> TickResult:
            nonlocal running, peak
            running += 1
            peak = max(peak, running)
            ran.append(n)
            entered.set()
            await gate.wait()
            running -= 1
            return TickResult({})

        return Job("exclusive", 1.0, tick)  # type: ignore[arg-type]

    a = Worker([job(1)], engine=engine)
    b = Worker([job(2)], engine=engine)
    first = asyncio.create_task(a.run_once())
    await asyncio.wait_for(entered.wait(), timeout=5)
    await b.run_once()  # lock held by ``a``: skipped, returns immediately
    gate.set()
    await first
    assert peak == 1 and ran == [1]
    assert b.state["exclusive"].skipped == 1 and a.state["exclusive"].ticks == 1

    # Released afterwards: the other replica gets it on its next tick.
    await b.run_once()
    assert ran == [1, 2]


async def test_advisory_lock_is_released_when_the_tick_raises(committed: Factory) -> None:
    engine = committed.kw["bind"]
    key = advisory_key(f"t-{uuid.uuid4()}")
    with pytest.raises(RuntimeError):
        async with advisory_lock(engine, key) as held:
            assert held
            raise RuntimeError("boom")
    async with advisory_lock(engine, key) as held:
        assert held
