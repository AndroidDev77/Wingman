"""Messaging connector SDK (WING-006).

Public surface for other tickets:

- `Connector` (protocol) / `BaseConnector` (capability-enforcing base class)
- `ConnectorCapabilities`, `Capability`, `CapabilitySet`, `CapabilityName`,
  `VerificationStatus`  (mirror `contracts/schemas/connector_capabilities.v1.json`)
- `ConnectorEvent`, `ConnectorEventType` and payload models
  (mirror `contracts/schemas/connector_event.v1.json`)
- `SendRequest`, `SendResult`, `SendStatus`, `ConnectorMessage`, `ConnectorConversation`
- `UnsupportedCapability`, `IdempotencyKeyConflict`, `ConnectorError`
- `DedupStore`, `InMemoryDedupStore`, `InboundDeduplicator`, `DedupOutcome`
- `MockConnector` (platform `mock` only)

No module in this package talks to a real dating platform.
"""

from app.connectors.base import BaseConnector, Connector
from app.connectors.bus import EventBus, EventSubscription
from app.connectors.capabilities import (
    Capability,
    CapabilityName,
    CapabilitySet,
    ConnectorCapabilities,
    VerificationStatus,
)
from app.connectors.dedup import (
    DedupOutcome,
    DedupStore,
    InboundDeduplicator,
    InMemoryDedupStore,
)
from app.connectors.errors import (
    ConnectorError,
    IdempotencyKeyConflict,
    InvalidCursor,
    UnsupportedCapability,
)
from app.connectors.events import (
    CapabilitiesChangedPayload,
    ConnectionStatus,
    ConnectionStatusPayload,
    ConnectorEvent,
    ConnectorEventType,
    DeliveryReceiptPayload,
    InboundDedupKey,
    InboundMessagePayload,
    MockBlock,
    SendAcknowledgedPayload,
    Sender,
    SendFailedPayload,
    SendFailure,
    SendFailureCode,
)
from app.connectors.mock import (
    EventScopeMismatch,
    InjectResult,
    MockConnector,
    SimulatedClock,
    default_mock_capabilities,
)
from app.connectors.models import (
    ConnectorContact,
    ConnectorConversation,
    ConnectorMessage,
    ConnectorProfile,
    MessageDirection,
    Page,
    SendRequest,
    SendResult,
    SendStatus,
    SwipeDecision,
    SwipeRequest,
    SwipeResult,
)

__all__ = [
    "BaseConnector",
    "CapabilitiesChangedPayload",
    "Capability",
    "CapabilityName",
    "CapabilitySet",
    "ConnectionStatus",
    "ConnectionStatusPayload",
    "Connector",
    "ConnectorCapabilities",
    "ConnectorContact",
    "ConnectorConversation",
    "ConnectorError",
    "ConnectorEvent",
    "ConnectorEventType",
    "ConnectorMessage",
    "ConnectorProfile",
    "DedupOutcome",
    "DedupStore",
    "DeliveryReceiptPayload",
    "EventBus",
    "EventScopeMismatch",
    "EventSubscription",
    "IdempotencyKeyConflict",
    "InMemoryDedupStore",
    "InboundDedupKey",
    "InboundDeduplicator",
    "InboundMessagePayload",
    "InjectResult",
    "InvalidCursor",
    "MessageDirection",
    "MockBlock",
    "MockConnector",
    "Page",
    "SendAcknowledgedPayload",
    "SendFailedPayload",
    "SendFailure",
    "SendFailureCode",
    "SendRequest",
    "SendResult",
    "SendStatus",
    "Sender",
    "SimulatedClock",
    "SwipeDecision",
    "SwipeRequest",
    "SwipeResult",
    "UnsupportedCapability",
    "VerificationStatus",
    "default_mock_capabilities",
]
