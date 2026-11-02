"""The three periodic jobs from WING-011 (I-015). Each one calls the existing service
functions; nothing here sends, leases or decides anything on its own.

* :func:`dispatch_due_messages`: queued outbound messages whose ``send_after`` has
  passed (reply delays, retry backoff) go through ``Dispatcher.dispatch``, **one
  message per transaction**, so a failure on one message never rolls back the recorded
  outcome of another and every transport call happens after the ``queued`` row was
  committed (D-006, PR #24). Messages of a connection that reports ``error`` or
  ``disconnected`` are left out (held, D-030) and picked up again once it is
  ``connected``.
* :func:`expire_leases`: ``app.services.outbox.expire_lapsed_leases`` (lapsed device
  leases -> ``expired``; outcome unknown, never retried).
* :func:`expire_due_approvals`: ``AutomationController.expire_approvals`` per user with
  a pending approval past ``expires_at``, one transaction per user.

Concurrency (two or more worker replicas, or a worker racing the API):

* dispatch: ``Dispatcher.dispatch`` locks the message row ``FOR UPDATE`` and only acts
  on ``queued``; a backend transport call happens while that lock is held, so a second
  dispatcher waits and then sees ``sent``/``queued with a later send_after``/``failed``
  and does nothing. For a device connection it creates the attempt's instruction once
  (a second dispatcher sees the live instruction; ``(outbound_message_id, attempt)`` is
  unique as a backstop). Retries use a new per-attempt key; a replay of the same
  attempt uses the same key, which the connector deduplicates (D-006).
* lease and approval expiry are single guarded ``UPDATE ... WHERE state = ...``
  statements: a concurrent run blocks on the row lock, re-evaluates the predicate and
  matches nothing.
"""

import logging
import uuid
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime

from sqlalchemy import exists, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db.enums import (
    ApprovalStatus,
    ConnectionStatus,
    DeliveryState,
    MessageDirection,
    OutboxInstructionState,
)
from app.db.models import Approval, Conversation, Message, OutboxInstruction, PlatformConnection
from app.db.scope import UserScope
from app.services.automation import AutomationController
from app.services.dispatcher import Dispatcher
from app.services.outbox import expire_lapsed_leases

__all__ = [
    "MOCK_PLATFORM",
    "DispatchTick",
    "dispatch_due_messages",
    "due_message_ids",
    "expire_due_approvals",
    "expire_leases",
]

logger = logging.getLogger(__name__)

MOCK_PLATFORM = "mock"
_LIVE = (OutboxInstructionState.PENDING, OutboxInstructionState.LEASED)


@dataclass
class DispatchTick:
    """What one dispatch tick did (counts only)."""

    attempted: int = 0
    errors: int = 0
    outcomes: Counter[str] = field(default_factory=Counter)

    @property
    def all_failed(self) -> bool:
        return self.attempted > 0 and self.errors == self.attempted


async def due_message_ids(
    session: AsyncSession, *, now: datetime, limit: int, include_mock: bool
) -> list[tuple[uuid.UUID, uuid.UUID]]:
    """``(user_id, message_id)`` of due queued outbound messages, oldest first.

    Cross-tenant on purpose (this is the scheduler): it reads IDs only, and every
    message is then handled under its own ``UserScope``. Messages that already have a
    live device instruction are left out: they wait for a device claim, and including
    them would let a phone that is offline fill every batch and starve newer sends.
    For the same reason, messages of a connection that is not ``connected`` (held,
    D-030) are left out; they come back here as soon as the connection recovers.
    """
    stmt = select(Message.user_id, Message.id).where(
        Message.direction == MessageDirection.OUTBOUND,
        Message.delivery_state == DeliveryState.QUEUED,
        (Message.send_after.is_(None)) | (Message.send_after <= now),
        ~exists().where(
            OutboxInstruction.user_id == Message.user_id,
            OutboxInstruction.outbound_message_id == Message.id,
            OutboxInstruction.state.in_(_LIVE),
        ),
        exists().where(
            Conversation.user_id == Message.user_id,
            Conversation.id == Message.conversation_id,
            PlatformConnection.user_id == Conversation.user_id,
            PlatformConnection.id == Conversation.connection_id,
            PlatformConnection.status == ConnectionStatus.CONNECTED,
        ),
    )
    if not include_mock:
        stmt = stmt.where(Message.platform != MOCK_PLATFORM)
    stmt = stmt.order_by(Message.created_at, Message.id).limit(limit)
    return [(row[0], row[1]) for row in (await session.execute(stmt)).all()]


async def dispatch_due_messages(
    session_factory: async_sessionmaker[AsyncSession],
    dispatcher: Dispatcher,
    *,
    now: Callable[[], datetime],
    limit: int,
    include_mock: bool,
    should_stop: Callable[[], bool] = lambda: False,
) -> DispatchTick:
    """Dispatch up to ``limit`` due messages, each in its own committed transaction.

    A failing message is logged (error class only) and skipped; it stays ``queued`` and
    is retried next tick with the same attempt key, so a transport that did send replays
    its stored result instead of sending again. ``should_stop`` is checked between
    messages (never inside a transaction), so shutdown waits for at most one send.
    """
    async with session_factory() as session:
        due = await due_message_ids(session, now=now(), limit=limit, include_mock=include_mock)
    tick = DispatchTick()
    for user_id, message_id in due:
        if should_stop():
            break
        tick.attempted += 1
        try:
            async with session_factory() as session, session.begin():
                result = await dispatcher.dispatch(session, UserScope(user_id), message_id)
            tick.outcomes[result.outcome.value] += 1
        except Exception as exc:
            tick.errors += 1
            logger.error(  # noqa: TRY400 - no traceback on purpose (redaction)
                "worker dispatch failed user=%s message=%s error_class=%s",
                user_id,
                message_id,
                type(exc).__name__,
            )
    return tick


async def expire_leases(
    session_factory: async_sessionmaker[AsyncSession], *, now: Callable[[], datetime]
) -> int:
    """Lapsed device leases -> ``expired`` (the existing outbox job)."""
    return await expire_lapsed_leases(session_factory, now=now)


async def expire_due_approvals(
    session_factory: async_sessionmaker[AsyncSession],
    controller: AutomationController,
    *,
    now: Callable[[], datetime],
) -> int:
    """Pending approvals past ``expires_at`` -> ``expired``, one transaction per user."""
    at = now()
    async with session_factory() as session:
        user_ids = list(
            (
                await session.scalars(
                    select(Approval.user_id)
                    .where(
                        Approval.status == ApprovalStatus.PENDING,
                        Approval.expires_at.is_not(None),
                        Approval.expires_at <= at,
                    )
                    .distinct()
                )
            ).all()
        )
    total = 0
    for user_id in user_ids:
        async with session_factory() as session, session.begin():
            total += await controller.expire_approvals(session, UserScope(user_id))
    return total
