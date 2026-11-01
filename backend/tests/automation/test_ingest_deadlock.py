"""I-028: concurrent duplicate ingest must never deadlock.

The CI deadlock (PR #36, E2E X1) was a cycle between two transactions of one user:

* the post-commit **pipeline** of an accepted message: ``set_analysis`` updates the
  conversation row, then ``decide`` locks the user row;
* a concurrent **duplicate ingest**: inserting its ``connector_event_ingests`` record
  runs the FK check on ``users``, which takes ``FOR KEY SHARE`` on the user row; then
  ``INSERT INTO conversations ... ON CONFLICT DO NOTHING`` waits for the pipeline's
  uncommitted update of the same conversation tuple.

``SELECT ... FOR UPDATE`` conflicts with ``FOR KEY SHARE``, so the pipeline then waited
for the ingest: a deadlock. Row locks taken only to change non-key columns are now
``FOR NO KEY UPDATE`` (what Postgres's own ``UPDATE`` takes), which does not conflict
with FK checks, while decisions still serialize with each other and with ``FOR SHARE``.

Both tests use separate committing sessions on a real Postgres scratch database and do
NOT retry on deadlock (unlike ``test_concurrency.tx``), so a deadlock fails them.
"""

import asyncio
import json
import random
import uuid
from collections.abc import Awaitable, Callable
from typing import Any

from sqlalchemy import func, select, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.ai.providers.mock import MockReply
from app.connectors.events import ConnectorEvent
from app.db.enums import MessageDirection, MessageOrigin
from app.db.models import Conversation, Draft, Message
from app.db.repositories import ConnectionRepository, ConversationRepository, UserRepository
from app.services.ingest import IngestOutcome
from tests.automation.conftest import ScriptedLLM, World, reply
from tests.automation.test_concurrency import setup, warm

Factory = async_sessionmaker[AsyncSession]

ROUNDS = 10
SAME_EVENT = 12  # the same envelope, 12 times
SAME_MESSAGE = 12  # the same external_message_id under 12 different event_ids


async def once[T](factory: Factory, fn: Callable[[AsyncSession], Awaitable[T]]) -> T:
    """One committed transaction, no retry: a deadlock propagates."""
    async with factory() as session, session.begin():
        return await fn(session)


async def ingest(factory: Factory, world: World, raw: dict[str, Any]) -> Any:
    async def step(s: AsyncSession) -> Any:
        connection = await ConnectionRepository(s, world.scope).require(world.connection_id)
        return await world.services.ingest.ingest(
            s,
            world.scope,
            connection,
            ConnectorEvent.model_validate(raw),
            request_hash=world.services.ingest.request_hash(raw),
        )

    return await once(factory, step)


async def upload(
    factory: Factory, world: World, raw: dict[str, Any], jitter: float = 0.0
) -> IngestOutcome:
    """What ``ingestConnectorEvent`` does: commit the ingest, then run the pipeline in
    its own transaction and dispatch an autopilot send in a further one."""
    await asyncio.sleep(jitter)
    result = await ingest(factory, world, raw)
    if result.new_inbound_message_id is not None:
        message_id = result.new_inbound_message_id
        pipeline = await once(
            factory,
            lambda s: world.controller.handle_inbound_message(
                s, world.scope, message_id, defer_dispatch=True
            ),
        )
        if pipeline.outbound_message_id is not None:
            out_id = pipeline.outbound_message_id
            await once(factory, lambda s: world.dispatcher.dispatch(s, world.scope, out_id))
    outcome: IngestOutcome = result.outcome
    return outcome


async def test_concurrent_duplicate_ingest_never_deadlocks(committed: Factory) -> None:
    """N=24 concurrent uploads per round (12x one envelope + 12x one message under new
    event IDs), each followed by the pipeline, for ``ROUNDS`` rounds. The reply model
    answers after a short delay (a real one takes seconds), so a pipeline holds its
    conversation update while other uploads of the round are still ingesting, and the
    uploads start with a little jitter, as HTTP requests do."""
    llm = ScriptedLLM(default_reply=MockReply(json.dumps(reply()), delay_s=0.05))
    world = await setup(committed, llm=llm)
    rng = random.Random(28)  # noqa: S311 - test jitter
    await warm(committed, world)
    assert world.connector is not None
    sent_before = len(world.connector.transport_log)
    failures: list[str] = []
    for n in range(ROUNDS):
        same = world.event(f"round {n} A", external_message_id=f"r{n}-a")
        ext = f"r{n}-b"
        uploads = [same] * SAME_EVENT + [
            world.event(f"round {n} B", external_message_id=ext) for _ in range(SAME_MESSAGE)
        ]
        results = await asyncio.gather(
            *(upload(committed, world, e, rng.uniform(0, 0.25)) for e in uploads),
            return_exceptions=True,
        )
        failures += [f"round {n}: {_describe(r)}" for r in results if isinstance(r, Exception)]
        outcomes = [r for r in results if isinstance(r, IngestOutcome)]
        if len(outcomes) == len(uploads):
            a, b = outcomes[:SAME_EVENT], outcomes[SAME_EVENT:]
            assert sorted(a) == sorted(
                [IngestOutcome.ACCEPTED] + [IngestOutcome.DUPLICATE_EVENT] * (SAME_EVENT - 1)
            ), (n, a)
            assert sorted(b) == sorted(
                [IngestOutcome.ACCEPTED] + [IngestOutcome.DUPLICATE_MESSAGE] * (SAME_MESSAGE - 1)
            ), (n, b)
    assert failures == [], f"{len(failures)} of {ROUNDS * 24} uploads failed: {failures}"

    async with committed() as s:
        uid = world.scope.user_id
        inbound = await s.scalar(
            select(func.count())
            .select_from(Message)
            .where(Message.user_id == uid, Message.direction == MessageDirection.INBOUND)
        )
        assert inbound == 1 + 2 * ROUNDS  # warm-up + one per distinct message
        per_trigger = (
            await s.execute(
                select(Draft.trigger_message_id, func.count())
                .where(Draft.user_id == uid)
                .group_by(Draft.trigger_message_id)
                .having(func.count() > 1)
            )
        ).all()
        assert per_trigger == []  # at most one draft per inbound message
        autopilot = (
            await s.scalars(
                select(Message.id).where(
                    Message.user_id == uid, Message.origin == MessageOrigin.AUTOPILOT
                )
            )
        ).all()
    log = world.connector.transport_log[sent_before:]
    keys = [r.idempotency_key for r in log]
    assert len(keys) == len(set(keys))  # zero duplicate transport attempts
    assert len(log) <= len(autopilot)  # every transmission is one stored autopilot message


async def test_pipeline_lock_after_conversation_update_does_not_deadlock_with_ingest(
    committed: Factory,
) -> None:
    """The exact CI interleaving, forced: the pipeline has updated the conversation
    (uncommitted); another upload for the same thread that has not seen the message yet
    (in CI: a duplicate that read before the winner committed; here, deterministically,
    the next message of the thread) blocks in ``INSERT INTO conversations ... ON
    CONFLICT`` on that tuple while holding its FK ``KEY SHARE`` lock on the user; the
    pipeline then locks the user."""
    world = await setup(committed, mode="manual")
    first = world.event("hi", external_message_id="x-1")
    assert await ingest(committed, world, first) is not None
    async with committed() as s:
        conv_id = await s.scalar(
            select(Conversation.id).where(Conversation.user_id == world.scope.user_id)
        )
    assert conv_id is not None

    async with committed() as pipeline, pipeline.begin():
        # Pipeline step 1 (analysis): update the conversation row.
        await ConversationRepository(pipeline, world.scope).set_analysis(
            conv_id, {"stage": "rapport"}, (await _stage(pipeline, conv_id))
        )
        dup = asyncio.create_task(
            ingest(committed, world, world.event("hi again", external_message_id="x-2"))
        )
        await _until_blocked(committed, 1)
        # Pipeline step 2 (decide): lock the user. Before the fix: deadlock detected.
        assert await UserRepository(pipeline).lock(world.scope) is not None
    result = await asyncio.wait_for(dup, 10)
    assert result.outcome is IngestOutcome.ACCEPTED


async def _stage(session: AsyncSession, conversation_id: uuid.UUID) -> Any:
    return await session.scalar(
        select(Conversation.stage).where(Conversation.id == conversation_id)
    )


async def _until_blocked(factory: Factory, n: int) -> None:
    """Wait until ``n`` backends of this database wait on a lock (bounded)."""
    for _ in range(200):
        async with factory() as s:
            waiting = await s.scalar(
                text(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                    " AND wait_event_type = 'Lock'"
                )
            )
        if (waiting or 0) >= n:
            return
        await asyncio.sleep(0.02)
    raise AssertionError("the duplicate ingest never blocked on the conversation row")


def _describe(exc: BaseException) -> str:
    """Exception class plus SQLSTATE (``40P01`` is a deadlock)."""
    orig = getattr(exc, "orig", None)
    state = getattr(orig, "sqlstate", None) or getattr(orig, "pgcode", None)
    cause = getattr(orig, "__cause__", None)
    state = state or getattr(cause, "sqlstate", None)
    return f"{type(exc).__name__}({state})"
