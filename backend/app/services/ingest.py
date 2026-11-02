"""Connector event ingest (``ingestConnectorEvent``, contract v1.1.0, ADR 0006).

:class:`IngestService` turns one ``connector_event.v1`` envelope into state:

* inbound (``message.received`` / ``mock.inbound_message_injected``): get-or-create the
  contact and conversation, store the message once per
  ``(user_id, platform, external_message_id)`` (``MessageRepository.ingest_inbound``),
  audit ``message_received``. The pipeline (memory -> analysis -> draft -> policy) is
  run by the caller *after* this is committed.
* send outcomes: only for an instruction the dispatcher issued to **this** connection
  that was actually leased. acknowledged -> ``sent``; failed -> ``failed`` (and a retry
  as a new instruction only when ``retryable: true`` and within budget); receipt ->
  ``delivered`` only after ``sent`` (an early receipt is stored, ``deferred``, and
  applied by the dispatcher when the acknowledgement lands). A late outcome for an
  ``expired`` lease is accepted: the device may really have sent.
* ``connection.capabilities_changed`` / ``connection.status_changed``: stored as
  reported, except that a claimed ``VERIFIED`` is never raised above what the server
  already holds (ADR 0007, ``trust_claimed_capabilities``); they authorize nothing.
  A status of ``error`` or ``disconnected`` *holds* the connection's sends (D-030): the
  dispatcher creates no instruction and calls no connector for it, and claims lease
  nothing new, until it reports ``connected`` again. Entering the hold is audited once
  (``policy_blocked``, reason ``connection_unavailable``, ``details.hold: "started"``).
* an unpaired connection (contract 1.2.0) accepts only send outcomes (``422`` at
  ``/connection_id`` otherwise).
* contract 1.3.0 (ADR 0008): an inbound event becomes a ``Message`` (and reaches the
  pipeline) only when it is a ``chat_message`` with ``identity: linked`` and neither
  ``truncated`` nor ``content_hidden``. Anything else is stored as an
  ``InboundNotice``: no contact, conversation, draft, approval or send is created, so
  an unlinked or ambiguous sender can never be merged into a person. ``source``, when
  given, must equal the connection's ``transport`` (``422`` at ``/payload/source``).

Dedup is permanent on ``(user_id, event_id)`` (``connector_event_ingests``) and, for
inbound messages, on ``(user_id, platform, external_message_id)``. A repeat changes
nothing and writes no second audit entry.

Validation order (the route maps the errors): ownership (``404``), envelope schema and
consistency (``422``), ``mock.*`` only on ``mock`` connections (``422`` ``/type``),
send outcomes must match a leased instruction (``422`` under ``/payload``).
"""

import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum

from sqlalchemy.ext.asyncio import AsyncSession

from app.connectors.capabilities import ConnectorCapabilities
from app.connectors.events import (
    CONNECTION_TYPES,
    DELIVERY_RECEIPT_TYPES,
    INBOUND_MESSAGE_TYPES,
    MOCK_PLATFORM,
    SEND_ACKNOWLEDGED_TYPES,
    SEND_FAILED_TYPES,
    CapabilitiesChangedPayload,
    ConnectionStatusPayload,
    ConnectorEvent,
    DeliveryReceiptPayload,
    InboundMessagePayload,
    SendAcknowledgedPayload,
    SendFailedPayload,
)
from app.db.base import utc_now
from app.db.enums import (
    Actor,
    AgentActionType,
    ConnectionStatus,
    ConnectionTransport,
    DeliveryState,
    InboundEventKind,
    OutboxInstructionState,
    SenderIdentity,
)
from app.db.models import InboundNotice, Message, PlatformConnection
from app.db.repositories import (
    AgentActionRepository,
    ConnectionRepository,
    ContactRepository,
    ConversationRepository,
    EventIngestRepository,
    InboundNoticeRepository,
    MessageRepository,
    OutboxRepository,
    UserRepository,
    fingerprint,
)
from app.db.scope import UserScope
from app.services.connections import trust_claimed_capabilities
from app.services.dispatcher import HOLD_REASON, HOLD_STATUSES, Dispatcher

__all__ = ["IngestError", "IngestOutcome", "IngestResult", "IngestService"]


class IngestOutcome(StrEnum):
    ACCEPTED = "accepted"
    DUPLICATE_EVENT = "duplicate_event"
    DUPLICATE_MESSAGE = "duplicate_message"


@dataclass(frozen=True)
class IngestResult:
    event_id: uuid.UUID
    outcome: IngestOutcome
    message_id: uuid.UUID | None
    recorded_at: datetime
    # Set when a new inbound message was stored: the caller runs the pipeline for it.
    new_inbound_message_id: uuid.UUID | None = None
    # Contract 1.3.0: the notice an inbound event was stored as (never the pipeline).
    notice_id: uuid.UUID | None = None


class IngestError(Exception):
    """A rejected envelope. ``status`` is 404 or 422; ``pointer`` locates the problem."""

    def __init__(
        self,
        status: int,
        detail: str,
        *,
        pointer: str | None = None,
        message: str | None = None,
        mismatch: bool = False,
    ) -> None:
        super().__init__(detail)
        self.status = status
        self.detail = detail
        self.pointer = pointer
        self.message = message or detail
        self.mismatch = mismatch


class IngestService:
    def __init__(self, dispatcher: Dispatcher, *, now: Callable[[], datetime] = utc_now) -> None:
        self.dispatcher = dispatcher
        self._now = now

    @staticmethod
    def request_hash(raw: object) -> str:
        return fingerprint(raw)

    async def ingest(
        self,
        session: AsyncSession,
        scope: UserScope,
        connection: PlatformConnection,
        event: ConnectorEvent,
        *,
        request_hash: str,
    ) -> IngestResult:
        """Apply one validated envelope for ``connection`` (already ownership-checked)."""
        if event.platform != connection.platform:
            raise IngestError(
                422,
                "envelope platform does not match the connection",
                pointer="/platform",
                message="must equal the connection's platform",
            )
        if connection.unpaired_at is not None and not (
            event.type in SEND_ACKNOWLEDGED_TYPES
            or event.type in SEND_FAILED_TYPES
            or event.type in DELIVERY_RECEIPT_TYPES
        ):
            # Contract 1.2.0 (ADR 0007): an unpaired connection accepts only late
            # outcomes of sends it was already leased (results are the truth).
            raise IngestError(
                422,
                "the connection is unpaired",
                pointer="/connection_id",
                message="connection is unpaired; only send outcomes are accepted",
            )
        if event.type.is_mock and connection.platform != MOCK_PLATFORM:
            raise IngestError(
                422,
                "mock.* events are only accepted on a mock connection",
                pointer="/type",
                message=f"'{event.type.value}' is not allowed on platform '{connection.platform}'",
            )

        ingests = EventIngestRepository(session, scope)
        previous = await ingests.get_by_event_id(event.event_id)
        if previous is not None:
            return self._replay(previous, request_hash, event.event_id)

        now = self._now()
        record = await ingests.record(
            event_id=event.event_id,
            connection_id=connection.id,
            event_type=event.type.value,
            request_hash=request_hash,
            outcome=IngestOutcome.ACCEPTED.value,
            occurred_at=event.occurred_at,
            at=now,
        )
        if record is None:
            # A concurrent request with the same event_id committed first.
            previous = await ingests.get_by_event_id(event.event_id)
            if previous is None:  # pragma: no cover
                raise LookupError("ingest record vanished")
            return self._replay(previous, request_hash, event.event_id)

        if event.type in INBOUND_MESSAGE_TYPES:
            payload = event.payload
            assert isinstance(payload, InboundMessagePayload)  # noqa: S101 - by event type
            if payload.source is not None and payload.source.value != connection.transport.value:
                raise IngestError(
                    422,
                    "payload source does not match the connection's transport",
                    pointer="/payload/source",
                    message=f"must equal '{connection.transport.value}'",
                )
            if not payload.enters_pipeline:
                notice, fresh = await self._notice(session, scope, connection, event, now)
                if not fresh:
                    await ingests.annotate(record.id, outcome=IngestOutcome.DUPLICATE_MESSAGE.value)
                    return IngestResult(
                        event.event_id,
                        IngestOutcome.DUPLICATE_MESSAGE,
                        None,
                        now,
                        notice_id=notice.id,
                    )
                return IngestResult(
                    event.event_id, IngestOutcome.ACCEPTED, None, now, notice_id=notice.id
                )
            stored, created = await self._inbound(session, scope, connection, event, now)
            if not created:
                await ingests.annotate(
                    record.id,
                    outcome=IngestOutcome.DUPLICATE_MESSAGE.value,
                    message_id=stored.id,
                )
                return IngestResult(event.event_id, IngestOutcome.DUPLICATE_MESSAGE, stored.id, now)
            await ingests.annotate(record.id, message_id=stored.id)
            return IngestResult(
                event.event_id,
                IngestOutcome.ACCEPTED,
                stored.id,
                now,
                new_inbound_message_id=stored.id,
            )
        if event.type in CONNECTION_TYPES:
            await self._connection_event(session, scope, connection, event)
            return IngestResult(event.event_id, IngestOutcome.ACCEPTED, None, now)

        message_id = await self._send_outcome(session, scope, connection, event, record.id)
        await ingests.annotate(record.id, message_id=message_id)
        return IngestResult(event.event_id, IngestOutcome.ACCEPTED, message_id, now)

    @staticmethod
    def _replay(previous: object, request_hash: str, event_id: uuid.UUID) -> IngestResult:
        from app.db.models import ConnectorEventIngest

        assert isinstance(previous, ConnectorEventIngest)  # noqa: S101
        if previous.request_hash != request_hash:
            raise IngestError(
                422, "this event_id was already used with a different envelope", mismatch=True
            )
        return IngestResult(
            event_id, IngestOutcome.DUPLICATE_EVENT, previous.message_id, previous.created_at
        )

    # ------------------------------------------------------------------ notices (1.3.0)

    async def _notice(
        self,
        session: AsyncSession,
        scope: UserScope,
        connection: PlatformConnection,
        event: ConnectorEvent,
        now: datetime,
    ) -> tuple[InboundNotice, bool]:
        """Store a non-pipeline inbound event. Creates no contact or conversation, so
        nothing is merged by name, and nothing downstream (draft, approval, send) runs."""
        payload = event.payload
        assert isinstance(payload, InboundMessagePayload)  # noqa: S101
        assert event.external_message_id is not None  # noqa: S101 - schema requires it
        notice, created = await InboundNoticeRepository(session, scope).create_once(
            connection_id=connection.id,
            platform=connection.platform,
            event_id=event.event_id,
            kind=InboundEventKind(payload.effective_kind.value),
            identity=SenderIdentity(payload.effective_identity.value),
            sender_label=payload.sender_label,
            text=payload.text,
            truncated=bool(payload.truncated),
            content_hidden=bool(payload.content_hidden),
            external_message_id=event.external_message_id,
            external_conversation_id=payload.external_conversation_id,
            source=ConnectionTransport(payload.source.value) if payload.source else None,
            occurred_at=event.occurred_at,
        )
        if created:
            await AgentActionRepository(session, scope).record(
                type=AgentActionType.NOTICE_RECEIVED,
                actor=Actor.CONNECTOR,
                occurred_at=now,
                reason_code=payload.effective_kind.value,
                details={
                    "notice_id": str(notice.id),
                    "connection_id": str(connection.id),
                    "platform": connection.platform,
                    "event_id": str(event.event_id),
                    "kind": payload.effective_kind.value,
                    "identity": payload.effective_identity.value,
                    "truncated": bool(payload.truncated),
                    "content_hidden": bool(payload.content_hidden),
                },
            )
        return notice, created

    # ------------------------------------------------------------------ inbound

    async def _inbound(
        self,
        session: AsyncSession,
        scope: UserScope,
        connection: PlatformConnection,
        event: ConnectorEvent,
        now: datetime,
    ) -> tuple[Message, bool]:
        payload = event.payload
        assert isinstance(payload, InboundMessagePayload)  # noqa: S101 - by event type
        assert event.external_message_id is not None  # noqa: S101 - schema requires it
        sender = payload.sender
        messages = MessageRepository(session, scope)
        existing = await messages.get_by_external_id(connection.platform, event.external_message_id)
        if existing is not None:
            return existing, False
        contact = await ContactRepository(session, scope).get_or_create(
            connection_id=connection.id,
            external_contact_id=sender.external_contact_id,
            display_name=(sender.display_name or sender.external_contact_id)[:200],
        )
        conversation = await ConversationRepository(session, scope).get_or_create_for_thread(
            contact_id=contact.id,
            connection_id=connection.id,
            platform=connection.platform,
            external_thread_id=event.external_thread_id,
            last_activity_at=event.occurred_at,
        )
        result = await messages.ingest_inbound(
            conversation_id=conversation.id,
            platform=connection.platform,
            external_message_id=event.external_message_id,
            text=payload.text,
            sent_at_source=event.occurred_at,
        )
        if result.created:
            await AgentActionRepository(session, scope).record(
                type=AgentActionType.MESSAGE_RECEIVED,
                actor=Actor.CONNECTOR,
                conversation_id=conversation.id,
                contact_id=contact.id,
                message_id=result.message.id,
                occurred_at=now,
                details={
                    "platform": connection.platform,
                    "event_id": str(event.event_id),
                    "attachment_count": payload.attachment_count or 0,
                },
            )
            await self.dispatcher.observe_inbound(connection, event)
        return result.message, result.created

    # ------------------------------------------------------------------ connection

    async def _connection_event(
        self,
        session: AsyncSession,
        scope: UserScope,
        connection: PlatformConnection,
        event: ConnectorEvent,
    ) -> None:
        connections = ConnectionRepository(session, scope)
        payload = event.payload
        if isinstance(payload, CapabilitiesChangedPayload):
            caps: ConnectorCapabilities = payload.capabilities
            if caps.platform != connection.platform:
                raise IngestError(
                    422,
                    "capabilities platform does not match the connection",
                    pointer="/payload/capabilities/platform",
                    message="must equal the connection's platform",
                )
            # A device's report is a claim (ADR 0007): never raised to VERIFIED here.
            await connections.set_capabilities(
                connection.id, trust_claimed_capabilities(caps, connection.capabilities)
            )
        elif isinstance(payload, ConnectionStatusPayload):
            await self._status_event(
                session, scope, connection, ConnectionStatus(payload.status.value)
            )

    async def _status_event(
        self,
        session: AsyncSession,
        scope: UserScope,
        connection: PlatformConnection,
        status: ConnectionStatus,
    ) -> None:
        """Store the reported status (D-030). The user lock (taken first, global lock
        order) serializes the change with claims and with the dispatcher's re-read of
        the connection, so no instruction is created or leased against a stale status.
        Nothing is canceled: held work stays queued (or ``pending``) and resumes on
        ``connected``; a lease already handed out follows ADR 0006."""
        await UserRepository(session).lock(scope)
        connections = ConnectionRepository(session, scope)
        current = await connections.get_fresh(connection.id)
        if current is None:  # pragma: no cover - deleted concurrently
            raise LookupError("connection vanished during ingest")
        before = current.status
        await connections.set_status(connection.id, status)
        if status in HOLD_STATUSES and before not in HOLD_STATUSES:
            messages = MessageRepository(session, scope)
            queued = await messages.outbound_for_connection(
                connection.id, states=(DeliveryState.QUEUED,)
            )
            in_flight = await messages.outbound_for_connection(
                connection.id, states=(DeliveryState.SENDING,)
            )
            await AgentActionRepository(session, scope).record(
                type=AgentActionType.POLICY_BLOCKED,
                actor=Actor.CONNECTOR,
                reason_code=HOLD_REASON,
                occurred_at=self._now(),
                details={
                    "hold": "started",
                    "connection_id": str(connection.id),
                    "platform": connection.platform,
                    "connection_status": status.value,
                    "held_queued_messages": len(queued),
                    "in_flight_messages": len(in_flight),
                },
            )

    # ------------------------------------------------------------------ send outcomes

    async def _send_outcome(
        self,
        session: AsyncSession,
        scope: UserScope,
        connection: PlatformConnection,
        event: ConnectorEvent,
        record_id: uuid.UUID,
    ) -> uuid.UUID:
        payload = event.payload
        key: str | None
        if isinstance(payload, SendAcknowledgedPayload | SendFailedPayload):
            key = payload.idempotency_key
            key_pointer = "/payload/idempotency_key"
        elif isinstance(payload, DeliveryReceiptPayload):
            key = None
            key_pointer = "/payload/outbound_message_id"
        else:  # pragma: no cover - every other type is handled before
            raise IngestError(422, "unsupported event type", pointer="/type")
        # Lock order (user -> contact -> instruction -> message), as pause/takeover/claim.
        await UserRepository(session).lock(scope, shared=True)
        outbox = OutboxRepository(session, scope)
        instruction = await outbox.find_leased(
            connection_id=connection.id,
            outbound_message_id=payload.outbound_message_id,
            idempotency_key=key,
        )
        if instruction is None:
            raise IngestError(
                422,
                "send result does not match a send instruction leased on this connection",
                pointer=key_pointer,
                message="no leased outbox instruction with this outbound_message_id"
                + (" and idempotency_key" if key is not None else ""),
            )
        messages = MessageRepository(session, scope)
        message = await messages.lock(instruction.outbound_message_id)
        if message is None:  # pragma: no cover - FK cascade
            raise LookupError("outbound message vanished")

        if event.type in DELIVERY_RECEIPT_TYPES:
            if message.sent_at is None:
                # Early receipt: keep it; the acknowledgement applies it.
                await EventIngestRepository(session, scope).annotate(record_id, deferred=True)
            else:
                await self.dispatcher.mark_delivered(session, scope, message, event.occurred_at)
            return message.id

        if instruction.state is not OutboxInstructionState.COMPLETED:
            await outbox.set_state(
                instruction.id, state=OutboxInstructionState.COMPLETED, completed_at=self._now()
            )
        is_current = instruction.attempt == message.attempt_count
        if event.type in SEND_ACKNOWLEDGED_TYPES:
            if is_current:
                await self.dispatcher.mark_sent(
                    session,
                    scope,
                    message,
                    external_message_id=event.external_message_id,
                    occurred_at=event.occurred_at,
                    attempt=instruction.attempt,
                )
            return message.id
        if event.type in SEND_FAILED_TYPES and is_current:
            assert isinstance(payload, SendFailedPayload)  # noqa: S101
            result = await self.dispatcher.record_failure(
                session,
                scope,
                message,
                attempt=instruction.attempt,
                code=payload.failure.code.value,
                detail=payload.failure.detail,
                retryable=payload.failure.retryable,
                key=instruction.idempotency_key,
            )
            if result.outcome.value == "retry_scheduled":
                # A new instruction (attempt + 1, new key) once the backoff has passed.
                await self.dispatcher.dispatch(session, scope, message.id)
        return message.id
