"""Device outbox: claim, listing and lease expiry (ADR 0006, contract v1.1.0).

Instructions are created only by :mod:`app.services.dispatcher`. This module leases
them to a device and expires lapsed leases; it never creates one and never transmits.

Claim, per instruction, atomically (one transaction, row locks):

1. Lock the user row ``FOR UPDATE`` (serializes with pause/takeover/policy decisions,
   which take the same lock first), then the oldest ``pending`` instructions of the
   connection ``FOR UPDATE SKIP LOCKED``: two devices claiming at once never get the
   same row; the loser skips it.
2. Re-check policy with the message's origin (D-012, :func:`recheck_send`). A failing
   instruction becomes ``canceled`` and its ``queued`` message ``canceled``.
3. Lease the rest to ``device_install_id``: state ``leased``, ``leased_at`` now,
   ``lease_expires_at`` = now + lease (server-set, default 120 s, bounded 30 to 600 s).
   The message moves ``queued`` -> ``sending`` (audit ``send_attempted``).
4. Also return this install's own unexpired leases (same lease, not extended), counting
   toward ``max_instructions``.

Lease expiry (:func:`expire_lapsed_leases`, a periodic job): ``leased`` past
``lease_expires_at`` -> ``expired``. The outcome is unknown, so the message stays
``sending``: never ``sent``, never retried automatically. A late outcome event still
completes the instruction (``app.services.ingest``).
"""

import logging
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db.base import utc_now
from app.db.enums import (
    Actor,
    AgentActionType,
    DeliveryState,
    OutboxInstructionState,
)
from app.db.errors import NotFoundError
from app.db.models import Message, OutboxInstruction, PlatformConnection, User
from app.db.repositories import (
    AgentActionRepository,
    ConnectionRepository,
    ContactRepository,
    ConversationRepository,
    MessageRepository,
    OutboxRepository,
    UserRepository,
)
from app.db.scope import UserScope
from app.services.dispatcher import Dispatcher, recheck_send

__all__ = [
    "DEFAULT_LEASE",
    "MAX_LEASE",
    "MIN_LEASE",
    "ClaimedInstruction",
    "OutboxService",
    "expire_lapsed_leases",
    "lease_duration",
]

logger = logging.getLogger(__name__)

DEFAULT_LEASE = timedelta(seconds=120)
MIN_LEASE = timedelta(seconds=30)
MAX_LEASE = timedelta(seconds=600)


def lease_duration(configured: timedelta = DEFAULT_LEASE) -> timedelta:
    """The server-set lease, clamped to the contract bounds (30 to 600 s)."""
    return max(MIN_LEASE, min(MAX_LEASE, configured))


@dataclass(frozen=True)
class ClaimedInstruction:
    instruction: OutboxInstruction
    message: Message
    conversation_id: uuid.UUID
    external_thread_id: str


class OutboxService:
    def __init__(
        self,
        dispatcher: Dispatcher,
        *,
        lease: timedelta = DEFAULT_LEASE,
        now: Callable[[], datetime] = utc_now,
    ) -> None:
        self.dispatcher = dispatcher
        self.lease = lease_duration(lease)
        self._now = now

    async def claim(
        self,
        session: AsyncSession,
        scope: UserScope,
        connection_id: uuid.UUID,
        *,
        device_install_id: uuid.UUID,
        max_instructions: int,
    ) -> tuple[list[ClaimedInstruction], datetime]:
        """Lease up to ``max_instructions`` (own unexpired leases included). Returns the
        instructions (oldest first) and the server time of the claim."""
        if not 1 <= max_instructions <= 20:
            raise ValueError("max_instructions must be between 1 and 20")
        connection = await ConnectionRepository(session, scope).require(connection_id)
        users = UserRepository(session)
        if await users.lock(scope) is None:  # pragma: no cover
            raise NotFoundError("user not found")
        # Due queued messages without an instruction (a retry after its backoff, a reply
        # delay that has passed) get one now, through the dispatcher and its re-check.
        await self.dispatcher.dispatch_due(session, scope, connection_id=connection_id)
        user = await users.get(scope)
        if user is None:  # pragma: no cover
            raise NotFoundError("user not found")
        now = self._now()
        outbox = OutboxRepository(session, scope)
        own = await outbox.own_leases(connection_id, device_install_id, now=now)
        out: list[ClaimedInstruction] = []
        for instruction in own[:max_instructions]:
            out.append(await self._claimed(session, scope, instruction))
        room = max_instructions - len(out)
        while room > 0:
            batch = await outbox.lock_pending(connection_id, limit=room)
            if not batch:
                break
            for instruction in batch:
                leased = await self._try_lease(
                    session, scope, connection, user, instruction, device_install_id, now
                )
                if leased is not None:
                    out.append(leased)
                    room -= 1
        out.sort(key=lambda c: (c.instruction.created_at, c.instruction.id))
        return out, now

    async def _try_lease(
        self,
        session: AsyncSession,
        scope: UserScope,
        connection: PlatformConnection,
        user: User,
        instruction: OutboxInstruction,
        device_install_id: uuid.UUID,
        now: datetime,
    ) -> ClaimedInstruction | None:
        messages = MessageRepository(session, scope)
        message = await messages.require(instruction.outbound_message_id)
        conversation = await ConversationRepository(session, scope).require(message.conversation_id)
        contact = await ContactRepository(session, scope).get_fresh(conversation.contact_id)
        if contact is None:  # pragma: no cover
            raise NotFoundError("contact not found")
        locked = await messages.lock(message.id)
        if locked is None or locked.delivery_state is not DeliveryState.QUEUED:
            # Should not happen (a live instruction implies a queued message); never
            # lease an instruction whose message is in any other state.
            await OutboxRepository(session, scope).set_state(
                instruction.id, state=OutboxInstructionState.CANCELED
            )
            return None
        reason = recheck_send(
            origin=locked.origin,
            send_capability=connection.send_capability,
            global_status=user.global_automation_status,
            contact_status=contact.automation_status,
        )
        if reason is not None:
            await self.dispatcher.cancel_for_claim(session, scope, locked, instruction, reason)
            return None
        leased = await OutboxRepository(session, scope).set_state(
            instruction.id,
            state=OutboxInstructionState.LEASED,
            device_install_id=device_install_id,
            leased_at=now,
            lease_expires_at=now + self.lease,
        )
        sending = await messages.update_delivery(
            locked.id, delivery_state=DeliveryState.SENDING, attempt_count=instruction.attempt
        )
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.SEND_ATTEMPTED,
            actor=Actor.DISPATCHER,
            conversation_id=conversation.id,
            contact_id=conversation.contact_id,
            message_id=locked.id,
            approval_id=locked.approval_id,
            occurred_at=now,
            details={
                "attempt": instruction.attempt,
                "transport": "device",
                "instruction_id": str(instruction.id),
                "lease_seconds": int(self.lease.total_seconds()),
                "origin": locked.origin.value,
            },
        )
        return ClaimedInstruction(
            leased,
            sending,
            conversation.id,
            conversation.external_thread_id or contact.external_contact_id,
        )

    async def _claimed(
        self, session: AsyncSession, scope: UserScope, instruction: OutboxInstruction
    ) -> ClaimedInstruction:
        message = await MessageRepository(session, scope).require(instruction.outbound_message_id)
        conversation = await ConversationRepository(session, scope).require(message.conversation_id)
        contact = await ContactRepository(session, scope).require(conversation.contact_id)
        return ClaimedInstruction(
            instruction,
            message,
            conversation.id,
            conversation.external_thread_id or contact.external_contact_id,
        )

    async def expire(self, session: AsyncSession, scope: UserScope) -> list[OutboxInstruction]:
        """This user's lapsed leases -> ``expired`` (audited). Messages are untouched."""
        now = self._now()
        expired = await OutboxRepository(session, scope).expire_lapsed(now=now)
        # Contract gap: ``AgentActionType`` has no "lease expired / outcome unknown"
        # entry, and ``send_failed`` would claim a failure nobody observed. So expiry is
        # visible on the instruction (``state: expired``, ``listOutboxInstructions``) and
        # in the log, not in the audit timeline. The message stays ``sending``.
        for instruction in expired:
            logger.warning(
                "outbox lease expired user=%s instruction=%s message=%s attempt=%d "
                "outcome=unknown retried=false",
                scope.user_id,
                instruction.id,
                instruction.outbound_message_id,
                instruction.attempt,
            )
        return expired


async def expire_lapsed_leases(
    session_factory: async_sessionmaker[AsyncSession],
    *,
    now: Callable[[], datetime] = utc_now,
) -> int:
    """Periodic job: expire every user's lapsed leases, one transaction per user.

    Run it every ~30 s (e.g. ``python -m app.services.outbox``). Safe to run
    concurrently with claims and ingest: it only touches ``leased`` rows past their
    deadline and never changes message state.
    """
    at = now()
    async with session_factory() as session:
        user_ids = list(
            (
                await session.scalars(
                    select(OutboxInstruction.user_id)
                    .where(
                        OutboxInstruction.state == OutboxInstructionState.LEASED,
                        OutboxInstruction.lease_expires_at <= at,
                    )
                    .distinct()
                )
            ).all()
        )
    total = 0
    service = OutboxService(Dispatcher(), now=lambda: at)
    for user_id in user_ids:
        async with session_factory() as session, session.begin():
            total += len(await service.expire(session, UserScope(user_id)))
    return total


def _main() -> None:  # pragma: no cover - thin CLI wrapper
    import asyncio
    import logging

    from app.db.session import dispose_engine, get_session_factory

    logging.basicConfig(level=logging.INFO)

    async def run() -> None:
        try:
            count = await expire_lapsed_leases(get_session_factory())
            logging.getLogger(__name__).info("expired %d outbox lease(s)", count)
        finally:
            await dispose_engine()

    asyncio.run(run())


if __name__ == "__main__":  # pragma: no cover
    _main()
