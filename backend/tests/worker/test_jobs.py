"""The worker's jobs end to end on a committing Postgres database: reply delays, retry
backoff, lease expiry, approval expiry and the mock-platform default (I-015, I-016)."""

from datetime import timedelta

from sqlalchemy import select

from app.connectors.mock import MockConnector
from app.db.enums import (
    AgentActionType,
    ApprovalStatus,
    DeliveryState,
    OutboxInstructionState,
)
from app.db.models import AgentAction, Approval, OutboxInstruction
from app.services.dispatcher import send_key
from app.worker.config import WorkerSettings
from app.worker.jobs import due_message_ids
from app.worker.runner import Worker, build_jobs
from tests.worker.conftest import Factory, worker_for
from tests.worker.helpers import (
    get_message,
    ingest,
    queue_manual,
    run_pipeline,
    setup,
    tx,
    warm,
)


async def test_delayed_reply_is_sent_only_after_send_after(committed: Factory) -> None:
    world = await setup(committed, mode="autopilot", prefs={"reply_delay": {"min": 60, "max": 60}})
    await warm(committed, world)
    assert isinstance(world.connector, MockConnector)
    before = len(world.connector.transport_log)

    inbound_id = await ingest(committed, world, world.event("Back from Lisbon!"))
    assert inbound_id is not None
    result = await run_pipeline(committed, world, inbound_id)
    message_id = result.outbound_message_id
    assert message_id is not None
    queued = await get_message(committed, message_id)
    assert queued.delivery_state is DeliveryState.QUEUED
    assert queued.send_after == world.clock.now + timedelta(seconds=60)

    worker = worker_for(committed, world)
    await worker.run_once()
    world.clock.advance(seconds=59)
    await worker.run_once()
    assert len(world.connector.transport_log) == before  # not due yet: nothing sent
    assert (await get_message(committed, message_id)).delivery_state is DeliveryState.QUEUED

    world.clock.advance(seconds=1)
    await worker.run_once()
    sent = await get_message(committed, message_id)
    assert sent.delivery_state is DeliveryState.SENT
    assert [r.idempotency_key for r in world.connector.transport_log[before:]] == [
        send_key(message_id, 1)
    ]
    await worker.run_once()  # idempotent: a later tick sends nothing more
    assert len(world.connector.transport_log) == before + 1


async def test_retryable_failure_is_retried_as_a_new_attempt_after_backoff(
    committed: Factory,
) -> None:
    world = await setup(committed)
    await ingest(committed, world, world.event("hi"))
    assert isinstance(world.connector, MockConnector)
    [message_id] = await queue_manual(committed, world, "see you there")
    world.connector.inject_send_failures(1, retryable=True)

    worker = worker_for(committed, world)
    await worker.run_once()
    m = await get_message(committed, message_id)
    assert m.delivery_state is DeliveryState.QUEUED and m.attempt_count == 1
    assert m.send_after == world.clock.now + timedelta(seconds=30)  # RetryPolicy base backoff

    world.clock.advance(seconds=29)
    await worker.run_once()
    assert len(world.connector.transport_log) == 1  # still backing off

    world.clock.advance(seconds=1)
    await worker.run_once()
    m = await get_message(committed, message_id)
    assert m.delivery_state is DeliveryState.SENT and m.attempt_count == 2
    keys = [r.idempotency_key for r in world.connector.transport_log]
    assert keys == [send_key(message_id, 1), send_key(message_id, 2)]


async def test_non_retryable_failure_is_dead_lettered_and_not_retried(committed: Factory) -> None:
    world = await setup(committed)
    await ingest(committed, world, world.event("hi"))
    assert isinstance(world.connector, MockConnector)
    [message_id] = await queue_manual(committed, world, "nope")
    world.connector.inject_send_failures(1, retryable=False)
    worker = worker_for(committed, world)
    await worker.run_once()
    world.clock.advance(hours=1)
    await worker.run_once()
    assert (await get_message(committed, message_id)).delivery_state is (
        DeliveryState.DEAD_LETTERED
    )
    assert len(world.connector.transport_log) == 1


async def test_expired_lease_becomes_expired_and_is_never_retried(committed: Factory) -> None:
    world = await setup(committed, platform="sms", device_side=True)
    await ingest(committed, world, world.event("hi"))
    [message_id] = await queue_manual(committed, world, "on my way")
    worker = worker_for(committed, world)

    # The worker's dispatch creates the device instruction (attempt 1); a device leases it.
    await worker.run_once()
    device = __import__("uuid").uuid4()
    claimed, _ = await tx(
        committed,
        lambda s: world.services.outbox.claim(
            s, world.scope, world.connection_id, device_install_id=device, max_instructions=5
        ),
    )
    assert len(claimed) == 1

    world.clock.advance(seconds=119)
    await worker.run_once()
    async with committed() as s:
        [row] = (await s.scalars(select(OutboxInstruction))).all()
        assert row.state is OutboxInstructionState.LEASED

    world.clock.advance(seconds=1)
    for _ in range(3):
        await worker.run_once()
        world.clock.advance(minutes=10)
    async with committed() as s:
        rows = (await s.scalars(select(OutboxInstruction))).all()
    assert [(r.state, r.attempt) for r in rows] == [(OutboxInstructionState.EXPIRED, 1)]
    m = await get_message(committed, message_id)
    assert m.delivery_state is DeliveryState.SENDING  # outcome unknown: never sent, no retry
    assert m.sent_at is None


async def test_stale_approval_expires(committed: Factory) -> None:
    world = await setup(committed, mode="assisted")
    inbound_id = await ingest(committed, world, world.event("hi"))
    assert inbound_id is not None
    result = await run_pipeline(committed, world, inbound_id)
    assert result.approval_id is not None

    worker = worker_for(committed, world)
    world.clock.advance(hours=23)
    await worker.run_once()
    async with committed() as s:
        approval = await s.get(Approval, result.approval_id)
        assert approval is not None and approval.status is ApprovalStatus.PENDING

    world.clock.advance(hours=1)
    await worker.run_once()
    await worker.run_once()  # a second run expires nothing twice
    async with committed() as s:
        approval = await s.get(Approval, result.approval_id)
        assert approval is not None and approval.status is ApprovalStatus.EXPIRED
        audits = (
            await s.scalars(
                select(AgentAction).where(AgentAction.type == AgentActionType.APPROVAL_EXPIRED)
            )
        ).all()
    assert [a.approval_id for a in audits] == [result.approval_id]


async def test_separate_worker_process_skips_mock_messages_by_default(committed: Factory) -> None:
    """I-016: a standalone worker has its own, empty simulator. By default it leaves mock
    messages alone instead of dead-lettering them as "unknown mock thread"."""
    world = await setup(committed)
    await ingest(committed, world, world.event("hi"))
    [message_id] = await queue_manual(committed, world, "hello")
    async with committed() as s:
        assert await due_message_ids(s, now=world.clock.now, limit=10, include_mock=False) == []
        assert await due_message_ids(s, now=world.clock.now, limit=10, include_mock=True) == [
            (world.scope.user_id, message_id)
        ]
    standalone = Worker(
        build_jobs(WorkerSettings(), committed, now=world.clock),
        engine=committed.kw["bind"],
        advisory_locks=False,
    )
    await standalone.run_once()
    assert (await get_message(committed, message_id)).delivery_state is DeliveryState.QUEUED


async def test_messages_waiting_for_a_device_do_not_fill_the_batch(committed: Factory) -> None:
    world = await setup(committed, platform="sms", device_side=True)
    await ingest(committed, world, world.event("hi"))
    first = await queue_manual(committed, world, "a", count=3)
    worker = worker_for(committed, world, dispatch_batch=3)
    await worker.run_once()  # three instructions, nobody claims them
    later = await queue_manual(committed, world, "b", count=2)
    await worker.run_once()
    async with committed() as s:
        rows = (await s.scalars(select(OutboxInstruction))).all()
    assert {r.outbound_message_id for r in rows} == set(first + later)
    assert all(r.state is OutboxInstructionState.PENDING for r in rows)
