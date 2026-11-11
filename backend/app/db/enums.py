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
    # contract 1.3.0: the user said they sent an assisted draft by hand. Never sent or
    # delivered (nothing was transmitted or observed by Wingman).
    USER_REPORTED_SENT = "user_reported_sent"


class MessageDirection(StrEnum):
    INBOUND = "inbound"
    OUTBOUND = "outbound"


class MessageOrigin(StrEnum):
    CONTACT = "contact"
    USER_MANUAL = "user_manual"
    APPROVED_DRAFT = "approved_draft"
    AUTOPILOT = "autopilot"
    ASSISTED_DRAFT = "assisted_draft"  # contract 1.3.0


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
    PAUSED = "paused"  # contract 1.2.0


class ApprovalStatus(StrEnum):
    PENDING = "pending"
    APPROVED = "approved"
    REJECTED = "rejected"
    EXPIRED = "expired"
    CANCELED = "canceled"


class DraftDecision(StrEnum):
    SEND_CANDIDATE = "send_candidate"
    NO_SEND = "no_send"
    ASK_USER = "ask_user"


class OutboxInstructionState(StrEnum):
    """Device outbox instruction lifecycle (contract v1.1.0, ADR 0006)."""

    PENDING = "pending"
    LEASED = "leased"
    COMPLETED = "completed"
    EXPIRED = "expired"
    CANCELED = "canceled"


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
    # contract 1.2.0
    LEASE_EXPIRED = "lease_expired"
    CONNECTION_PAIRED = "connection_paired"
    CONNECTION_UNPAIRED = "connection_unpaired"
    # contract 1.3.0 (ADR 0008)
    NOTICE_RECEIVED = "notice_received"
    ASSISTED_DELIVERY_QUEUED = "assisted_delivery_queued"
    ASSISTED_DELIVERY_CONFIRMED = "assisted_delivery_confirmed"
    ASSISTED_DELIVERY_DISMISSED = "assisted_delivery_dismissed"
    ASSISTED_DELIVERY_CANCELED = "assisted_delivery_canceled"
    EXPERIMENTAL_CONSENT_RECORDED = "experimental_consent_recorded"
    EXPERIMENTAL_CONSENT_REVOKED = "experimental_consent_revoked"
    IDENTITY_LINK_CREATED = "identity_link_created"
    IDENTITY_LINK_DELETED = "identity_link_deleted"
    PROFILE_IMPORTED = "profile_imported"
    PROFILE_DECISION_RECORDED = "profile_decision_recorded"
    PROFILE_ACTION_QUEUED = "profile_action_queued"
    # Contract 1.3.2 (D-032): a profile attached to / detached from a conversation, and a
    # profile deleted by the user.
    PROFILE_LINKED = "profile_linked"
    PROFILE_UNLINKED = "profile_unlinked"
    PROFILE_DELETED = "profile_deleted"


class ConnectionStatus(StrEnum):
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"
    ERROR = "error"


class ConnectionTransport(StrEnum):
    """Contract 1.3.0: how the paired device reaches the platform (ADR 0008)."""

    SIMULATOR = "simulator"
    SMS_PROVIDER = "sms_provider"
    NOTIFICATION = "notification"
    COMPANION = "companion"
    UNOFFICIAL_API = "unofficial_api"


class InboundEventKind(StrEnum):
    CHAT_MESSAGE = "chat_message"
    MATCH = "match"
    LIKE_COMMENT = "like_comment"
    EXPIRY = "expiry"
    SYSTEM = "system"
    UNKNOWN = "unknown"


class SenderIdentity(StrEnum):
    LINKED = "linked"
    UNLINKED = "unlinked"
    AMBIGUOUS = "ambiguous"


class AssistedDeliveryStatus(StrEnum):
    PENDING = "pending"
    USER_REPORTED_SENT = "user_reported_sent"
    DISMISSED = "dismissed"
    CANCELED = "canceled"


class ProfileProvider(StrEnum):
    TINDER = "tinder"
    BUMBLE = "bumble"
    HINGE = "hinge"
    MOCK = "mock"


class ProfileSource(StrEnum):
    MANUAL = "manual"
    FIXTURE = "fixture"
    DEVICE = "device"


class ProfileDecision(StrEnum):
    LIKE = "like"
    PASS = "pass"  # noqa: S105 - a swipe decision, not a password
    SKIP = "skip"
    SUPERLIKE = "superlike"


class ProfileActionExecution(StrEnum):
    NOT_EXECUTABLE = "not_executable"
    QUEUED = "queued"
    EXECUTED = "executed"
    FAILED = "failed"
    UNKNOWN = "unknown"


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
    ContractEnum(ApprovalStatus, "approval_status", _schema("ApprovalStatus")),
    ContractEnum(DraftDecision, "draft_decision", _schema("DraftDecision")),
    ContractEnum(
        OutboxInstructionState, "outbox_instruction_state", _schema("OutboxInstructionState")
    ),
    ContractEnum(
        ContactAutomationStatus, "contact_automation_status", _schema("ContactAutomationStatus")
    ),
    ContractEnum(
        GlobalAutomationStatus, "global_automation_status", _schema("GlobalAutomationStatus")
    ),
    ContractEnum(Actor, "actor", _schema("Actor")),
    ContractEnum(AgentActionType, "agent_action_type", _schema("AgentActionType")),
    ContractEnum(ConnectionStatus, "connection_status", _schema("ConnectionStatus")),
    # contract 1.3.0 (ADR 0008)
    ContractEnum(ConnectionTransport, "connection_transport", _schema("ConnectionTransport")),
    ContractEnum(InboundEventKind, "inbound_event_kind", _schema("InboundEventKind")),
    ContractEnum(SenderIdentity, "sender_identity", _schema("SenderIdentity")),
    ContractEnum(
        AssistedDeliveryStatus, "assisted_delivery_status", _schema("AssistedDeliveryStatus")
    ),
    ContractEnum(ProfileProvider, "profile_provider", _schema("ProfileProvider")),
    ContractEnum(ProfileSource, "profile_source", _schema("ProfileSource")),
    ContractEnum(ProfileDecision, "profile_decision", _schema("ProfileDecision")),
    ContractEnum(
        ProfileActionExecution, "profile_action_execution", _schema("ProfileActionExecution")
    ),
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
