"""The dispatcher: the single send path (WING-011, architecture principle 2).

Nothing else in ``app/`` calls ``Connector.send_message`` or creates a device outbox
instruction; ``tests/automation/test_architecture.py`` enforces it. Callers (the
automation controller, the approve route and the user's own ``sendMessage``) hand the
dispatcher a *decision that was already made*; the dispatcher re-checks the parts of
that decision that can change between deciding and sending, then transmits once.

Delivery state machine (``DeliveryState``)::

    queued -> sending -> sent -> delivered
       |         |-> failed -> queued (retry: new attempt, new key) | dead_lettered
       |-> canceled (pause, takeover, capability change before sending)

* A state only advances on an observed connector outcome: an acknowledged
  ``SendResult`` (backend connectors) or an ingested ``send_acknowledged`` /
  ``send_failed`` / ``delivery_receipt`` event (device connectors). ``delivered``
  needs ``sent`` first; an early receipt is stored and applied after the ack.
* One transport attempt per connector key (D-006). The key is
  ``send:<message_id>:<attempt>`` where ``attempt = attempt_count + 1``. A retry is
  scheduled only after a definitive failure with ``retryable: true``, as a new attempt
  with a new key, up to :attr:`RetryPolicy.max_attempts`; then ``dead_lettered``.
* **Backend connectors** (``MockConnector``): :meth:`Dispatcher.dispatch` locks the
  message row (``FOR UPDATE``), moves it to ``sending`` and calls
  ``Connector.send_message`` while holding the lock, in the caller's transaction. A
  concurrent dispatch of the same message waits and then sees it is no longer
  ``queued``. If the process dies after the transport call but before commit, the
  transaction rolls back, the next dispatch derives the *same* key, and the connector
  replays its stored result instead of sending again (D-006).
* **Device connectors** (SMS, notifications; ADR 0006): the dispatcher never
  transmits. It creates one ``pending`` outbox instruction for the attempt; the
  device leases it through ``claimOutboxInstructions`` (``app.services.outbox``) and
  reports the outcome through ``ingestConnectorEvent``.

Lock order used everywhere (avoids deadlocks with pause/takeover):
user -> contact -> approval -> outbox instruction -> message.

Every transition appends an ``agent_actions`` row with IDs, codes and counts only.
"""

import logging
import uuid
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import StrEnum
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.connectors.base import Connector
from app.connectors.capabilities import CapabilityName
from app.connectors.errors import UnsupportedCapability
from app.connectors.events import ConnectorEvent, ConnectorEventType
from app.connectors.mock import EventScopeMismatch, MockConnector
from app.connectors.models import SendRequest, SendResult
from app.db.base import utc_now
from app.db.enums import (
    Actor,
    AgentActionType,
    CapabilityVerification,
    ContactAutomationStatus,
    DeliveryState,
    GlobalAutomationStatus,
    MessageDirection,
    MessageOrigin,
    OutboxInstructionState,
)
from app.db.models import (
    Contact,
    Conversation,
    Message,
    OutboxInstruction,
    PlatformConnection,
    User,
)
from app.db.repositories import (
    AgentActionRepository,
    ConnectionRepository,
    ContactRepository,
    ConversationRepository,
    EventIngestRepository,
    MessageRepository,
    OutboxRepository,
    UserRepository,
)
from app.db.scope import UserScope

__all__ = [
    "AUTOMATED_ORIGINS",
    "PAUSE_CANCELS",
    "DEVICE_SIDE_PLATFORMS",
    "ConnectorRegistry",
    "DispatchOutcome",
    "DispatchResult",
    "Dispatcher",
    "RecheckReason",
    "RetryPolicy",
    "recheck_send",
    "send_key",
]

logger = logging.getLogger(__name__)

# Platforms whose connector runs on the user's device: sends go through the outbox.
DEVICE_SIDE_PLATFORMS = frozenset({"sms", "android_notification"})
# What pause and takeover cancel (D-012), never the user's own ``user_manual`` sends
# (``sendMessage`` is allowed while paused or taken over):
# * pause (global or contact) stops sends *without approval*: ``autopilot`` only. While
#   paused "the agent may still queue drafts for approval", so a draft the user
#   explicitly approves during a pause is still sent.
# * takeover stops every agent-originated send: ``autopilot`` and ``approved_draft``
#   (it also cancels pending approvals; the user is driving now).
PAUSE_CANCELS = (MessageOrigin.AUTOPILOT,)
AUTOMATED_ORIGINS = (MessageOrigin.AUTOPILOT, MessageOrigin.APPROVED_DRAFT)


def send_key(message_id: uuid.UUID, attempt: int) -> str:
    """Per-attempt connector idempotency key (D-006)."""
    return f"send:{message_id}:{attempt}"


# --------------------------------------------------------------------------- policy re-check


class RecheckReason(StrEnum):
    """Why a queued message may no longer be sent. Values are contract codes."""

    CAPABILITY_UNSUPPORTED = "capability_unsupported"
    CAPABILITY_UNVERIFIED = "capability_unverified"
    AUTOMATION_PAUSED = "automation_paused"
    NOT_QUEUED = "not_queued"


def recheck_send(
    *,
    origin: MessageOrigin,
    send_capability: CapabilityVerification,
    global_status: GlobalAutomationStatus,
    contact_status: ContactAutomationStatus,
) -> RecheckReason | None:
    """The last-moment check before a transport attempt or a lease (D-012).

    Cancels exactly what pause/takeover cancel (automated origins only), any send whose
    capability became ``UNSUPPORTED``, and an ``autopilot`` send whose capability is no
    longer ``VERIFIED`` (ADR 0005). ``None`` means the send may proceed.
    """
    if send_capability is CapabilityVerification.UNSUPPORTED:
        return RecheckReason.CAPABILITY_UNSUPPORTED
    if origin in AUTOMATED_ORIGINS and contact_status is ContactAutomationStatus.TAKEN_OVER:
        return RecheckReason.AUTOMATION_PAUSED
    if origin in PAUSE_CANCELS and (
        global_status is GlobalAutomationStatus.PAUSED
        or contact_status is ContactAutomationStatus.PAUSED
    ):
        return RecheckReason.AUTOMATION_PAUSED
    if origin is MessageOrigin.AUTOPILOT and send_capability is not CapabilityVerification.VERIFIED:
        return RecheckReason.CAPABILITY_UNVERIFIED
    return None


# --------------------------------------------------------------------------- connectors


class ConnectorRegistry:
    """Resolves the backend-side connector for a connection.

    ``None`` from :meth:`backend_connector` means the connection's transport runs on a
    device (outbox) or has no transport at all. By default every ``mock`` connection
    gets its own in-process :class:`MockConnector` (simulated transport, never a real
    platform). Tests can register connectors explicitly, or mark a mock connection as
    device-side to exercise the outbox with ``mock.*`` events.
    """

    def __init__(
        self,
        *,
        connectors: Mapping[uuid.UUID, Connector] | None = None,
        mock_factory: Callable[[PlatformConnection], Connector] | None = None,
        auto_mock: bool = True,
        device_connection_ids: frozenset[uuid.UUID] = frozenset(),
    ) -> None:
        self._connectors: dict[uuid.UUID, Connector] = dict(connectors or {})
        self._mock_factory = mock_factory or self._default_mock
        self._auto_mock = auto_mock
        self._device_ids = set(device_connection_ids)

    @staticmethod
    def _default_mock(connection: PlatformConnection) -> Connector:
        return MockConnector(user_id=connection.user_id, connection_id=connection.id)

    def register(self, connection_id: uuid.UUID, connector: Connector) -> None:
        self._connectors[connection_id] = connector

    def mark_device_side(self, connection_id: uuid.UUID) -> None:
        self._device_ids.add(connection_id)

    def is_device_side(self, connection: PlatformConnection) -> bool:
        if connection.id in self._device_ids:
            return True
        if connection.id in self._connectors:
            return False
        return connection.platform in DEVICE_SIDE_PLATFORMS

    def backend_connector(self, connection: PlatformConnection) -> Connector | None:
        if self.is_device_side(connection):
            return None
        found = self._connectors.get(connection.id)
        if found is None and self._auto_mock and connection.platform == "mock":
            found = self._mock_factory(connection)
            self._connectors[connection.id] = found
        return found


# --------------------------------------------------------------------------- results


class DispatchOutcome(StrEnum):
    SENT = "sent"  # backend connector acknowledged (message ``sent``/``delivered``)
    RETRY_SCHEDULED = "retry_scheduled"  # definitive retryable failure; back to ``queued``
    DEAD_LETTERED = "dead_lettered"
    CANCELED = "canceled"  # policy re-check removed it before any transport attempt
    INSTRUCTION_CREATED = "instruction_created"  # device-side: waiting for a lease
    NOT_DUE = "not_due"  # ``send_after`` in the future
    NOOP = "noop"  # not ``queued`` (already handled) or instruction already live


@dataclass(frozen=True)
class DispatchResult:
    message: Message
    outcome: DispatchOutcome
    reason: str | None = None
    attempt: int | None = None
    idempotency_key: str | None = None


@dataclass(frozen=True)
class RetryPolicy:
    """Bounded retries (attempts include the first one)."""

    max_attempts: int = 3
    backoff_base_s: float = 30.0
    backoff_max_s: float = 900.0

    def __post_init__(self) -> None:
        if not 1 <= self.max_attempts <= 10:
            raise ValueError("max_attempts must be between 1 and 10")

    def backoff(self, attempt: int) -> timedelta:
        seconds = min(self.backoff_max_s, self.backoff_base_s * (2 ** max(0, attempt - 1)))
        return timedelta(seconds=seconds)


@dataclass
class _Context:
    user: User
    contact: Contact
    conversation: Conversation
    connection: PlatformConnection
    extra: dict[str, Any] = field(default_factory=dict)


# --------------------------------------------------------------------------- dispatcher


class Dispatcher:
    def __init__(
        self,
        registry: ConnectorRegistry | None = None,
        *,
        retry: RetryPolicy | None = None,
        now: Callable[[], datetime] = utc_now,
    ) -> None:
        self.registry = registry if registry is not None else ConnectorRegistry()
        self.retry = retry or RetryPolicy()
        self._now = now

    # ------------------------------------------------------------------ queue

    async def queue(
        self,
        session: AsyncSession,
        scope: UserScope,
        *,
        conversation: Conversation,
        origin: MessageOrigin,
        text: str,
        actor: Actor,
        approval_id: uuid.UUID | None = None,
        send_after: datetime | None = None,
        reason_code: str | None = None,
    ) -> Message:
        """Create the outbound message (``queued``) and audit ``send_queued``.

        The caller has already decided this send is authorized (policy, approval or
        the user's own request). Nothing is transmitted here.
        """
        if origin is MessageOrigin.CONTACT:
            raise ValueError("an outbound message cannot have origin 'contact'")
        if (origin is MessageOrigin.APPROVED_DRAFT) != (approval_id is not None):
            raise ValueError("approval_id is set exactly for approved_draft messages")
        now = self._now()
        message = await MessageRepository(session, scope).create_outbound(
            conversation_id=conversation.id,
            platform=conversation.platform,
            origin=origin,
            text=text,
            approval_id=approval_id,
            send_after=send_after if send_after is not None and send_after > now else None,
            created_at=now,
        )
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.SEND_QUEUED,
            actor=actor,
            conversation_id=conversation.id,
            contact_id=conversation.contact_id,
            message_id=message.id,
            approval_id=approval_id,
            reason_code=reason_code,
            occurred_at=now,
            details={
                "origin": origin.value,
                "send_after": _iso(message.send_after),
            },
        )
        return message

    # ------------------------------------------------------------------ dispatch

    async def _context(self, session: AsyncSession, scope: UserScope, message: Message) -> _Context:
        conversation = await ConversationRepository(session, scope).require(message.conversation_id)
        connection = await ConnectionRepository(session, scope).require(conversation.connection_id)
        # Lock order: user -> contact -> message (pause/takeover write these first).
        user = await UserRepository(session).lock(scope, shared=True)
        contact = await ContactRepository(session, scope).lock_shared(conversation.contact_id)
        if user is None or contact is None:  # pragma: no cover - deleted concurrently
            raise LookupError("user or contact vanished during dispatch")
        return _Context(user, contact, conversation, connection)

    async def dispatch(
        self, session: AsyncSession, scope: UserScope, message_id: uuid.UUID
    ) -> DispatchResult:
        """Advance one ``queued`` outbound message by one step. Idempotent.

        Returns ``NOOP`` if the message is not ``queued`` any more (it was already sent,
        canceled, or is being handled by a concurrent call), ``NOT_DUE`` before its
        ``send_after``. Raises ``NotFoundError`` for a message outside ``scope``.
        """
        messages = MessageRepository(session, scope)
        peek = await messages.require(message_id)
        if peek.direction is not MessageDirection.OUTBOUND:
            raise ValueError("only outbound messages can be dispatched")
        ctx = await self._context(session, scope, peek)
        message = await messages.lock(message_id)
        if message is None:  # pragma: no cover - deleted concurrently
            raise LookupError("message vanished during dispatch")
        if message.delivery_state is not DeliveryState.QUEUED:
            return DispatchResult(message, DispatchOutcome.NOOP)
        now = self._now()
        if message.send_after is not None and message.send_after > now:
            return DispatchResult(message, DispatchOutcome.NOT_DUE)
        if await OutboxRepository(session, scope).live_for_message(message.id) is not None:
            # A device instruction already exists: the claim re-checks and leases it.
            return DispatchResult(message, DispatchOutcome.NOOP)

        reason = recheck_send(
            origin=message.origin,
            send_capability=ctx.connection.send_capability,
            global_status=ctx.user.global_automation_status,
            contact_status=ctx.contact.automation_status,
        )
        if reason is not None:
            canceled = await self._cancel(session, scope, message, ctx.conversation, reason, now)
            return DispatchResult(canceled, DispatchOutcome.CANCELED, reason=reason.value)

        if self.registry.is_device_side(ctx.connection):
            return await self._create_instruction(session, scope, message, ctx, now)
        connector = self.registry.backend_connector(ctx.connection)
        if connector is None:
            # No transport for this connection: a definitive, non-retryable failure.
            return await self._fail(
                session,
                scope,
                message,
                ctx.conversation,
                attempt=message.attempt_count + 1,
                code="not_connected",
                detail="no connector is available for this connection",
                retryable=False,
                actor=Actor.DISPATCHER,
                now=now,
            )
        return await self._send_backend(session, scope, message, ctx, connector, now)

    async def dispatch_due(
        self,
        session: AsyncSession,
        scope: UserScope,
        *,
        limit: int = 50,
        connection_id: uuid.UUID | None = None,
    ) -> list[DispatchResult]:
        """Dispatch this user's queued messages whose ``send_after`` has passed (reply
        delays, retry backoff). Each one goes through the full re-check."""
        now = self._now()
        page = await MessageRepository(session, scope).due_outbound(
            now=now, limit=limit, connection_id=connection_id
        )
        results = []
        for message in page:
            results.append(await self.dispatch(session, scope, message.id))
        return results

    async def _send_backend(
        self,
        session: AsyncSession,
        scope: UserScope,
        message: Message,
        ctx: _Context,
        connector: Connector,
        now: datetime,
    ) -> DispatchResult:
        attempt = message.attempt_count + 1
        key = send_key(message.id, attempt)
        thread = ctx.conversation.external_thread_id or ctx.contact.external_contact_id
        messages = MessageRepository(session, scope)
        audit = AgentActionRepository(session, scope)
        message = await messages.update_delivery(
            message.id, delivery_state=DeliveryState.SENDING, attempt_count=attempt
        )
        await audit.record(
            type=AgentActionType.SEND_ATTEMPTED,
            actor=Actor.DISPATCHER,
            conversation_id=ctx.conversation.id,
            contact_id=ctx.conversation.contact_id,
            message_id=message.id,
            approval_id=message.approval_id,
            occurred_at=now,
            details={"attempt": attempt, "transport": "backend", "origin": message.origin.value},
        )
        subscription = connector.subscribe()
        try:
            try:
                result = await connector.send_message(
                    SendRequest(
                        outbound_message_id=message.id,
                        idempotency_key=key,
                        external_thread_id=thread,
                        text=message.text,
                    )
                )
            except UnsupportedCapability:
                # Raised before any side effect: nothing went out (typed, no send).
                return await self._fail(
                    session,
                    scope,
                    message,
                    ctx.conversation,
                    attempt=attempt,
                    code=RecheckReason.CAPABILITY_UNSUPPORTED.value,
                    detail="connector reports send as UNSUPPORTED",
                    retryable=False,
                    actor=Actor.CONNECTOR,
                    now=self._now(),
                    key=key,
                )
            receipts = []
            while (event := subscription.get_nowait()) is not None:
                if event.type in (
                    ConnectorEventType.MESSAGE_DELIVERY_RECEIPT,
                    ConnectorEventType.MOCK_DELIVERY_RECEIPT_SIMULATED,
                ):
                    receipts.append(event)
        finally:
            subscription.close()
        return await self._apply_result(session, scope, message, ctx, result, receipts)

    async def _apply_result(
        self,
        session: AsyncSession,
        scope: UserScope,
        message: Message,
        ctx: _Context,
        result: SendResult,
        receipts: list[ConnectorEvent],
    ) -> DispatchResult:
        attempt = message.attempt_count
        if result.acknowledged:
            sent = await self.mark_sent(
                session,
                scope,
                message,
                external_message_id=result.external_message_id,
                occurred_at=result.event.occurred_at,
                attempt=attempt,
                replayed=result.replayed,
            )
            for event in receipts:
                payload = event.payload
                if getattr(payload, "outbound_message_id", None) == message.id:
                    sent = await self.mark_delivered(session, scope, sent, event.occurred_at)
            return DispatchResult(
                sent, DispatchOutcome.SENT, attempt=attempt, idempotency_key=result.idempotency_key
            )
        failure = result.failure
        return await self._fail(
            session,
            scope,
            message,
            ctx.conversation,
            attempt=attempt,
            code=failure.code.value if failure else "transport_error",
            detail=failure.detail if failure else None,
            retryable=bool(failure and failure.retryable),
            actor=Actor.CONNECTOR,
            now=self._now(),
            key=result.idempotency_key,
        )

    # ------------------------------------------------------------------ transitions

    async def mark_sent(
        self,
        session: AsyncSession,
        scope: UserScope,
        message: Message,
        *,
        external_message_id: str | None,
        occurred_at: datetime,
        attempt: int,
        replayed: bool = False,
    ) -> Message:
        """``sending`` -> ``sent`` on an observed acknowledgement. Other states: no-op."""
        if message.delivery_state is not DeliveryState.SENDING:
            return message
        now = self._now()
        values: dict[str, Any] = {
            "delivery_state": DeliveryState.SENT,
            "sent_at": min(occurred_at, now),
            "failure_code": None,
            "failure_detail": None,
            "failure_retryable": None,
        }
        if external_message_id is not None:
            values["external_message_id"] = external_message_id
        sent = await MessageRepository(session, scope).update_delivery(message.id, **values)
        conversation = await ConversationRepository(session, scope).require(message.conversation_id)
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.SEND_ACKNOWLEDGED,
            actor=Actor.CONNECTOR,
            conversation_id=conversation.id,
            contact_id=conversation.contact_id,
            message_id=message.id,
            approval_id=message.approval_id,
            occurred_at=now,
            details={"attempt": attempt, "replayed": replayed},
        )
        # A receipt that arrived before this acknowledgement is applied now.
        ingests = EventIngestRepository(session, scope)
        for record in await ingests.deferred_receipts(message.id):
            sent = await self.mark_delivered(session, scope, sent, record.occurred_at)
            await ingests.annotate(record.id, deferred=False)
        return sent

    async def mark_delivered(
        self, session: AsyncSession, scope: UserScope, message: Message, occurred_at: datetime
    ) -> Message:
        """``sent`` -> ``delivered`` on an observed receipt. Never from any other state."""
        if message.delivery_state is not DeliveryState.SENT or message.sent_at is None:
            return message
        now = self._now()
        delivered = await MessageRepository(session, scope).update_delivery(
            message.id,
            delivery_state=DeliveryState.DELIVERED,
            delivered_at=max(message.sent_at, min(occurred_at, now)),
        )
        conversation = await ConversationRepository(session, scope).require(message.conversation_id)
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.DELIVERY_CONFIRMED,
            actor=Actor.CONNECTOR,
            conversation_id=conversation.id,
            contact_id=conversation.contact_id,
            message_id=message.id,
            approval_id=message.approval_id,
            occurred_at=now,
            details={"attempt": message.attempt_count},
        )
        return delivered

    async def record_failure(
        self,
        session: AsyncSession,
        scope: UserScope,
        message: Message,
        *,
        attempt: int,
        code: str,
        detail: str | None,
        retryable: bool,
        key: str | None,
    ) -> DispatchResult:
        """A definitive ``send_failed`` reported by a device for a ``sending`` message."""
        if message.delivery_state is not DeliveryState.SENDING:
            return DispatchResult(message, DispatchOutcome.NOOP)
        conversation = await ConversationRepository(session, scope).require(message.conversation_id)
        return await self._fail(
            session,
            scope,
            message,
            conversation,
            attempt=attempt,
            code=code,
            detail=detail,
            retryable=retryable,
            actor=Actor.CONNECTOR,
            now=self._now(),
            key=key,
        )

    async def _fail(
        self,
        session: AsyncSession,
        scope: UserScope,
        message: Message,
        conversation: Conversation,
        *,
        attempt: int,
        code: str,
        detail: str | None,
        retryable: bool,
        actor: Actor,
        now: datetime,
        key: str | None = None,
    ) -> DispatchResult:
        messages = MessageRepository(session, scope)
        audit = AgentActionRepository(session, scope)
        failed = await messages.update_delivery(
            message.id,
            delivery_state=DeliveryState.FAILED,
            attempt_count=max(attempt, message.attempt_count),
            failure_code=code,
            failure_detail=(detail or None) and detail[:500],
            failure_retryable=retryable,
        )
        base = {
            "conversation_id": conversation.id,
            "contact_id": conversation.contact_id,
            "message_id": message.id,
            "approval_id": message.approval_id,
        }
        await audit.record(
            type=AgentActionType.SEND_FAILED,
            actor=actor,
            reason_code=code,
            occurred_at=now,
            details={"attempt": attempt, "retryable": retryable},
            **base,
        )
        if retryable and attempt < self.retry.max_attempts:
            requeued = await messages.update_delivery(
                message.id,
                delivery_state=DeliveryState.QUEUED,
                send_after=now + self.retry.backoff(attempt),
            )
            await audit.record(
                type=AgentActionType.SEND_QUEUED,
                actor=Actor.DISPATCHER,
                reason_code=code,
                occurred_at=now,
                details={
                    "retry": True,
                    "next_attempt": attempt + 1,
                    "send_after": _iso(requeued.send_after),
                },
                **base,
            )
            return DispatchResult(
                requeued, DispatchOutcome.RETRY_SCHEDULED, reason=code, attempt=attempt
            )
        dead = await messages.update_delivery(
            message.id, delivery_state=DeliveryState.DEAD_LETTERED
        )
        await audit.record(
            type=AgentActionType.SEND_DEAD_LETTERED,
            actor=Actor.DISPATCHER,
            reason_code=code,
            occurred_at=now,
            details={"attempts": attempt, "retryable": retryable},
            **base,
        )
        del failed
        return DispatchResult(
            dead, DispatchOutcome.DEAD_LETTERED, reason=code, attempt=attempt, idempotency_key=key
        )

    async def _cancel(
        self,
        session: AsyncSession,
        scope: UserScope,
        message: Message,
        conversation: Conversation,
        reason: RecheckReason,
        now: datetime,
    ) -> Message:
        canceled = await MessageRepository(session, scope).update_delivery(
            message.id, delivery_state=DeliveryState.CANCELED
        )
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.SEND_CANCELED,
            actor=Actor.DISPATCHER,
            conversation_id=conversation.id,
            contact_id=conversation.contact_id,
            message_id=message.id,
            approval_id=message.approval_id,
            reason_code=reason.value,
            occurred_at=now,
            details={"origin": message.origin.value, "stage": "dispatch"},
        )
        return canceled

    async def cancel_for_claim(
        self,
        session: AsyncSession,
        scope: UserScope,
        message: Message,
        instruction: OutboxInstruction,
        reason: RecheckReason,
    ) -> None:
        """Claim-time re-check failed: the instruction and its queued message are canceled."""
        now = self._now()
        await OutboxRepository(session, scope).set_state(
            instruction.id, state=OutboxInstructionState.CANCELED
        )
        if message.delivery_state is not DeliveryState.QUEUED:
            return
        conversation = await ConversationRepository(session, scope).require(message.conversation_id)
        await MessageRepository(session, scope).update_delivery(
            message.id, delivery_state=DeliveryState.CANCELED
        )
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.SEND_CANCELED,
            actor=Actor.DISPATCHER,
            conversation_id=conversation.id,
            contact_id=conversation.contact_id,
            message_id=message.id,
            approval_id=message.approval_id,
            reason_code=reason.value,
            occurred_at=now,
            details={"origin": message.origin.value, "stage": "claim"},
        )

    # ------------------------------------------------------------------ device outbox

    async def _create_instruction(
        self,
        session: AsyncSession,
        scope: UserScope,
        message: Message,
        ctx: _Context,
        now: datetime,
    ) -> DispatchResult:
        outbox = OutboxRepository(session, scope)
        attempt = message.attempt_count + 1
        key = send_key(message.id, attempt)
        await outbox.create_instruction(
            connection_id=ctx.connection.id,
            outbound_message_id=message.id,
            attempt=attempt,
            idempotency_key=key,
            created_at=now,
        )
        return DispatchResult(
            message,
            DispatchOutcome.INSTRUCTION_CREATED,
            attempt=attempt,
            idempotency_key=key,
        )

    # ------------------------------------------------------------------ bulk cancel

    async def cancel_queued_automated(
        self,
        session: AsyncSession,
        scope: UserScope,
        *,
        contact_id: uuid.UUID | None,
        reason_code: str,
        actor: Actor = Actor.USER,
        origins: tuple[MessageOrigin, ...] = AUTOMATED_ORIGINS,
    ) -> int:
        """Pause/takeover: cancel queued messages with one of ``origins`` (default: every
        agent origin; never ``user_manual``) and their pending outbox instructions.
        ``contact_id=None`` = every contact.

        Instructions are canceled before messages (lock order). A message a device has
        already leased is ``sending`` and is left alone: its real outcome is recorded.
        """
        now = self._now()
        messages = MessageRepository(session, scope)
        queued = await messages.queued_automated(contact_id=contact_id, origins=origins)
        if not queued:
            return 0
        await OutboxRepository(session, scope).cancel_pending_for_messages(
            [m.id for m in queued], at=now
        )
        audit = AgentActionRepository(session, scope)
        conversations = ConversationRepository(session, scope)
        contact_of: dict[uuid.UUID, uuid.UUID] = {}
        count = 0
        for message in queued:
            locked = await messages.lock(message.id)
            if locked is None or locked.delivery_state is not DeliveryState.QUEUED:
                continue  # dispatched or leased meanwhile: its real outcome is recorded
            await messages.update_delivery(locked.id, delivery_state=DeliveryState.CANCELED)
            if locked.conversation_id not in contact_of:
                conv = await conversations.require(locked.conversation_id)
                contact_of[locked.conversation_id] = conv.contact_id
            await audit.record(
                type=AgentActionType.SEND_CANCELED,
                actor=actor,
                conversation_id=locked.conversation_id,
                contact_id=contact_of[locked.conversation_id],
                message_id=locked.id,
                approval_id=locked.approval_id,
                reason_code=reason_code,
                occurred_at=now,
                details={"origin": locked.origin.value, "stage": "queued"},
            )
            count += 1
        return count

    async def cancel_queued_message(
        self,
        session: AsyncSession,
        scope: UserScope,
        message_id: uuid.UUID,
        *,
        reason_code: str,
        actor: Actor,
    ) -> bool:
        """Cancel one still-``queued`` message (and its pending instruction). ``False`` if
        it already left the queue."""
        now = self._now()
        await OutboxRepository(session, scope).cancel_pending_for_messages([message_id], at=now)
        messages = MessageRepository(session, scope)
        locked = await messages.lock(message_id)
        if locked is None or locked.delivery_state is not DeliveryState.QUEUED:
            return False
        await messages.update_delivery(locked.id, delivery_state=DeliveryState.CANCELED)
        conversation = await ConversationRepository(session, scope).require(locked.conversation_id)
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.SEND_CANCELED,
            actor=actor,
            conversation_id=conversation.id,
            contact_id=conversation.contact_id,
            message_id=locked.id,
            approval_id=locked.approval_id,
            reason_code=reason_code,
            occurred_at=now,
            details={"origin": locked.origin.value, "stage": "queued"},
        )
        return True

    # ------------------------------------------------------------------ inbound mirror

    async def observe_inbound(self, connection: PlatformConnection, event: ConnectorEvent) -> None:
        """Let an in-process simulator see an ingested inbound message, so it knows the
        thread it may later be asked to send into. Never sends anything."""
        connector = self.registry.backend_connector(connection)
        if isinstance(connector, MockConnector) and event.type.is_inbound_message:
            try:
                await connector.ingest(event)
            except EventScopeMismatch:
                logger.info("mock connector ignored an event for another scope")


def _iso(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.isoformat().replace("+00:00", "Z")


# Re-exported for the architecture test: the capability the dispatcher relies on.
SEND_CAPABILITY = CapabilityName.SEND
