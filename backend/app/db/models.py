"""SQLAlchemy 2 ORM models for every WING-003 table.

Field names, nullability and enums follow ``contracts/openapi.yaml``. Where the
contract describes an object that is computed (e.g. ``Conversation.effective_intent``,
``Conversation.automation``, ``pending_approval_count``) it is *not* stored here;
services derive it. Contract ``if/then`` rules that relate several fields of one row
(e.g. inbound messages are always ``received``) are enforced as CHECK constraints so
no code path can persist a state the contract forbids.

Tenant isolation is enforced twice: repositories always filter by ``user_id``, and
every reference between user-owned rows is a composite FK ``(user_id, x_id)``, so a
row can never point at another user's row.
"""

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Float,
    ForeignKeyConstraint,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy import text as sql
from sqlalchemy.dialects import postgresql
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import (
    PLATFORM_PATTERN,
    Base,
    IdMixin,
    TimestampMixin,
    UserOwnedMixin,
    pg_enum,
    tenant_fk,
    tenant_key,
)
from app.db.enums import (
    Actor,
    AgentActionType,
    ApprovalReason,
    ApprovalStatus,
    AutomationMode,
    Brevity,
    CapabilityVerification,
    ConnectionStatus,
    ContactAutomationStatus,
    ConversationStage,
    DateProposalState,
    DeliveryState,
    DraftDecision,
    EmojiFrequency,
    GlobalAutomationStatus,
    Humor,
    IntentMode,
    MessageDirection,
    MessageOrigin,
    OutboxInstructionState,
    Punctuation,
    ResponsePace,
    Tone,
)

EXTERNAL_ID_LEN = 256
IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9._:-]{8,128}$"

JSONB = postgresql.JSONB(none_as_null=True)
UUID_ARRAY = postgresql.ARRAY(postgresql.UUID(as_uuid=True))
TEXT_ARRAY = postgresql.ARRAY(Text())


def _empty_array() -> Any:
    return sql("'{}'")


def _empty_object() -> Any:
    return sql("'{}'::jsonb")


# --------------------------------------------------------------------------- users


class User(IdMixin, TimestampMixin, Base):
    """A Wingman account. Resolved from an OIDC ``(issuer, subject)`` pair (WING-004)."""

    __tablename__ = "users"
    __table_args__ = (
        UniqueConstraint("auth_issuer", "auth_subject"),
        CheckConstraint("char_length(auth_issuer) BETWEEN 1 AND 512", name="auth_issuer_len"),
        CheckConstraint("char_length(auth_subject) BETWEEN 1 AND 512", name="auth_subject_len"),
    )

    auth_issuer: Mapped[str] = mapped_column(Text, nullable=False)
    auth_subject: Mapped[str] = mapped_column(Text, nullable=False)
    email: Mapped[str | None] = mapped_column(Text, nullable=True)
    display_name: Mapped[str | None] = mapped_column(String(120), nullable=True)
    # GlobalAutomationState: the account-wide stop switch.
    global_automation_status: Mapped[GlobalAutomationStatus] = mapped_column(
        pg_enum("global_automation_status"),
        nullable=False,
        default=GlobalAutomationStatus.ACTIVE,
        server_default=GlobalAutomationStatus.ACTIVE.value,
    )
    global_automation_changed_at: Mapped[datetime | None] = mapped_column(nullable=True)
    # Retention: set when the user requests account deletion; a purge job hard-deletes
    # later (all user-owned rows cascade).
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True)


# --------------------------------------------------------------------------- preferences


class StyleProfile(UserOwnedMixin, Base):
    """Immutable ``StyleProfile`` snapshot, referenced by preference versions.

    Every attribute is nullable: null means "not configured", never inferred.
    """

    __tablename__ = "style_profiles"
    __table_args__ = (
        tenant_key(),
        Index(None, "user_id", "created_at", "id"),
        CheckConstraint("cardinality(style_samples) <= 20", name="style_samples_max"),
    )

    punctuation: Mapped[Punctuation | None] = mapped_column(
        pg_enum("style_punctuation"), nullable=True
    )
    emoji_frequency: Mapped[EmojiFrequency | None] = mapped_column(
        pg_enum("style_emoji_frequency"), nullable=True
    )
    brevity: Mapped[Brevity | None] = mapped_column(pg_enum("style_brevity"), nullable=True)
    humor: Mapped[Humor | None] = mapped_column(pg_enum("style_humor"), nullable=True)
    tone: Mapped[Tone | None] = mapped_column(pg_enum("style_tone"), nullable=True)
    response_pace: Mapped[ResponsePace | None] = mapped_column(
        pg_enum("style_response_pace"), nullable=True
    )
    style_samples: Mapped[list[str]] = mapped_column(
        TEXT_ARRAY, nullable=False, default=list, server_default=_empty_array()
    )


class UserPreference(UserOwnedMixin, Base):
    """One immutable version of preferences.

    ``contact_id IS NULL``: user-wide ``Preferences`` (intent, automation and style are
    required). ``contact_id`` set: a ``ContactPreferences.override`` version, where every
    field is nullable and null means "inherit". The current version for a scope is the
    highest ``version``. Versions start at 1 (``version: 0`` in the API = never saved).
    """

    __tablename__ = "user_preferences"
    __table_args__ = (
        tenant_key(),
        tenant_fk("contact_id", "contacts"),
        tenant_fk("style_profile_id", "style_profiles", ondelete="RESTRICT"),
        UniqueConstraint("user_id", "contact_id", "version", postgresql_nulls_not_distinct=True),
        Index(None, "user_id", "contact_id", "version", "id"),
        CheckConstraint("version >= 1", name="version_positive"),
        CheckConstraint(
            "contact_id IS NOT NULL OR (intent_mode IS NOT NULL AND automation_mode IS NOT NULL"
            " AND style_profile_id IS NOT NULL)",
            name="user_wide_required",
        ),
        CheckConstraint(
            "(intent_mode = 'custom'"
            " AND coalesce(char_length(intent_custom_description), 0) BETWEEN 1 AND 500)"
            " OR (intent_mode IS DISTINCT FROM 'custom' AND intent_custom_description IS NULL)",
            name="intent_custom_description",
        ),
        CheckConstraint("cardinality(dealbreakers) <= 50", name="dealbreakers_max"),
        CheckConstraint(
            "active_hours IS NULL OR jsonb_typeof(active_hours) = 'object'",
            name="active_hours_object",
        ),
        CheckConstraint(
            "(reply_delay_min_seconds IS NULL) = (reply_delay_max_seconds IS NULL)",
            name="reply_delay_pair",
        ),
        CheckConstraint(
            "reply_delay_min_seconds IS NULL OR (reply_delay_min_seconds BETWEEN 0 AND 86400"
            " AND reply_delay_max_seconds BETWEEN 0 AND 86400"
            " AND reply_delay_min_seconds <= reply_delay_max_seconds)",
            name="reply_delay_range",
        ),
        CheckConstraint(
            "daily_send_limit IS NULL OR daily_send_limit BETWEEN 0 AND 1000",
            name="daily_send_limit_range",
        ),
    )

    contact_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    version: Mapped[int] = mapped_column(Integer, nullable=False)
    intent_mode: Mapped[IntentMode | None] = mapped_column(pg_enum("intent_mode"), nullable=True)
    intent_custom_description: Mapped[str | None] = mapped_column(Text, nullable=True)
    dealbreakers: Mapped[list[str]] = mapped_column(
        TEXT_ARRAY, nullable=False, default=list, server_default=_empty_array()
    )
    style_profile_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    automation_mode: Mapped[AutomationMode | None] = mapped_column(
        pg_enum("automation_mode"), nullable=True
    )
    # ActiveHours object exactly as in the contract; null = no time restriction.
    active_hours: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)
    reply_delay_min_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)
    reply_delay_max_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)
    daily_send_limit: Mapped[int | None] = mapped_column(Integer, nullable=True)
    approval_required_for: Mapped[list[ApprovalReason]] = mapped_column(
        postgresql.ARRAY(pg_enum("approval_reason")),
        nullable=False,
        default=list,
        server_default=_empty_array(),
    )


# --------------------------------------------------------------------------- connections


class PlatformConnection(UserOwnedMixin, Base):
    """``Connection``: one messaging connector instance and its advertised capabilities.

    ``capabilities`` stores the full ``connector_capabilities.v1`` document (evidence,
    notes, ``verified_at``). The five ``*_capability`` columns denormalise each
    verification label for policy queries; a CHECK keeps them equal to the document.
    """

    __tablename__ = "platform_connections"
    __table_args__ = (
        tenant_key(),
        UniqueConstraint("user_id", "id", "platform"),
        Index(None, "user_id", "created_at", "id"),
        CheckConstraint(f"platform ~ '{PLATFORM_PATTERN}'", name="platform_format"),
        CheckConstraint("char_length(display_name) >= 1", name="display_name_nonempty"),
        # Contract 1.2.0 (ADR 0007): one paired connection per install and platform, and
        # an unpaired connection is always disconnected.
        Index(
            None,
            "user_id",
            "platform",
            "device_install_id",
            unique=True,
            postgresql_where=sql("device_install_id IS NOT NULL AND unpaired_at IS NULL"),
        ),
        CheckConstraint(
            "unpaired_at IS NULL OR status = 'disconnected'", name="unpaired_is_disconnected"
        ),
        CheckConstraint("jsonb_typeof(capabilities) = 'object'", name="capabilities_object"),
        CheckConstraint(
            "capabilities ->> 'platform' IS NOT DISTINCT FROM platform",
            name="capabilities_platform_matches",
        ),
        *(
            CheckConstraint(
                f"{cap}_capability::text IS NOT DISTINCT FROM"
                f" capabilities -> 'capabilities' -> '{cap}' ->> 'status'",
                name=f"{cap}_capability_matches",
            )
            for cap in ("read", "draft", "send", "swipe", "calendar_write")
        ),
    )

    platform: Mapped[str] = mapped_column(String(32), nullable=False)
    display_name: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[ConnectionStatus] = mapped_column(
        pg_enum("connection_status"),
        nullable=False,
        default=ConnectionStatus.CONNECTED,
        server_default=ConnectionStatus.CONNECTED.value,
    )
    capabilities: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    read_capability: Mapped[CapabilityVerification] = mapped_column(
        pg_enum("capability_verification"), nullable=False
    )
    draft_capability: Mapped[CapabilityVerification] = mapped_column(
        pg_enum("capability_verification"), nullable=False
    )
    send_capability: Mapped[CapabilityVerification] = mapped_column(
        pg_enum("capability_verification"), nullable=False
    )
    swipe_capability: Mapped[CapabilityVerification] = mapped_column(
        pg_enum("capability_verification"), nullable=False
    )
    calendar_write_capability: Mapped[CapabilityVerification] = mapped_column(
        pg_enum("capability_verification"), nullable=False
    )
    # Contract 1.2.0 pairing (ADR 0007). Null for connections created outside the API.
    device_install_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    paired_at: Mapped[datetime | None] = mapped_column(nullable=True)
    unpaired_at: Mapped[datetime | None] = mapped_column(nullable=True)


# --------------------------------------------------------------------------- contacts


class Contact(UserOwnedMixin, Base):
    """The other person, as seen through one connection. Carries per-contact agent state."""

    __tablename__ = "contacts"
    __table_args__ = (
        tenant_key(),
        UniqueConstraint("user_id", "id", "connection_id"),
        UniqueConstraint("user_id", "connection_id", "external_contact_id"),
        tenant_fk("connection_id", "platform_connections"),
        Index(None, "user_id", "created_at", "id"),
        CheckConstraint(
            f"char_length(external_contact_id) BETWEEN 1 AND {EXTERNAL_ID_LEN}",
            name="external_contact_id_len",
        ),
    )

    connection_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    external_contact_id: Mapped[str] = mapped_column(String(EXTERNAL_ID_LEN), nullable=False)
    display_name: Mapped[str] = mapped_column(String(200), nullable=False)
    # ContactAutomationState
    automation_status: Mapped[ContactAutomationStatus] = mapped_column(
        pg_enum("contact_automation_status"),
        nullable=False,
        default=ContactAutomationStatus.ACTIVE,
        server_default=ContactAutomationStatus.ACTIVE.value,
    )
    automation_status_changed_at: Mapped[datetime | None] = mapped_column(nullable=True)


# --------------------------------------------------------------------------- conversations


class Conversation(UserOwnedMixin, Base):
    """``Conversation``. ``effective_intent``/``automation``/counts other than unread are
    computed by services, not stored."""

    __tablename__ = "conversations"
    __table_args__ = (
        tenant_key(),
        UniqueConstraint("user_id", "id", "platform"),
        # Contact must be on the same connection, and the platform must be the connection's.
        ForeignKeyConstraint(
            ["user_id", "contact_id", "connection_id"],
            ["contacts.user_id", "contacts.id", "contacts.connection_id"],
            ondelete="CASCADE",
        ),
        ForeignKeyConstraint(
            ["user_id", "connection_id", "platform"],
            [
                "platform_connections.user_id",
                "platform_connections.id",
                "platform_connections.platform",
            ],
            ondelete="CASCADE",
            name="fk_conversations_user_id_connection_id_platform_connections",
        ),
        Index(
            "uq_conversations_user_id_connection_id_external_thread_id",
            "user_id",
            "connection_id",
            "external_thread_id",
            unique=True,
            postgresql_where=sql("external_thread_id IS NOT NULL"),
        ),
        # Inbox keyset: last_activity_at DESC, id DESC.
        Index(
            "ix_conversations_user_id_last_activity_at_id",
            "user_id",
            sql("last_activity_at DESC"),
            sql("id DESC"),
        ),
        Index(None, "user_id", "contact_id", "id"),
        CheckConstraint("unread_count >= 0", name="unread_count_nonnegative"),
        CheckConstraint(
            "last_message_preview IS NULL OR char_length(last_message_preview) <= 160",
            name="last_message_preview_len",
        ),
        CheckConstraint(
            "analysis IS NULL OR jsonb_typeof(analysis) = 'object'", name="analysis_object"
        ),
    )

    contact_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    connection_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    platform: Mapped[str] = mapped_column(String(32), nullable=False)
    external_thread_id: Mapped[str | None] = mapped_column(String(EXTERNAL_ID_LEN), nullable=True)
    stage: Mapped[ConversationStage] = mapped_column(
        pg_enum("conversation_stage"),
        nullable=False,
        default=ConversationStage.NEW,
        server_default=ConversationStage.NEW.value,
    )
    unread_count: Mapped[int] = mapped_column(
        Integer, nullable=False, default=0, server_default=sql("0")
    )
    last_message_preview: Mapped[str | None] = mapped_column(Text, nullable=True)
    last_activity_at: Mapped[datetime] = mapped_column(nullable=False, server_default=func.now())
    # Latest advisory ConversationAnalysis (WING-009), stored as the contract object.
    analysis: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)


# --------------------------------------------------------------------------- messages


class Message(UserOwnedMixin, Base):
    """``Message``. Inbound rows are deduplicated by ``(user_id, platform,
    external_message_id)``; ``platform`` is pinned to the conversation's by FK.

    ``created_at`` follows the contract: inbound = when the platform says it was sent,
    outbound = when queued. ``ingested_at`` is when Wingman stored the row.
    """

    __tablename__ = "messages"
    __table_args__ = (
        tenant_key(),
        UniqueConstraint("user_id", "platform", "external_message_id"),
        ForeignKeyConstraint(
            ["user_id", "conversation_id", "platform"],
            ["conversations.user_id", "conversations.id", "conversations.platform"],
            ondelete="CASCADE",
        ),
        # Thread keyset: created_at ASC, id ASC.
        Index(None, "user_id", "conversation_id", "created_at", "id"),
        # Dispatcher work queue / takeover cancellation.
        Index(None, "user_id", "delivery_state", "created_at", "id"),
        # WING-011: the approval a message was sent under; an approval yields at most one
        # outbound message (approve can never queue two sends).
        # use_alter: messages -> approvals -> drafts -> messages is a cycle; the
        # migration adds this FK after all three tables exist.
        ForeignKeyConstraint(
            ["user_id", "approval_id"],
            ["approvals.user_id", "approvals.id"],
            ondelete="SET NULL (approval_id)",
            use_alter=True,
        ),
        Index(
            "uq_messages_user_id_approval_id",
            "user_id",
            "approval_id",
            unique=True,
            postgresql_where=sql("approval_id IS NOT NULL"),
        ),
        CheckConstraint("attempt_count >= 0", name="attempt_count_nonnegative"),
        CheckConstraint(
            "send_after IS NULL OR direction = 'outbound'", name="send_after_outbound_only"
        ),
        CheckConstraint(
            f"external_message_id IS NULL OR char_length(external_message_id)"
            f" BETWEEN 1 AND {EXTERNAL_ID_LEN}",
            name="external_message_id_len",
        ),
        CheckConstraint(
            "direction <> 'inbound' OR (origin = 'contact' AND delivery_state = 'received'"
            " AND external_message_id IS NOT NULL)",
            name="inbound_shape",
        ),
        CheckConstraint(
            "direction <> 'outbound' OR (origin <> 'contact' AND delivery_state <> 'received')",
            name="outbound_shape",
        ),
        CheckConstraint(
            "delivery_state NOT IN ('queued', 'sending', 'canceled')"
            " OR (sent_at IS NULL AND delivered_at IS NULL)",
            name="unsent_has_no_sent_at",
        ),
        CheckConstraint(
            "delivery_state <> 'delivered' OR (sent_at IS NOT NULL AND delivered_at IS NOT NULL)",
            name="delivered_has_timestamps",
        ),
        CheckConstraint(
            "(failure_code IS NULL) = (failure_retryable IS NULL)"
            " AND (failure_code IS NOT NULL OR failure_detail IS NULL)",
            name="failure_shape",
        ),
    )

    conversation_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    platform: Mapped[str] = mapped_column(String(32), nullable=False)
    direction: Mapped[MessageDirection] = mapped_column(
        pg_enum("message_direction"), nullable=False
    )
    origin: Mapped[MessageOrigin] = mapped_column(pg_enum("message_origin"), nullable=False)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    delivery_state: Mapped[DeliveryState] = mapped_column(pg_enum("delivery_state"), nullable=False)
    external_message_id: Mapped[str | None] = mapped_column(String(EXTERNAL_ID_LEN), nullable=True)
    # The Approval this message was queued under (WING-011), tenant-scoped FK.
    approval_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    attempt_count: Mapped[int] = mapped_column(
        Integer, nullable=False, default=0, server_default=sql("0")
    )
    # MessageFailure, flattened.
    failure_code: Mapped[str | None] = mapped_column(Text, nullable=True)
    failure_detail: Mapped[str | None] = mapped_column(Text, nullable=True)
    failure_retryable: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    sent_at: Mapped[datetime | None] = mapped_column(nullable=True)
    delivered_at: Mapped[datetime | None] = mapped_column(nullable=True)
    ingested_at: Mapped[datetime] = mapped_column(nullable=False, server_default=func.now())
    # WING-011 dispatcher: a queued outbound message is not handed to a transport before
    # this instant (reply delay, retry backoff). Null = due immediately.
    send_after: Mapped[datetime | None] = mapped_column(nullable=True)


# --------------------------------------------------------------------------- drafts and approvals


class Draft(UserOwnedMixin, Base):
    """A ``Draft`` the automation pipeline generated (WING-011, I-007).

    Stored exactly as the reply generator returned it: ``approval_reasons`` are its
    content-based reasons only (D-013); the policy reasons live on the approval. A draft
    is advisory data and authorizes nothing.
    """

    __tablename__ = "drafts"
    __table_args__ = (
        tenant_key(),
        tenant_fk("conversation_id", "conversations"),
        tenant_fk("contact_id", "contacts"),
        tenant_fk("trigger_message_id", "messages", ondelete="SET NULL"),
        Index(None, "user_id", "conversation_id", "created_at", "id"),
        # One agent-initiated draft per inbound message: re-running the pipeline for the
        # same message can never produce a second draft, approval or send.
        Index(
            "uq_drafts_user_id_trigger_message_id",
            "user_id",
            "trigger_message_id",
            unique=True,
            postgresql_where=sql("trigger_message_id IS NOT NULL"),
        ),
        CheckConstraint(
            "(decision = 'send_candidate') = (text IS NOT NULL)", name="text_iff_send_candidate"
        ),
        CheckConstraint("text IS NULL OR char_length(text) BETWEEN 1 AND 4000", name="text_len"),
        CheckConstraint(
            "decision <> 'ask_user' OR question_for_user IS NOT NULL", name="ask_user_question"
        ),
        CheckConstraint("confidence >= 0 AND confidence <= 1", name="confidence_range"),
        CheckConstraint(
            "requires_approval = (cardinality(approval_reasons) > 0)",
            name="requires_approval_matches_reasons",
        ),
    )

    conversation_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    contact_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    decision: Mapped[DraftDecision] = mapped_column(pg_enum("draft_decision"), nullable=False)
    text: Mapped[str | None] = mapped_column(Text, nullable=True)
    question_for_user: Mapped[str | None] = mapped_column(Text, nullable=True)
    rationale: Mapped[str] = mapped_column(Text, nullable=False)
    confidence: Mapped[float] = mapped_column(Float, nullable=False)
    requires_approval: Mapped[bool] = mapped_column(Boolean, nullable=False)
    approval_reasons: Mapped[list[ApprovalReason]] = mapped_column(
        postgresql.ARRAY(pg_enum("approval_reason")),
        nullable=False,
        default=list,
        server_default=_empty_array(),
    )
    intent_mode: Mapped[IntentMode] = mapped_column(pg_enum("intent_mode"), nullable=False)
    grounding_memory_ids: Mapped[list[uuid.UUID]] = mapped_column(
        UUID_ARRAY, nullable=False, default=list, server_default=_empty_array()
    )
    grounding_message_ids: Mapped[list[uuid.UUID]] = mapped_column(
        UUID_ARRAY, nullable=False, default=list, server_default=_empty_array()
    )
    # DraftReason code from the generator (``model_decision``, ``model_unavailable``, ...).
    reason: Mapped[str] = mapped_column(String(64), nullable=False)
    # The inbound message that triggered an agent-initiated draft (null otherwise).
    trigger_message_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)


class Approval(UserOwnedMixin, Base):
    """``Approval``: a draft waiting for the user's explicit decision (WING-011).

    ``reasons`` = the draft's content reasons plus the controller's policy reasons. At
    most one ``pending`` approval per conversation: a newer one cancels the older. The
    outbound message created on approval points back here (``messages.approval_id``,
    unique), so one approval can never queue two sends.
    """

    __tablename__ = "approvals"
    __table_args__ = (
        tenant_key(),
        ForeignKeyConstraint(
            ["user_id", "conversation_id"],
            ["conversations.user_id", "conversations.id"],
            ondelete="CASCADE",
        ),
        tenant_fk("contact_id", "contacts"),
        tenant_fk("draft_id", "drafts"),
        UniqueConstraint("user_id", "draft_id"),
        # Queue keyset: created_at DESC, id DESC (optionally by status).
        Index(
            "ix_approvals_user_id_status_created_at_id",
            "user_id",
            "status",
            sql("created_at DESC"),
            sql("id DESC"),
        ),
        Index(None, "user_id", "contact_id", "status"),
        Index(
            "uq_approvals_user_id_conversation_id_pending",
            "user_id",
            "conversation_id",
            unique=True,
            postgresql_where=sql("status = 'pending'"),
        ),
        CheckConstraint("cardinality(reasons) >= 1", name="reasons_nonempty"),
        CheckConstraint(
            "(status = 'pending') = (decided_at IS NULL)", name="decided_iff_not_pending"
        ),
        CheckConstraint(
            "(status = 'approved') = (final_text IS NOT NULL)", name="final_text_iff_approved"
        ),
        CheckConstraint(
            "final_text IS NULL OR char_length(final_text) BETWEEN 1 AND 4000",
            name="final_text_len",
        ),
        CheckConstraint(
            "rejection_reason IS NULL OR (status = 'rejected'"
            " AND char_length(rejection_reason) <= 500)",
            name="rejection_reason_shape",
        ),
    )

    conversation_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    contact_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    draft_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    status: Mapped[ApprovalStatus] = mapped_column(
        pg_enum("approval_status"),
        nullable=False,
        default=ApprovalStatus.PENDING,
        server_default=ApprovalStatus.PENDING.value,
    )
    reasons: Mapped[list[ApprovalReason]] = mapped_column(
        postgresql.ARRAY(pg_enum("approval_reason")), nullable=False
    )
    final_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    rejection_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    expires_at: Mapped[datetime | None] = mapped_column(nullable=True)
    decided_at: Mapped[datetime | None] = mapped_column(nullable=True)


# --------------------------------------------------------------------------- device outbox


class OutboxInstruction(UserOwnedMixin, Base):
    """One authorized send attempt for a device-side connector (ADR 0006, contract v1.1.0).

    Created only by the dispatcher (WING-011). ``idempotency_key`` is the per-attempt
    connector key ``send:<message_id>:<attempt>`` (D-006). Leased at most once, never
    renewed: ``leased`` / ``completed`` / ``expired`` rows always carry the lease, and
    ``pending`` / ``canceled`` rows never do. At most one live (pending or leased)
    instruction per outbound message.
    """

    __tablename__ = "outbox_instructions"
    __table_args__ = (
        tenant_key(),
        tenant_fk("connection_id", "platform_connections"),
        tenant_fk("outbound_message_id", "messages"),
        UniqueConstraint("user_id", "idempotency_key"),
        UniqueConstraint("user_id", "outbound_message_id", "attempt"),
        Index(
            "uq_outbox_instructions_user_id_outbound_message_id_live",
            "user_id",
            "outbound_message_id",
            unique=True,
            postgresql_where=sql("state IN ('pending', 'leased')"),
        ),
        # Claim / listing keyset: created_at ASC, id ASC per connection and state.
        Index(None, "user_id", "connection_id", "state", "created_at", "id"),
        Index(
            "ix_outbox_instructions_user_id_lease_expires_at",
            "user_id",
            "lease_expires_at",
            postgresql_where=sql("state = 'leased'"),
        ),
        CheckConstraint("attempt >= 1", name="attempt_positive"),
        CheckConstraint(
            f"idempotency_key ~ '{IDEMPOTENCY_KEY_PATTERN}'", name="idempotency_key_format"
        ),
        CheckConstraint(
            "(state IN ('pending', 'canceled')) = (leased_at IS NULL)"
            " AND (leased_at IS NULL) = (lease_expires_at IS NULL)"
            " AND (leased_at IS NULL) = (device_install_id IS NULL)",
            name="lease_shape",
        ),
        CheckConstraint(
            "lease_expires_at IS NULL OR lease_expires_at > leased_at", name="lease_after_start"
        ),
        CheckConstraint(
            "(state = 'completed') = (completed_at IS NOT NULL)", name="completed_shape"
        ),
    )

    connection_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    outbound_message_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    attempt: Mapped[int] = mapped_column(Integer, nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(128), nullable=False)
    state: Mapped[OutboxInstructionState] = mapped_column(
        pg_enum("outbox_instruction_state"),
        nullable=False,
        default=OutboxInstructionState.PENDING,
        server_default=OutboxInstructionState.PENDING.value,
    )
    device_install_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    leased_at: Mapped[datetime | None] = mapped_column(nullable=True)
    lease_expires_at: Mapped[datetime | None] = mapped_column(nullable=True)
    completed_at: Mapped[datetime | None] = mapped_column(nullable=True)


class ConnectorEventIngest(UserOwnedMixin, Base):
    """Permanent record of every ``connector_event.v1`` accepted (ADR 0006).

    Keyed ``(user_id, event_id)``: a repeated ``event_id`` is a duplicate forever (not
    only for the 24 h idempotency window). ``request_hash`` detects a reused key with a
    different body. A delivery receipt that arrives before its acknowledgement is kept
    with ``deferred = true`` and applied when the acknowledgement lands. No message text
    is stored here.
    """

    __tablename__ = "connector_event_ingests"
    __table_args__ = (
        tenant_key(),
        UniqueConstraint("user_id", "event_id"),
        tenant_fk("connection_id", "platform_connections"),
        tenant_fk("message_id", "messages", ondelete="SET NULL"),
        Index(
            "ix_connector_event_ingests_user_id_message_id_deferred",
            "user_id",
            "message_id",
            postgresql_where=sql("deferred"),
        ),
        CheckConstraint("outcome IN ('accepted', 'duplicate_message')", name="outcome_values"),
        CheckConstraint("request_hash ~ '^[0-9a-f]{64}$'", name="request_hash_sha256"),
        CheckConstraint("event_type ~ '^[a-z][a-z_.]{1,63}$'", name="event_type_format"),
    )

    event_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    connection_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    outcome: Mapped[str] = mapped_column(String(32), nullable=False)
    message_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    occurred_at: Mapped[datetime] = mapped_column(nullable=False)
    deferred: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False, server_default=sql("false")
    )


# --------------------------------------------------------------------------- memory


class ConversationMemory(UserOwnedMixin, Base):
    """Attributable per-contact fact or summary (WING-008).

    A ``fact`` must cite at least one source message. ``superseded_by_id`` links a
    corrected fact to its replacement. ``expires_at``/``deleted_at`` are retention
    fields: retrieval must exclude rows that are expired or deleted.
    """

    __tablename__ = "conversation_memories"
    __table_args__ = (
        tenant_key(),
        tenant_fk("contact_id", "contacts"),
        tenant_fk("conversation_id", "conversations", ondelete="SET NULL"),
        tenant_fk(
            "superseded_by_id",
            "conversation_memories",
            ondelete="SET NULL",
            name="fk_conversation_memories_user_id_superseded_by_id_self",
        ),
        Index(None, "user_id", "contact_id", "created_at", "id"),
        CheckConstraint("kind IN ('fact', 'summary')", name="kind_values"),
        CheckConstraint("confidence >= 0 AND confidence <= 1", name="confidence_range"),
        CheckConstraint("char_length(content) >= 1", name="content_nonempty"),
        CheckConstraint(
            "kind <> 'fact' OR cardinality(source_message_ids) >= 1", name="fact_is_attributed"
        ),
        # WING-008 provenance (D-010: folded into 0001).
        CheckConstraint("origin IN ('extracted', 'user_correction')", name="origin_values"),
        CheckConstraint("basis IN ('stated', 'inferred')", name="basis_values"),
        Index(None, "user_id", "contact_id", "fact_key"),
    )

    contact_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    conversation_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    kind: Mapped[str] = mapped_column(String(16), nullable=False)
    category: Mapped[str | None] = mapped_column(String(64), nullable=True)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    confidence: Mapped[float] = mapped_column(Float, nullable=False)
    source_message_ids: Mapped[list[uuid.UUID]] = mapped_column(
        UUID_ARRAY, nullable=False, default=list, server_default=_empty_array()
    )
    superseded_by_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    expires_at: Mapped[datetime | None] = mapped_column(nullable=True)
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True)
    # WING-008 provenance (D-010: in 0001): used for supersession and labeling.
    origin: Mapped[str] = mapped_column(
        String(16), nullable=False, default="extracted", server_default=sql("'extracted'")
    )
    basis: Mapped[str] = mapped_column(
        String(16), nullable=False, default="stated", server_default=sql("'stated'")
    )
    fact_key: Mapped[str | None] = mapped_column(String(64), nullable=True)
    stated_at: Mapped[datetime | None] = mapped_column(nullable=True)


# --------------------------------------------------------------------------- audit


class AgentAction(UserOwnedMixin, Base):
    """``AgentAction``: append-only audit log. Repositories expose no update or delete.

    References use ``ON DELETE SET NULL`` (of the reference column only) so audit entries
    outlive the rows they describe; deleting the user removes everything.
    """

    __tablename__ = "agent_actions"
    __table_args__ = (
        tenant_key(),
        tenant_fk("conversation_id", "conversations", ondelete="SET NULL"),
        tenant_fk("contact_id", "contacts", ondelete="SET NULL"),
        tenant_fk("message_id", "messages", ondelete="SET NULL"),
        tenant_fk("approval_id", "approvals", ondelete="SET NULL"),
        # Audit keyset: occurred_at DESC, id DESC (optionally filtered).
        Index(
            "ix_agent_actions_user_id_occurred_at_id",
            "user_id",
            sql("occurred_at DESC"),
            sql("id DESC"),
        ),
        Index(
            "ix_agent_actions_user_id_conversation_id_occurred_at_id",
            "user_id",
            "conversation_id",
            sql("occurred_at DESC"),
            sql("id DESC"),
        ),
        Index(
            "ix_agent_actions_user_id_contact_id_occurred_at_id",
            "user_id",
            "contact_id",
            sql("occurred_at DESC"),
            sql("id DESC"),
        ),
        CheckConstraint("jsonb_typeof(details) = 'object'", name="details_object"),
    )

    type: Mapped[AgentActionType] = mapped_column(pg_enum("agent_action_type"), nullable=False)
    actor: Mapped[Actor] = mapped_column(pg_enum("actor"), nullable=False)
    conversation_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    contact_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    message_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    approval_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    reason_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    occurred_at: Mapped[datetime] = mapped_column(nullable=False, server_default=func.now())
    details: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, default=dict, server_default=_empty_object()
    )


# --------------------------------------------------------------------------- automation rules


class AutomationRule(UserOwnedMixin, Base):
    """A user-defined rule (``ApprovalReason.user_rule``). ``contact_id`` null = all contacts.

    ``conditions`` is an opaque object interpreted by WING-011's policy engine.
    """

    __tablename__ = "automation_rules"
    __table_args__ = (
        tenant_key(),
        tenant_fk("contact_id", "contacts"),
        Index(None, "user_id", "contact_id", "priority", "id"),
        CheckConstraint("char_length(name) BETWEEN 1 AND 120", name="name_len"),
        CheckConstraint("action IN ('require_approval', 'block_send')", name="action_values"),
        CheckConstraint("jsonb_typeof(conditions) = 'object'", name="conditions_object"),
    )

    contact_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    enabled: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=True, server_default=sql("true")
    )
    priority: Mapped[int] = mapped_column(
        Integer, nullable=False, default=0, server_default=sql("0")
    )
    action: Mapped[str] = mapped_column(String(32), nullable=False)
    conditions: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, default=dict, server_default=_empty_object()
    )


# --------------------------------------------------------------------------- dates


class DateProposal(UserOwnedMixin, Base):
    """``DateProposal`` (shape only in contract v1.0.0; lifecycle is WING-018).

    ``confirmed`` requires a ``proposed_start`` and at least one confirmation message,
    so a proposed time can never be stored as a confirmed date.
    """

    __tablename__ = "date_proposals"
    __table_args__ = (
        tenant_key(),
        tenant_fk("conversation_id", "conversations"),
        Index(None, "user_id", "conversation_id", "created_at", "id"),
        Index(
            "uq_date_proposals_user_id_calendar_event_id",
            "user_id",
            "calendar_event_id",
            unique=True,
            postgresql_where=sql("calendar_event_id IS NOT NULL"),
        ),
        CheckConstraint(
            "state <> 'confirmed' OR (proposed_start IS NOT NULL"
            " AND cardinality(confirmation_message_ids) >= 1)",
            name="confirmed_requires_evidence",
        ),
        CheckConstraint(
            "proposed_end IS NULL OR proposed_start IS NULL OR proposed_end >= proposed_start",
            name="proposed_range",
        ),
        CheckConstraint(
            "venue_text IS NULL OR char_length(venue_text) <= 300", name="venue_text_len"
        ),
    )

    conversation_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    state: Mapped[DateProposalState] = mapped_column(
        pg_enum("date_proposal_state"),
        nullable=False,
        default=DateProposalState.SUGGESTED,
        server_default=DateProposalState.SUGGESTED.value,
    )
    proposed_start: Mapped[datetime | None] = mapped_column(nullable=True)
    proposed_end: Mapped[datetime | None] = mapped_column(nullable=True)
    venue_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    confirmation_message_ids: Mapped[list[uuid.UUID]] = mapped_column(
        UUID_ARRAY, nullable=False, default=list, server_default=_empty_array()
    )
    calendar_event_id: Mapped[str | None] = mapped_column(String(EXTERNAL_ID_LEN), nullable=True)


# --------------------------------------------------------------------------- billing


class Subscription(UserOwnedMixin, Base):
    """Subscription entitlement record (WING-024 stub). At most one live subscription."""

    __tablename__ = "subscriptions"
    __table_args__ = (
        tenant_key(),
        Index(None, "user_id", "created_at", "id"),
        Index(
            "uq_subscriptions_user_id_live",
            "user_id",
            unique=True,
            postgresql_where=sql("status IN ('trialing', 'active', 'past_due')"),
        ),
        Index(
            "uq_subscriptions_user_id_provider_external_subscription_id",
            "user_id",
            "provider",
            "external_subscription_id",
            unique=True,
            postgresql_where=sql("external_subscription_id IS NOT NULL"),
        ),
        CheckConstraint("plan IN ('basic', 'pro', 'premium')", name="plan_values"),
        CheckConstraint(
            "status IN ('trialing', 'active', 'past_due', 'canceled', 'expired')",
            name="status_values",
        ),
        CheckConstraint(
            "current_period_end IS NULL OR current_period_start IS NULL"
            " OR current_period_end >= current_period_start",
            name="period_range",
        ),
    )

    plan: Mapped[str] = mapped_column(String(32), nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    provider: Mapped[str] = mapped_column(
        String(32), nullable=False, default="none", server_default="none"
    )
    external_subscription_id: Mapped[str | None] = mapped_column(
        String(EXTERNAL_ID_LEN), nullable=True
    )
    current_period_start: Mapped[datetime | None] = mapped_column(nullable=True)
    current_period_end: Mapped[datetime | None] = mapped_column(nullable=True)
    trial_ends_at: Mapped[datetime | None] = mapped_column(nullable=True)
    canceled_at: Mapped[datetime | None] = mapped_column(nullable=True)


class UsageEvent(UserOwnedMixin, Base):
    """Metering/analytics event (WING-022/024). ``dedup_key`` makes retried events count once."""

    __tablename__ = "usage_events"
    __table_args__ = (
        tenant_key(),
        UniqueConstraint("user_id", "event_type", "dedup_key"),
        tenant_fk("conversation_id", "conversations", ondelete="SET NULL"),
        tenant_fk("connection_id", "platform_connections", ondelete="SET NULL"),
        tenant_fk("message_id", "messages", ondelete="SET NULL"),
        Index(None, "user_id", "occurred_at", "id"),
        Index(None, "user_id", "event_type", "occurred_at", "id"),
        CheckConstraint("event_type ~ '^[a-z][a-z0-9_.]{1,63}$'", name="event_type_format"),
        CheckConstraint("char_length(dedup_key) BETWEEN 1 AND 256", name="dedup_key_len"),
        CheckConstraint("quantity >= 0", name="quantity_nonnegative"),
        CheckConstraint("cost_micros IS NULL OR cost_micros >= 0", name="cost_nonnegative"),
        CheckConstraint("jsonb_typeof(attributes) = 'object'", name="attributes_object"),
    )

    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    dedup_key: Mapped[str] = mapped_column(String(256), nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(nullable=False)
    quantity: Mapped[int] = mapped_column(
        BigInteger, nullable=False, default=1, server_default=sql("1")
    )
    unit: Mapped[str | None] = mapped_column(String(32), nullable=True)
    # Cost in millionths of a USD, only when derived from recorded usage.
    cost_micros: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    conversation_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    connection_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    message_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    attributes: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, default=dict, server_default=_empty_object()
    )


# --------------------------------------------------------------------------- idempotency


class IdempotencyKey(UserOwnedMixin, Base):
    """Durable ``Idempotency-Key`` store (ADR 0004).

    Scoped to ``(user, method, route template, key)``. ``request_hash`` is a SHA-256 hex
    fingerprint of the request body; a different hash with the same key is a mismatch.
    Only completed 2xx/4xx responses are stored; a 5xx releases the key.
    """

    __tablename__ = "idempotency_keys"
    __table_args__ = (
        tenant_key(),
        UniqueConstraint("user_id", "method", "route", "key"),
        Index(None, "user_id", "expires_at"),
        CheckConstraint(f"key ~ '{IDEMPOTENCY_KEY_PATTERN}'", name="key_format"),
        CheckConstraint("method IN ('POST', 'PUT', 'PATCH', 'DELETE')", name="method_values"),
        CheckConstraint("char_length(route) BETWEEN 1 AND 256", name="route_len"),
        CheckConstraint("request_hash ~ '^[0-9a-f]{64}$'", name="request_hash_sha256"),
        CheckConstraint("state IN ('in_progress', 'completed')", name="state_values"),
        CheckConstraint(
            "(state = 'in_progress' AND response_status IS NULL AND completed_at IS NULL)"
            " OR (state = 'completed' AND response_status BETWEEN 200 AND 499"
            " AND completed_at IS NOT NULL)",
            name="state_shape",
        ),
        CheckConstraint("expires_at > created_at", name="expires_after_created"),
    )

    key: Mapped[str] = mapped_column(String(128), nullable=False)
    method: Mapped[str] = mapped_column(String(8), nullable=False)
    route: Mapped[str] = mapped_column(String(256), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    state: Mapped[str] = mapped_column(String(16), nullable=False)
    response_status: Mapped[int | None] = mapped_column(Integer, nullable=True)
    response_body: Mapped[Any | None] = mapped_column(JSONB, nullable=True)
    completed_at: Mapped[datetime | None] = mapped_column(nullable=True)
    expires_at: Mapped[datetime] = mapped_column(nullable=False)


USER_OWNED_MODELS: tuple[type[UserOwnedMixin], ...] = (
    StyleProfile,
    UserPreference,
    PlatformConnection,
    Contact,
    Conversation,
    Message,
    Draft,
    Approval,
    OutboxInstruction,
    ConnectorEventIngest,
    ConversationMemory,
    AgentAction,
    AutomationRule,
    DateProposal,
    Subscription,
    UsageEvent,
    IdempotencyKey,
)
