"""`ConnectorEvent`: mirrors `contracts/schemas/connector_event.v1.json`.

The envelope validator enforces the schema's conditional rules, so a model instance
can never hold an event the contract would reject:

- `mock.*` types only with `platform == "mock"`, and live `message.*` types never
  with `platform == "mock"` (simulated success can't pass as a real one);
- the payload shape is chosen by `type`, and `payload.mock` is present exactly on
  `mock.*` events;
- `external_message_id` is required for received / acknowledged / receipt events
  and must be null for connection events.

`payload.text` on inbound events is private: never log it.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Any, ClassVar, Literal, NamedTuple
from uuid import UUID

from pydantic import Field, StringConstraints, ValidationInfo, field_validator, model_validator

from app.connectors._types import (
    ContractModel,
    ExternalId,
    IdempotencyKey,
    PlatformKey,
    UtcTimestamp,
    WingmanId,
)
from app.connectors.capabilities import ConnectorCapabilities

EVENT_SCHEMA_VERSION: Literal["connector_event.v1"] = "connector_event.v1"
MOCK_PLATFORM = "mock"


class ConnectorEventType(StrEnum):
    MESSAGE_RECEIVED = "message.received"
    MESSAGE_SEND_ACKNOWLEDGED = "message.send_acknowledged"
    MESSAGE_SEND_FAILED = "message.send_failed"
    MESSAGE_DELIVERY_RECEIPT = "message.delivery_receipt"
    CONNECTION_STATUS_CHANGED = "connection.status_changed"
    CONNECTION_CAPABILITIES_CHANGED = "connection.capabilities_changed"
    MOCK_INBOUND_MESSAGE_INJECTED = "mock.inbound_message_injected"
    MOCK_OUTBOUND_SEND_ACKNOWLEDGED = "mock.outbound_send_acknowledged"
    MOCK_OUTBOUND_SEND_FAILED = "mock.outbound_send_failed"
    MOCK_DELIVERY_RECEIPT_SIMULATED = "mock.delivery_receipt_simulated"

    @property
    def is_mock(self) -> bool:
        return self.value.startswith("mock.")

    @property
    def is_inbound_message(self) -> bool:
        return self in INBOUND_MESSAGE_TYPES


INBOUND_MESSAGE_TYPES = frozenset(
    {ConnectorEventType.MESSAGE_RECEIVED, ConnectorEventType.MOCK_INBOUND_MESSAGE_INJECTED}
)
SEND_ACKNOWLEDGED_TYPES = frozenset(
    {
        ConnectorEventType.MESSAGE_SEND_ACKNOWLEDGED,
        ConnectorEventType.MOCK_OUTBOUND_SEND_ACKNOWLEDGED,
    }
)
SEND_FAILED_TYPES = frozenset(
    {ConnectorEventType.MESSAGE_SEND_FAILED, ConnectorEventType.MOCK_OUTBOUND_SEND_FAILED}
)
DELIVERY_RECEIPT_TYPES = frozenset(
    {
        ConnectorEventType.MESSAGE_DELIVERY_RECEIPT,
        ConnectorEventType.MOCK_DELIVERY_RECEIPT_SIMULATED,
    }
)
CONNECTION_TYPES = frozenset(
    {
        ConnectorEventType.CONNECTION_STATUS_CHANGED,
        ConnectorEventType.CONNECTION_CAPABILITIES_CHANGED,
    }
)


class SendFailureCode(StrEnum):
    CAPABILITY_UNSUPPORTED = "capability_unsupported"
    TRANSPORT_ERROR = "transport_error"
    TIMEOUT = "timeout"
    REJECTED_BY_PLATFORM = "rejected_by_platform"
    NOT_CONNECTED = "not_connected"
    PERMISSION_DENIED = "permission_denied"


class ConnectionStatus(StrEnum):
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"
    ERROR = "error"


# ------------------------------------------------------------------ payload parts


class MockBlock(ContractModel):
    """Simulation metadata, present only on `mock.*` events."""

    _omit_if_none: ClassVar[frozenset[str]] = frozenset({"simulated_latency_ms"})

    scenario_id: Annotated[str, StringConstraints(min_length=1, max_length=128)]
    sequence: Annotated[int, Field(ge=0)]
    simulated_latency_ms: Annotated[int, Field(ge=0)] | None = None


class Sender(ContractModel):
    external_contact_id: ExternalId
    display_name: Annotated[str, StringConstraints(max_length=200)] | None = None


class SendFailure(ContractModel):
    code: SendFailureCode
    detail: Annotated[str, StringConstraints(max_length=500)] | None = None
    retryable: bool


class _Payload(ContractModel):
    _omit_if_none: ClassVar[frozenset[str]] = frozenset({"mock"})

    mock: MockBlock | None = None


class InboundKind(StrEnum):
    """Contract 1.3.0 ``kind``: only ``chat_message`` can enter the pipeline."""

    CHAT_MESSAGE = "chat_message"
    MATCH = "match"
    LIKE_COMMENT = "like_comment"
    EXPIRY = "expiry"
    SYSTEM = "system"
    UNKNOWN = "unknown"


class Identity(StrEnum):
    LINKED = "linked"
    UNLINKED = "unlinked"
    AMBIGUOUS = "ambiguous"


class EventSource(StrEnum):
    SMS_PROVIDER = "sms_provider"
    NOTIFICATION = "notification"
    COMPANION = "companion"
    UNOFFICIAL_API = "unofficial_api"


class InboundMessagePayload(_Payload):
    """Inbound message or (contract 1.3.0) notice. The 1.3.0 fields are optional and
    default to the 1.2.0 meaning: a linked, complete ``chat_message``."""

    _omit_if_none: ClassVar[frozenset[str]] = frozenset(
        {
            "mock",
            "attachment_count",
            "kind",
            "identity",
            "truncated",
            "content_hidden",
            "source",
            "external_conversation_id",
            "sender_label",
        }
    )

    sender: Sender
    text: Annotated[str, StringConstraints(max_length=20000)]
    attachment_count: Annotated[int, Field(ge=0)] | None = None
    kind: InboundKind | None = None
    external_conversation_id: ExternalId | None = None
    sender_label: Annotated[str, StringConstraints(max_length=200)] | None = None
    identity: Identity | None = None
    truncated: bool | None = None
    content_hidden: bool | None = None
    source: EventSource | None = None

    @model_validator(mode="after")
    def _hidden_has_no_text(self) -> InboundMessagePayload:
        if self.content_hidden and self.text:
            raise ValueError("content_hidden events carry no text")
        return self

    @property
    def effective_kind(self) -> InboundKind:
        return self.kind or InboundKind.CHAT_MESSAGE

    @property
    def effective_identity(self) -> Identity:
        return self.identity or Identity.LINKED

    @property
    def enters_pipeline(self) -> bool:
        """ADR 0008: only a linked, complete chat message becomes a ``Message``."""
        return (
            self.effective_kind is InboundKind.CHAT_MESSAGE
            and self.effective_identity is Identity.LINKED
            and not self.truncated
            and not self.content_hidden
        )

    def __repr_args__(self) -> Any:
        # Keep private text out of reprs (and therefore out of accidental logs).
        for name, value in super().__repr_args__():
            yield name, ("<redacted>" if name == "text" else value)


class SendAcknowledgedPayload(_Payload):
    outbound_message_id: WingmanId
    idempotency_key: IdempotencyKey


class SendFailedPayload(_Payload):
    outbound_message_id: WingmanId
    idempotency_key: IdempotencyKey
    failure: SendFailure


class DeliveryReceiptPayload(_Payload):
    outbound_message_id: WingmanId


class ConnectionStatusPayload(ContractModel):
    status: ConnectionStatus
    detail: Annotated[str, StringConstraints(max_length=500)] | None = None


class CapabilitiesChangedPayload(ContractModel):
    capabilities: ConnectorCapabilities


EventPayload = (
    InboundMessagePayload
    | SendAcknowledgedPayload
    | SendFailedPayload
    | DeliveryReceiptPayload
    | ConnectionStatusPayload
    | CapabilitiesChangedPayload
)


def payload_class_for(event_type: ConnectorEventType) -> type[EventPayload]:
    if event_type in INBOUND_MESSAGE_TYPES:
        return InboundMessagePayload
    if event_type in SEND_ACKNOWLEDGED_TYPES:
        return SendAcknowledgedPayload
    if event_type in SEND_FAILED_TYPES:
        return SendFailedPayload
    if event_type in DELIVERY_RECEIPT_TYPES:
        return DeliveryReceiptPayload
    if event_type is ConnectorEventType.CONNECTION_STATUS_CHANGED:
        return ConnectionStatusPayload
    return CapabilitiesChangedPayload


class InboundDedupKey(NamedTuple):
    """The contract's inbound dedup key: (user_id, platform, external_message_id)."""

    user_id: UUID
    platform: str
    external_message_id: str


# ------------------------------------------------------------------ envelope


class ConnectorEvent(ContractModel):
    """Versioned envelope for everything a connector reports (`connector_event.v1`)."""

    _omit_if_none: ClassVar[frozenset[str]] = frozenset({"observed_at"})

    schema_version: Literal["connector_event.v1"] = EVENT_SCHEMA_VERSION
    event_id: WingmanId
    type: ConnectorEventType
    platform: PlatformKey
    connection_id: WingmanId
    user_id: WingmanId
    external_message_id: ExternalId | None
    external_thread_id: ExternalId | None = None
    occurred_at: UtcTimestamp
    observed_at: UtcTimestamp | None = None
    payload: EventPayload

    @field_validator("payload", mode="before")
    @classmethod
    def _payload_by_type(cls, value: Any, info: ValidationInfo) -> Any:
        event_type = info.data.get("type")
        if not isinstance(event_type, ConnectorEventType):
            raise ValueError("payload cannot be validated without a valid event type")
        expected = payload_class_for(event_type)
        if isinstance(value, expected):
            return value
        if isinstance(value, ContractModel):
            raise ValueError(f"payload {type(value).__name__} does not match type {event_type}")
        return expected.model_validate(value)

    @model_validator(mode="after")
    def _contract_rules(self) -> ConnectorEvent:
        is_mock_platform = self.platform == MOCK_PLATFORM
        if self.type.is_mock and not is_mock_platform:
            raise ValueError("mock.* event types are only valid with platform 'mock'")
        if self.type.value.startswith("message.") and is_mock_platform:
            raise ValueError("live message.* event types are never valid with platform 'mock'")

        has_mock_block = getattr(self.payload, "mock", None) is not None
        if self.type.is_mock and not has_mock_block:
            raise ValueError("mock.* events require payload.mock")
        if not self.type.is_mock and has_mock_block:
            raise ValueError("payload.mock is only allowed on mock.* events")

        needs_external_id = (
            self.type in INBOUND_MESSAGE_TYPES
            or self.type in SEND_ACKNOWLEDGED_TYPES
            or self.type in DELIVERY_RECEIPT_TYPES
        )
        if needs_external_id and self.external_message_id is None:
            raise ValueError(f"{self.type} requires external_message_id")
        if self.type in CONNECTION_TYPES and self.external_message_id is not None:
            raise ValueError(f"{self.type} requires external_message_id to be null")
        return self

    @property
    def dedup_key(self) -> InboundDedupKey | None:
        """Inbound dedup key, or None for events that aren't inbound messages."""
        if self.type not in INBOUND_MESSAGE_TYPES or self.external_message_id is None:
            return None
        return InboundDedupKey(self.user_id, self.platform, self.external_message_id)
