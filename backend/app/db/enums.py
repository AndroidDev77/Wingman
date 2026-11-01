"""Domain enums persisted as PostgreSQL enum types.

Every enum here mirrors an enum in ``contracts/openapi.yaml`` (or
``contracts/schemas/connector_capabilities.v1.json``) value-for-value and in the
same order. ``tests/db/test_enum_drift.py`` loads the contract and fails if they
diverge, so a contract change must be accompanied by a migration.

``CONTRACT_ENUMS`` maps each Python enum to its Postgres type name and its
location in the contract; it drives both the models and the drift test.
"""

from dataclasses import dataclass
from enum import StrEnum


class IntentMode(StrEnum):
    SERIOUS = "serious"
    CASUAL_DATING = "casual_dating"
    CASUAL_PHYSICAL = "casual_physical"
    OPEN = "open"
    CUSTOM = "custom"


class AutomationMode(StrEnum):
    MANUAL = "manual"
    ASSISTED = "assisted"
    AUTOPILOT = "autopilot"


class ConversationStage(StrEnum):
    NEW = "new"
    INTRODUCTION = "introduction"
    RAPPORT = "rapport"
    INTEREST = "interest"
    DATE_PROPOSAL = "date_proposal"
    SCHEDULING = "scheduling"
    CONFIRMED = "confirmed"
    FOLLOW_UP = "follow_up"
    PAUSED = "paused"


class DateProposalState(StrEnum):
    SUGGESTED = "suggested"
    OFFERED = "offered"
    ACCEPTED = "accepted"
    CONFIRMED = "confirmed"
    CANCELED = "canceled"


class DeliveryState(StrEnum):
    RECEIVED = "received"
    QUEUED = "queued"
    SENDING = "sending"
    SENT = "sent"
    DELIVERED = "delivered"
    FAILED = "failed"
    DEAD_LETTERED = "dead_lettered"
    CANCELED = "canceled"


class MessageDirection(StrEnum):
    INBOUND = "inbound"
    OUTBOUND = "outbound"


class MessageOrigin(StrEnum):
    CONTACT = "contact"
    USER_MANUAL = "user_manual"
    APPROVED_DRAFT = "approved_draft"
    AUTOPILOT = "autopilot"


class ApprovalReason(StrEnum):
    ASSISTED_MODE = "assisted_mode"
    SENSITIVE_CONTENT = "sensitive_content"
    DATE_OR_COMMITMENT = "date_or_commitment"
    LOW_CONFIDENCE = "low_confidence"
    FIRST_MESSAGE = "first_message"
    OUTSIDE_ACTIVE_HOURS = "outside_active_hours"
    CAPABILITY_UNVERIFIED = "capability_unverified"
    SEND_LIMIT_REACHED = "send_limit_reached"
    USER_RULE = "user_rule"


class ContactAutomationStatus(StrEnum):
    ACTIVE = "active"
    PAUSED = "paused"
    TAKEN_OVER = "taken_over"


class GlobalAutomationStatus(StrEnum):
    ACTIVE = "active"
    PAUSED = "paused"


class Actor(StrEnum):
    USER = "user"
    AGENT = "agent"
    DISPATCHER = "dispatcher"
    CONNECTOR = "connector"
    SYSTEM = "system"


class AgentActionType(StrEnum):
    MESSAGE_RECEIVED = "message_received"
    ANALYSIS_UPDATED = "analysis_updated"
    DRAFT_GENERATED = "draft_generated"
    APPROVAL_REQUESTED = "approval_requested"
    APPROVAL_APPROVED = "approval_approved"
    APPROVAL_REJECTED = "approval_rejected"
    APPROVAL_EXPIRED = "approval_expired"
    APPROVAL_CANCELED = "approval_canceled"
    POLICY_BLOCKED = "policy_blocked"
    SEND_QUEUED = "send_queued"
    SEND_ATTEMPTED = "send_attempted"
    SEND_ACKNOWLEDGED = "send_acknowledged"
    DELIVERY_CONFIRMED = "delivery_confirmed"
    SEND_FAILED = "send_failed"
    SEND_DEAD_LETTERED = "send_dead_lettered"
    SEND_CANCELED = "send_canceled"
    AUTOMATION_PAUSED = "automation_paused"
    AUTOMATION_RESUMED = "automation_resumed"
    AUTOMATION_TAKEOVER = "automation_takeover"
    PREFERENCES_UPDATED = "preferences_updated"


class ConnectionStatus(StrEnum):
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"
    ERROR = "error"


class CapabilityVerification(StrEnum):
    VERIFIED = "VERIFIED"
    UNVERIFIED = "UNVERIFIED"
    UNSUPPORTED = "UNSUPPORTED"


# Style attributes (StyleProfile in the contract). Each is nullable there.
class Punctuation(StrEnum):
    MINIMAL = "minimal"
    STANDARD = "standard"
    EXPRESSIVE = "expressive"


class EmojiFrequency(StrEnum):
    NONE = "none"
    RARE = "rare"
    OCCASIONAL = "occasional"
    FREQUENT = "frequent"


class Brevity(StrEnum):
    VERY_SHORT = "very_short"
    SHORT = "short"
    MEDIUM = "medium"
    LONG = "long"


class Humor(StrEnum):
    NONE = "none"
    LIGHT = "light"
    PLAYFUL = "playful"
    WITTY = "witty"


class Tone(StrEnum):
    WARM = "warm"
    DIRECT = "direct"
    PLAYFUL = "playful"
    FLIRTY = "flirty"
    RESERVED = "reserved"
    FORMAL = "formal"


class ResponsePace(StrEnum):
    IMMEDIATE = "immediate"
    PROMPT = "prompt"
    RELAXED = "relaxed"
    SLOW = "slow"


@dataclass(frozen=True)
class ContractEnum:
    """Where a persisted enum comes from in ``contracts/``."""

    enum: type[StrEnum]
    pg_name: str
    # Path inside the contract document: ("openapi.yaml", "components", "schemas", ...).
    contract_path: tuple[str, ...]


def _schema(*path: str) -> tuple[str, ...]:
    return ("openapi.yaml", "components", "schemas", *path)


def _style(attr: str) -> tuple[str, ...]:
    return _schema("StyleProfile", "properties", attr)


CONTRACT_ENUMS: tuple[ContractEnum, ...] = (
    ContractEnum(IntentMode, "intent_mode", _schema("IntentMode")),
    ContractEnum(AutomationMode, "automation_mode", _schema("AutomationMode")),
    ContractEnum(ConversationStage, "conversation_stage", _schema("ConversationStage")),
    ContractEnum(DateProposalState, "date_proposal_state", _schema("DateProposalState")),
    ContractEnum(DeliveryState, "delivery_state", _schema("DeliveryState")),
    ContractEnum(MessageDirection, "message_direction", _schema("MessageDirection")),
    ContractEnum(MessageOrigin, "message_origin", _schema("MessageOrigin")),
    ContractEnum(ApprovalReason, "approval_reason", _schema("ApprovalReason")),
    ContractEnum(
        ContactAutomationStatus, "contact_automation_status", _schema("ContactAutomationStatus")
    ),
    ContractEnum(
        GlobalAutomationStatus, "global_automation_status", _schema("GlobalAutomationStatus")
    ),
    ContractEnum(Actor, "actor", _schema("Actor")),
    ContractEnum(AgentActionType, "agent_action_type", _schema("AgentActionType")),
    ContractEnum(ConnectionStatus, "connection_status", _schema("ConnectionStatus")),
    ContractEnum(
        CapabilityVerification,
        "capability_verification",
        ("schemas/connector_capabilities.v1.json", "$defs", "verification"),
    ),
    ContractEnum(Punctuation, "style_punctuation", _style("punctuation")),
    ContractEnum(EmojiFrequency, "style_emoji_frequency", _style("emoji_frequency")),
    ContractEnum(Brevity, "style_brevity", _style("brevity")),
    ContractEnum(Humor, "style_humor", _style("humor")),
    ContractEnum(Tone, "style_tone", _style("tone")),
    ContractEnum(ResponsePace, "style_response_pace", _style("response_pace")),
)
