"""`MockConnector`: a deterministic, in-memory messaging simulator (platform `mock`).

- Inbound messages are injected by the test harness and emitted as
  `mock.inbound_message_injected`, deduplicated on (user, platform, external ID).
- Outbound sends are simulated: each emits `mock.outbound_send_acknowledged` or
  `mock.outbound_send_failed`; delivery receipts are a separate
  `mock.delivery_receipt_simulated` event. It never emits live `message.*` types and
  never claims any real platform.
- Exactly one transport attempt per idempotency key, whatever its outcome. A repeated
  key returns the stored result with `replayed=True` (a reused key with a different
  request raises `IdempotencyKeyConflict`). To retry after a retryable failure, the
  dispatcher uses a new key per attempt, e.g. `send:<message_id>:<attempt>`.
- Failures can be injected (`inject_send_failures`) for retry/dead-letter tests.

IDs are deterministic (UUIDv5 from the connection ID plus a counter). Timestamps
(`occurred_at`/`observed_at`, and so the `sent_at`/`delivered_at` the dispatcher records
from them) come from wall-clock UTC by default (I-021), so a dev run never shows a send
in the past. Tests that need reproducible timestamps inject a clock, e.g.
`SimulatedClock()` (starts 2026-01-01, ticks one second per event).
"""

from __future__ import annotations

import asyncio
import base64
import binascii
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid5

from app.connectors.base import BaseConnector
from app.connectors.capabilities import (
    Capability,
    CapabilitySet,
    ConnectorCapabilities,
)
from app.connectors.dedup import DedupOutcome, InboundDeduplicator
from app.connectors.errors import ConnectorError, IdempotencyKeyConflict, InvalidCursor
from app.connectors.events import (
    MOCK_PLATFORM,
    ConnectorEvent,
    ConnectorEventType,
    DeliveryReceiptPayload,
    InboundMessagePayload,
    MockBlock,
    SendAcknowledgedPayload,
    Sender,
    SendFailedPayload,
    SendFailure,
    SendFailureCode,
)
from app.connectors.models import (
    ConnectorContact,
    ConnectorConversation,
    ConnectorMessage,
    MessageDirection,
    Page,
    SendRequest,
    SendResult,
    SendStatus,
)

MOCK_CONNECTOR_VERSION = "0.1.0"
MOCK_EVIDENCE = "backend/tests/connectors (deterministic simulator)"
MOCK_VERIFIED_AT = datetime(2026, 11, 1, tzinfo=UTC)
DEFAULT_START_TIME = datetime(2026, 1, 1, tzinfo=UTC)
_ID_NAMESPACE = UUID("6f2b8d0e-3c51-5a7e-9a43-1d0f5c2b7e90")


def default_mock_capabilities(*, assessed_at: datetime = MOCK_VERIFIED_AT) -> ConnectorCapabilities:
    """`read` and `send` VERIFIED against the simulator only; everything else UNSUPPORTED."""
    return ConnectorCapabilities(
        platform=MOCK_PLATFORM,
        connector_version=MOCK_CONNECTOR_VERSION,
        capabilities=CapabilitySet(
            read=Capability.verified(
                evidence=MOCK_EVIDENCE,
                verified_at=MOCK_VERIFIED_AT,
                notes="Simulated transport, not a real platform.",
            ),
            draft=Capability.unsupported(),
            send=Capability.verified(
                evidence=MOCK_EVIDENCE,
                verified_at=MOCK_VERIFIED_AT,
                reports_delivery_receipts=True,
                notes="Simulated transport. VERIFIED means the simulator contract is "
                "tested, not a real platform.",
            ),
            swipe=Capability.unsupported(),
            calendar_write=Capability.unsupported(),
        ),
        assessed_at=assessed_at,
    )


def wall_clock() -> datetime:
    """Default `MockConnector` clock: the current UTC time."""
    return datetime.now(UTC)


class SimulatedClock:
    """Deterministic clock: returns `start`, then advances by `step` on every call.
    Inject it (`MockConnector(clock=SimulatedClock())`) for reproducible timestamps."""

    def __init__(
        self, start: datetime = DEFAULT_START_TIME, step: timedelta = timedelta(seconds=1)
    ) -> None:
        self._next = start.astimezone(UTC)
        self._step = step

    def __call__(self) -> datetime:
        now = self._next
        self._next = now + self._step
        return now


class EventScopeMismatch(ConnectorError):  # noqa: N818
    """An event's user, connection or platform doesn't match this connector (boundary B1)."""

    code = "validation_error"


@dataclass(frozen=True)
class InjectResult:
    """Outcome of injecting an inbound message."""

    event: ConnectorEvent
    outcome: DedupOutcome

    @property
    def duplicate(self) -> bool:
        return self.outcome.is_duplicate


@dataclass
class _FailurePlan:
    remaining: int | None  # None = until cleared
    failure: SendFailure


@dataclass
class _SendRecord:
    fingerprint: tuple[UUID, str, str]
    result: SendResult


@dataclass
class _Thread:
    external_thread_id: str
    participants: dict[str, ConnectorContact]
    messages: list[ConnectorMessage]
    last_activity_at: datetime


class MockConnector(BaseConnector):
    """Deterministic simulator for one (user, connection) on platform `mock`."""

    def __init__(
        self,
        *,
        user_id: UUID,
        connection_id: UUID,
        scenario_id: str = "default",
        capabilities: ConnectorCapabilities | None = None,
        clock: Callable[[], datetime] | None = None,
        deduplicator: InboundDeduplicator | None = None,
        simulated_latency_ms: int = 0,
        auto_delivery_receipts: bool = False,
    ) -> None:
        super().__init__()
        caps = capabilities if capabilities is not None else default_mock_capabilities()
        if caps.platform != MOCK_PLATFORM:
            raise ValueError("MockConnector may only advertise platform 'mock'")
        self.user_id = user_id
        self.connection_id = connection_id
        self.scenario_id = scenario_id
        self._capabilities = caps
        # Wall-clock UTC unless a test injects one (I-021: no fixed simulated date).
        self._clock: Callable[[], datetime] = clock if clock is not None else wall_clock
        self._dedup = deduplicator if deduplicator is not None else InboundDeduplicator()
        self._latency_ms = simulated_latency_ms
        self._auto_receipts = auto_delivery_receipts

        self._sequence = 0
        self._id_counter = 0
        self._inbound_counter = 0
        self._outbound_counter = 0
        self._threads: dict[str, _Thread] = {}
        self._sends: dict[str, _SendRecord] = {}
        self._send_locks: dict[str, asyncio.Lock] = {}
        self._failures: list[_FailurePlan] = []
        self._receipted: set[UUID] = set()
        self.transport_log: list[SendRequest] = []
        """Every transport attempt actually made, in order (the simulator's 'wire')."""

    # ------------------------------------------------------------------ basics

    def capabilities(self) -> ConnectorCapabilities:
        return self._capabilities

    def set_capabilities(self, capabilities: ConnectorCapabilities) -> None:
        if capabilities.platform != MOCK_PLATFORM:
            raise ValueError("MockConnector may only advertise platform 'mock'")
        self._capabilities = capabilities

    @property
    def events(self) -> list[ConnectorEvent]:
        """Every event emitted so far, oldest first."""
        return self._bus.history

    def _next_id(self, kind: str) -> UUID:
        self._id_counter += 1
        return uuid5(_ID_NAMESPACE, f"{self.connection_id}:{kind}:{self._id_counter}")

    def _mock_block(self, *, latency: bool = False) -> MockBlock:
        self._sequence += 1
        return MockBlock(
            scenario_id=self.scenario_id,
            sequence=self._sequence,
            simulated_latency_ms=self._latency_ms if latency else None,
        )

    def _event(
        self,
        event_type: ConnectorEventType,
        *,
        external_message_id: str | None,
        external_thread_id: str | None,
        payload: InboundMessagePayload
        | SendAcknowledgedPayload
        | SendFailedPayload
        | DeliveryReceiptPayload,
        occurred_at: datetime | None = None,
        event_id: UUID | None = None,
    ) -> ConnectorEvent:
        now = self._clock()
        return ConnectorEvent(
            event_id=event_id if event_id is not None else self._next_id("event"),
            type=event_type,
            platform=MOCK_PLATFORM,
            connection_id=self.connection_id,
            user_id=self.user_id,
            external_message_id=external_message_id,
            external_thread_id=external_thread_id,
            occurred_at=occurred_at if occurred_at is not None else now,
            observed_at=now,
            payload=payload,
        )

    # ------------------------------------------------------------------ inbound

    async def inject_inbound(
        self,
        *,
        external_thread_id: str,
        sender_id: str,
        text: str,
        display_name: str | None = None,
        external_message_id: str | None = None,
        occurred_at: datetime | None = None,
    ) -> InjectResult:
        """Simulate the contact sending us a message.

        Pass the same `external_message_id` twice to simulate a platform re-delivery:
        the second call returns a duplicate outcome and emits/stores nothing.
        """
        if external_message_id is None:
            self._inbound_counter += 1
            external_message_id = f"mock-msg-in-{self._inbound_counter:04d}"
        event = self._event(
            ConnectorEventType.MOCK_INBOUND_MESSAGE_INJECTED,
            external_message_id=external_message_id,
            external_thread_id=external_thread_id,
            payload=InboundMessagePayload(
                sender=Sender(external_contact_id=sender_id, display_name=display_name),
                text=text,
                attachment_count=0,
                mock=self._mock_block(),
            ),
            occurred_at=occurred_at,
        )
        return await self.ingest(event)

    async def ingest(self, event: ConnectorEvent) -> InjectResult:
        """Ingest a ready-made inbound event (e.g. a replayed envelope).

        Rejects events for another user, connection or platform. Duplicates (same
        `event_id`, or same (user, platform, external_message_id)) are recognised and
        dropped: not stored, not re-emitted.
        """
        if event.type is not ConnectorEventType.MOCK_INBOUND_MESSAGE_INJECTED:
            raise EventScopeMismatch(f"MockConnector cannot ingest {event.type} events")
        if (
            event.user_id != self.user_id
            or event.connection_id != self.connection_id
            or event.platform != MOCK_PLATFORM
        ):
            raise EventScopeMismatch("event does not belong to this user's mock connection")
        if event.external_thread_id is None or event.external_message_id is None:
            raise EventScopeMismatch("mock inbound events need a thread and message ID")
        payload = event.payload
        assert isinstance(payload, InboundMessagePayload)  # noqa: S101 - guaranteed by model

        outcome = await self._dedup.check(event)
        if outcome.is_duplicate:
            return InjectResult(event=event, outcome=outcome)

        contact = ConnectorContact(
            external_contact_id=payload.sender.external_contact_id,
            display_name=payload.sender.display_name,
        )
        thread = self._thread(event.external_thread_id, event.occurred_at)
        thread.participants[contact.external_contact_id] = contact
        thread.messages.append(
            ConnectorMessage(
                external_message_id=event.external_message_id,
                external_thread_id=event.external_thread_id,
                direction=MessageDirection.INBOUND,
                sender=contact,
                text=payload.text,
                occurred_at=event.occurred_at,
            )
        )
        thread.last_activity_at = max(thread.last_activity_at, event.occurred_at)
        self._bus.publish(event)
        return InjectResult(event=event, outcome=outcome)

    def _thread(self, external_thread_id: str, at: datetime) -> _Thread:
        thread = self._threads.get(external_thread_id)
        if thread is None:
            thread = _Thread(external_thread_id, {}, [], at)
            self._threads[external_thread_id] = thread
        return thread

    # ------------------------------------------------------------------ reads

    async def _do_list_conversations(
        self, *, cursor: str | None, limit: int
    ) -> Page[ConnectorConversation]:
        threads = sorted(
            self._threads.values(),
            key=lambda t: (t.last_activity_at, t.external_thread_id),
            reverse=True,
        )
        items = [
            ConnectorConversation(
                external_thread_id=t.external_thread_id,
                participants=list(t.participants.values()),
                last_activity_at=t.last_activity_at,
            )
            for t in threads
        ]
        return _paginate(items, cursor=cursor, limit=limit, scope="conversations")

    async def _do_read_messages(
        self, external_thread_id: str, *, cursor: str | None, limit: int
    ) -> Page[ConnectorMessage]:
        thread = self._threads.get(external_thread_id)
        messages = list(thread.messages) if thread is not None else []
        return _paginate(messages, cursor=cursor, limit=limit, scope=f"t:{external_thread_id}")

    # ------------------------------------------------------------------ failure injection

    def inject_send_failures(
        self,
        count: int | None = 1,
        *,
        code: SendFailureCode = SendFailureCode.TRANSPORT_ERROR,
        retryable: bool = True,
        detail: str | None = "simulated failure",
    ) -> None:
        """Make the next `count` transport attempts fail (`None` = until cleared)."""
        if count is not None and count < 1:
            raise ValueError("count must be >= 1 or None")
        self._failures.append(
            _FailurePlan(count, SendFailure(code=code, detail=detail, retryable=retryable))
        )

    def clear_send_failures(self) -> None:
        self._failures.clear()

    def _take_failure(self) -> SendFailure | None:
        while self._failures:
            plan = self._failures[0]
            if plan.remaining is None:
                return plan.failure
            if plan.remaining > 0:
                plan.remaining -= 1
                if plan.remaining == 0:
                    self._failures.pop(0)
                return plan.failure
            self._failures.pop(0)
        return None

    # ------------------------------------------------------------------ send

    def send_count(self, idempotency_key: str) -> int:
        """Transport attempts actually made for a key (0 or 1)."""
        return sum(1 for r in self.transport_log if r.idempotency_key == idempotency_key)

    async def _do_send_message(self, request: SendRequest) -> SendResult:
        key = request.idempotency_key
        lock = self._send_locks.setdefault(key, asyncio.Lock())
        async with lock:
            existing = self._sends.get(key)
            if existing is not None:
                if existing.fingerprint != request.fingerprint():
                    raise IdempotencyKeyConflict(key)
                return existing.result.model_copy(update={"replayed": True})

            # Yield while holding the key's lock, like a real transport round trip,
            # so concurrent duplicates genuinely contend for it.
            await asyncio.sleep(0)
            result = self._transmit(request)
            self._sends[key] = _SendRecord(request.fingerprint(), result)

        if result.acknowledged and self._auto_receipts:
            await self.simulate_delivery_receipt(request.outbound_message_id)
        return result

    def _transmit(self, request: SendRequest) -> SendResult:
        self.transport_log.append(request)
        thread = self._threads.get(request.external_thread_id)
        failure = self._take_failure()
        if failure is None and thread is None:
            failure = SendFailure(
                code=SendFailureCode.REJECTED_BY_PLATFORM,
                detail="unknown mock thread",
                retryable=False,
            )

        if failure is not None:
            event = self._event(
                ConnectorEventType.MOCK_OUTBOUND_SEND_FAILED,
                external_message_id=None,
                external_thread_id=request.external_thread_id,
                payload=SendFailedPayload(
                    outbound_message_id=request.outbound_message_id,
                    idempotency_key=request.idempotency_key,
                    failure=failure,
                    mock=self._mock_block(latency=True),
                ),
            )
            self._bus.publish(event)
            return SendResult(
                status=SendStatus.FAILED,
                outbound_message_id=request.outbound_message_id,
                idempotency_key=request.idempotency_key,
                failure=failure,
                event=event,
            )

        assert thread is not None  # noqa: S101 - unknown thread handled above
        self._outbound_counter += 1
        # Derived from the per-attempt key (D-006), not from a per-instance counter: the
        # backend stores it under a unique (user_id, platform, external_message_id), and
        # a counter restarts at 1 for every MockConnector (a second mock connection of the
        # same user, or any process restart), which collided with an earlier send and left
        # the message stuck (WING-025). Like SMS (`sms:out:<key>`), one ID per attempt.
        external_message_id = f"mock-msg-out:{request.idempotency_key}"
        event = self._event(
            ConnectorEventType.MOCK_OUTBOUND_SEND_ACKNOWLEDGED,
            external_message_id=external_message_id,
            external_thread_id=request.external_thread_id,
            payload=SendAcknowledgedPayload(
                outbound_message_id=request.outbound_message_id,
                idempotency_key=request.idempotency_key,
                mock=self._mock_block(latency=True),
            ),
        )
        thread.messages.append(
            ConnectorMessage(
                external_message_id=external_message_id,
                external_thread_id=request.external_thread_id,
                direction=MessageDirection.OUTBOUND,
                text=request.text,
                occurred_at=event.occurred_at,
                outbound_message_id=request.outbound_message_id,
            )
        )
        thread.last_activity_at = max(thread.last_activity_at, event.occurred_at)
        self._bus.publish(event)
        return SendResult(
            status=SendStatus.ACKNOWLEDGED,
            outbound_message_id=request.outbound_message_id,
            idempotency_key=request.idempotency_key,
            external_message_id=external_message_id,
            event=event,
        )

    async def simulate_delivery_receipt(self, outbound_message_id: UUID) -> ConnectorEvent:
        """Emit `mock.delivery_receipt_simulated` for an acknowledged send (once).

        Raises `ValueError` if the message was never acknowledged, so a receipt can't
        be simulated for a failed or unknown send. Calling it again returns the
        original receipt event.
        """
        if not self._capabilities.capabilities.send.reports_delivery_receipts:
            raise ValueError("this mock configuration does not report delivery receipts")
        ack = next(
            (
                r.result
                for r in self._sends.values()
                if r.result.outbound_message_id == outbound_message_id and r.result.acknowledged
            ),
            None,
        )
        if ack is None or ack.external_message_id is None:
            raise ValueError("no acknowledged send for this outbound_message_id")
        if outbound_message_id in self._receipted:
            return next(
                e
                for e in self.events
                if e.type is ConnectorEventType.MOCK_DELIVERY_RECEIPT_SIMULATED
                and isinstance(e.payload, DeliveryReceiptPayload)
                and e.payload.outbound_message_id == outbound_message_id
            )
        self._receipted.add(outbound_message_id)
        event = self._event(
            ConnectorEventType.MOCK_DELIVERY_RECEIPT_SIMULATED,
            external_message_id=ack.external_message_id,
            external_thread_id=ack.event.external_thread_id,
            payload=DeliveryReceiptPayload(
                outbound_message_id=outbound_message_id, mock=self._mock_block()
            ),
        )
        self._bus.publish(event)
        return event


# ---------------------------------------------------------------------- pagination


def _encode_cursor(scope: str, offset: int) -> str:
    raw = f"{scope}|{offset}".encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _decode_cursor(cursor: str, scope: str) -> int:
    try:
        padded = cursor + "=" * (-len(cursor) % 4)
        raw = base64.urlsafe_b64decode(padded.encode()).decode()
        cursor_scope, _, offset_text = raw.rpartition("|")
        offset = int(offset_text)
    except (binascii.Error, UnicodeDecodeError, ValueError) as exc:
        raise InvalidCursor("invalid cursor") from exc
    if cursor_scope != scope or offset < 0:
        raise InvalidCursor("cursor does not belong to this listing")
    return offset


def _paginate[T](items: list[T], *, cursor: str | None, limit: int, scope: str) -> Page[T]:
    if not 1 <= limit <= 100:
        raise ValueError("limit must be between 1 and 100")
    start = _decode_cursor(cursor, scope) if cursor is not None else 0
    end = start + limit
    next_cursor = _encode_cursor(scope, end) if end < len(items) else None
    return Page[T](items=items[start:end], next_cursor=next_cursor)
