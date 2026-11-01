"""Initial schema: all WING-003 tables, enum types, constraints and indexes.

Revision ID: 0001
Revises:
Create Date: 2026-11-01 04:28:40.594731+00:00
"""

# ruff: noqa: E501  (generated DDL: long CHECK-constraint SQL literals kept on one line)

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


# PostgreSQL enum types. Values are frozen here at authoring time and must equal the
# contract: tests/db/test_enum_drift.py compares the migrated DB with openapi.yaml.
ENUMS: dict[str, tuple[str, ...]] = {
    "intent_mode": ("serious", "casual_dating", "casual_physical", "open", "custom"),
    "automation_mode": ("manual", "assisted", "autopilot"),
    "conversation_stage": (
        "new",
        "introduction",
        "rapport",
        "interest",
        "date_proposal",
        "scheduling",
        "confirmed",
        "follow_up",
        "paused",
    ),
    "date_proposal_state": ("suggested", "offered", "accepted", "confirmed", "canceled"),
    "delivery_state": (
        "received",
        "queued",
        "sending",
        "sent",
        "delivered",
        "failed",
        "dead_lettered",
        "canceled",
    ),
    "message_direction": ("inbound", "outbound"),
    "message_origin": ("contact", "user_manual", "approved_draft", "autopilot"),
    "approval_reason": (
        "assisted_mode",
        "sensitive_content",
        "date_or_commitment",
        "low_confidence",
        "first_message",
        "outside_active_hours",
        "capability_unverified",
        "send_limit_reached",
        "user_rule",
        "paused",
    ),
    "approval_status": ("pending", "approved", "rejected", "expired", "canceled"),
    "draft_decision": ("send_candidate", "no_send", "ask_user"),
    "outbox_instruction_state": ("pending", "leased", "completed", "expired", "canceled"),
    "contact_automation_status": ("active", "paused", "taken_over"),
    "global_automation_status": ("active", "paused"),
    "actor": ("user", "agent", "dispatcher", "connector", "system"),
    "agent_action_type": (
        "message_received",
        "analysis_updated",
        "draft_generated",
        "approval_requested",
        "approval_approved",
        "approval_rejected",
        "approval_expired",
        "approval_canceled",
        "policy_blocked",
        "send_queued",
        "send_attempted",
        "send_acknowledged",
        "delivery_confirmed",
        "send_failed",
        "send_dead_lettered",
        "send_canceled",
        "automation_paused",
        "automation_resumed",
        "automation_takeover",
        "preferences_updated",
        "lease_expired",
        "connection_paired",
        "connection_unpaired",
    ),
    "connection_status": ("connected", "disconnected", "error"),
    "capability_verification": ("VERIFIED", "UNVERIFIED", "UNSUPPORTED"),
    "style_punctuation": ("minimal", "standard", "expressive"),
    "style_emoji_frequency": ("none", "rare", "occasional", "frequent"),
    "style_brevity": ("very_short", "short", "medium", "long"),
    "style_humor": ("none", "light", "playful", "witty"),
    "style_tone": ("warm", "direct", "playful", "flirty", "reserved", "formal"),
    "style_response_pace": ("immediate", "prompt", "relaxed", "slow"),
}


def upgrade() -> None:
    bind = op.get_bind()
    for name, values in ENUMS.items():
        postgresql.ENUM(*values, name=name).create(bind, checkfirst=False)

    op.create_table(
        "users",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("auth_issuer", sa.Text(), nullable=False),
        sa.Column("auth_subject", sa.Text(), nullable=False),
        sa.Column("email", sa.Text(), nullable=True),
        sa.Column("display_name", sa.String(length=120), nullable=True),
        sa.Column(
            "global_automation_status",
            postgresql.ENUM("active", "paused", name="global_automation_status", create_type=False),
            server_default="active",
            nullable=False,
        ),
        sa.Column("global_automation_changed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "char_length(auth_issuer) BETWEEN 1 AND 512", name=op.f("ck_users_auth_issuer_len")
        ),
        sa.CheckConstraint(
            "char_length(auth_subject) BETWEEN 1 AND 512", name=op.f("ck_users_auth_subject_len")
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_users")),
        sa.UniqueConstraint(
            "auth_issuer", "auth_subject", name=op.f("uq_users_auth_issuer_auth_subject")
        ),
    )
    op.create_table(
        "idempotency_keys",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("key", sa.String(length=128), nullable=False),
        sa.Column("method", sa.String(length=8), nullable=False),
        sa.Column("route", sa.String(length=256), nullable=False),
        sa.Column("request_hash", sa.String(length=64), nullable=False),
        sa.Column("state", sa.String(length=16), nullable=False),
        sa.Column("response_status", sa.Integer(), nullable=True),
        sa.Column(
            "response_body",
            postgresql.JSONB(none_as_null=True, astext_type=sa.Text()),
            nullable=True,
        ),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "(state = 'in_progress' AND response_status IS NULL AND completed_at IS NULL) OR (state = 'completed' AND response_status BETWEEN 200 AND 499 AND completed_at IS NOT NULL)",
            name=op.f("ck_idempotency_keys_state_shape"),
        ),
        sa.CheckConstraint(
            "key ~ '^[A-Za-z0-9._:-]{8,128}$'", name=op.f("ck_idempotency_keys_key_format")
        ),
        sa.CheckConstraint(
            "method IN ('POST', 'PUT', 'PATCH', 'DELETE')",
            name=op.f("ck_idempotency_keys_method_values"),
        ),
        sa.CheckConstraint(
            "request_hash ~ '^[0-9a-f]{64}$'", name=op.f("ck_idempotency_keys_request_hash_sha256")
        ),
        sa.CheckConstraint(
            "state IN ('in_progress', 'completed')", name=op.f("ck_idempotency_keys_state_values")
        ),
        sa.CheckConstraint(
            "char_length(route) BETWEEN 1 AND 256", name=op.f("ck_idempotency_keys_route_len")
        ),
        sa.CheckConstraint(
            "expires_at > created_at", name=op.f("ck_idempotency_keys_expires_after_created")
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_idempotency_keys_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_idempotency_keys")),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_idempotency_keys_user_id_id")),
        sa.UniqueConstraint(
            "user_id",
            "method",
            "route",
            "key",
            name=op.f("uq_idempotency_keys_user_id_method_route_key"),
        ),
    )
    op.create_index(
        op.f("ix_idempotency_keys_user_id_expires_at"),
        "idempotency_keys",
        ["user_id", "expires_at"],
        unique=False,
    )
    op.create_table(
        "platform_connections",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("platform", sa.String(length=32), nullable=False),
        sa.Column("display_name", sa.Text(), nullable=False),
        sa.Column(
            "status",
            postgresql.ENUM(
                "connected", "disconnected", "error", name="connection_status", create_type=False
            ),
            server_default="connected",
            nullable=False,
        ),
        sa.Column(
            "capabilities",
            postgresql.JSONB(none_as_null=True, astext_type=sa.Text()),
            nullable=False,
        ),
        sa.Column(
            "read_capability",
            postgresql.ENUM(
                "VERIFIED",
                "UNVERIFIED",
                "UNSUPPORTED",
                name="capability_verification",
                create_type=False,
            ),
            nullable=False,
        ),
        sa.Column(
            "draft_capability",
            postgresql.ENUM(
                "VERIFIED",
                "UNVERIFIED",
                "UNSUPPORTED",
                name="capability_verification",
                create_type=False,
            ),
            nullable=False,
        ),
        sa.Column(
            "send_capability",
            postgresql.ENUM(
                "VERIFIED",
                "UNVERIFIED",
                "UNSUPPORTED",
                name="capability_verification",
                create_type=False,
            ),
            nullable=False,
        ),
        sa.Column(
            "swipe_capability",
            postgresql.ENUM(
                "VERIFIED",
                "UNVERIFIED",
                "UNSUPPORTED",
                name="capability_verification",
                create_type=False,
            ),
            nullable=False,
        ),
        sa.Column(
            "calendar_write_capability",
            postgresql.ENUM(
                "VERIFIED",
                "UNVERIFIED",
                "UNSUPPORTED",
                name="capability_verification",
                create_type=False,
            ),
            nullable=False,
        ),
        # Contract 1.2.0 pairing (ADR 0007).
        sa.Column("device_install_id", sa.UUID(), nullable=True),
        sa.Column("paired_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("unpaired_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "calendar_write_capability::text IS NOT DISTINCT FROM capabilities -> 'capabilities' -> 'calendar_write' ->> 'status'",
            name=op.f("ck_platform_connections_calendar_write_capability_matches"),
        ),
        sa.CheckConstraint(
            "capabilities ->> 'platform' IS NOT DISTINCT FROM platform",
            name=op.f("ck_platform_connections_capabilities_platform_matches"),
        ),
        sa.CheckConstraint(
            "draft_capability::text IS NOT DISTINCT FROM capabilities -> 'capabilities' -> 'draft' ->> 'status'",
            name=op.f("ck_platform_connections_draft_capability_matches"),
        ),
        sa.CheckConstraint(
            "jsonb_typeof(capabilities) = 'object'",
            name=op.f("ck_platform_connections_capabilities_object"),
        ),
        sa.CheckConstraint(
            "platform ~ '^[a-z][a-z0-9_]{1,31}$'",
            name=op.f("ck_platform_connections_platform_format"),
        ),
        sa.CheckConstraint(
            "read_capability::text IS NOT DISTINCT FROM capabilities -> 'capabilities' -> 'read' ->> 'status'",
            name=op.f("ck_platform_connections_read_capability_matches"),
        ),
        sa.CheckConstraint(
            "send_capability::text IS NOT DISTINCT FROM capabilities -> 'capabilities' -> 'send' ->> 'status'",
            name=op.f("ck_platform_connections_send_capability_matches"),
        ),
        sa.CheckConstraint(
            "swipe_capability::text IS NOT DISTINCT FROM capabilities -> 'capabilities' -> 'swipe' ->> 'status'",
            name=op.f("ck_platform_connections_swipe_capability_matches"),
        ),
        sa.CheckConstraint(
            "char_length(display_name) >= 1",
            name=op.f("ck_platform_connections_display_name_nonempty"),
        ),
        sa.CheckConstraint(
            "unpaired_at IS NULL OR status = 'disconnected'",
            name=op.f("ck_platform_connections_unpaired_is_disconnected"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_platform_connections_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_platform_connections")),
        sa.UniqueConstraint(
            "user_id", "id", "platform", name=op.f("uq_platform_connections_user_id_id_platform")
        ),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_platform_connections_user_id_id")),
    )
    op.create_index(
        op.f("ix_platform_connections_user_id_created_at_id"),
        "platform_connections",
        ["user_id", "created_at", "id"],
        unique=False,
    )
    op.create_index(
        op.f("ix_platform_connections_user_id_platform_device_install_id"),
        "platform_connections",
        ["user_id", "platform", "device_install_id"],
        unique=True,
        postgresql_where=sa.text("device_install_id IS NOT NULL AND unpaired_at IS NULL"),
    )
    op.create_table(
        "style_profiles",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column(
            "punctuation",
            postgresql.ENUM(
                "minimal", "standard", "expressive", name="style_punctuation", create_type=False
            ),
            nullable=True,
        ),
        sa.Column(
            "emoji_frequency",
            postgresql.ENUM(
                "none",
                "rare",
                "occasional",
                "frequent",
                name="style_emoji_frequency",
                create_type=False,
            ),
            nullable=True,
        ),
        sa.Column(
            "brevity",
            postgresql.ENUM(
                "very_short", "short", "medium", "long", name="style_brevity", create_type=False
            ),
            nullable=True,
        ),
        sa.Column(
            "humor",
            postgresql.ENUM(
                "none", "light", "playful", "witty", name="style_humor", create_type=False
            ),
            nullable=True,
        ),
        sa.Column(
            "tone",
            postgresql.ENUM(
                "warm",
                "direct",
                "playful",
                "flirty",
                "reserved",
                "formal",
                name="style_tone",
                create_type=False,
            ),
            nullable=True,
        ),
        sa.Column(
            "response_pace",
            postgresql.ENUM(
                "immediate",
                "prompt",
                "relaxed",
                "slow",
                name="style_response_pace",
                create_type=False,
            ),
            nullable=True,
        ),
        sa.Column(
            "style_samples",
            postgresql.ARRAY(sa.Text()),
            server_default=sa.text("'{}'"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "cardinality(style_samples) <= 20", name=op.f("ck_style_profiles_style_samples_max")
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_style_profiles_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_style_profiles")),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_style_profiles_user_id_id")),
    )
    op.create_index(
        op.f("ix_style_profiles_user_id_created_at_id"),
        "style_profiles",
        ["user_id", "created_at", "id"],
        unique=False,
    )
    op.create_table(
        "subscriptions",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("plan", sa.String(length=32), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("provider", sa.String(length=32), server_default="none", nullable=False),
        sa.Column("external_subscription_id", sa.String(length=256), nullable=True),
        sa.Column("current_period_start", sa.DateTime(timezone=True), nullable=True),
        sa.Column("current_period_end", sa.DateTime(timezone=True), nullable=True),
        sa.Column("trial_ends_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("canceled_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "plan IN ('basic', 'pro', 'premium')", name=op.f("ck_subscriptions_plan_values")
        ),
        sa.CheckConstraint(
            "status IN ('trialing', 'active', 'past_due', 'canceled', 'expired')",
            name=op.f("ck_subscriptions_status_values"),
        ),
        sa.CheckConstraint(
            "current_period_end IS NULL OR current_period_start IS NULL OR current_period_end >= current_period_start",
            name=op.f("ck_subscriptions_period_range"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_subscriptions_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_subscriptions")),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_subscriptions_user_id_id")),
    )
    op.create_index(
        op.f("ix_subscriptions_user_id_created_at_id"),
        "subscriptions",
        ["user_id", "created_at", "id"],
        unique=False,
    )
    op.create_index(
        "uq_subscriptions_user_id_live",
        "subscriptions",
        ["user_id"],
        unique=True,
        postgresql_where=sa.text("status IN ('trialing', 'active', 'past_due')"),
    )
    op.create_index(
        "uq_subscriptions_user_id_provider_external_subscription_id",
        "subscriptions",
        ["user_id", "provider", "external_subscription_id"],
        unique=True,
        postgresql_where=sa.text("external_subscription_id IS NOT NULL"),
    )
    op.create_table(
        "contacts",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("connection_id", sa.UUID(), nullable=False),
        sa.Column("external_contact_id", sa.String(length=256), nullable=False),
        sa.Column("display_name", sa.String(length=200), nullable=False),
        sa.Column(
            "automation_status",
            postgresql.ENUM(
                "active",
                "paused",
                "taken_over",
                name="contact_automation_status",
                create_type=False,
            ),
            server_default="active",
            nullable=False,
        ),
        sa.Column("automation_status_changed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "char_length(external_contact_id) BETWEEN 1 AND 256",
            name=op.f("ck_contacts_external_contact_id_len"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "connection_id"],
            ["platform_connections.user_id", "platform_connections.id"],
            name=op.f("fk_contacts_user_id_connection_id_platform_connections"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"], name=op.f("fk_contacts_user_id_users"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_contacts")),
        sa.UniqueConstraint(
            "user_id",
            "connection_id",
            "external_contact_id",
            name=op.f("uq_contacts_user_id_connection_id_external_contact_id"),
        ),
        sa.UniqueConstraint(
            "user_id", "id", "connection_id", name=op.f("uq_contacts_user_id_id_connection_id")
        ),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_contacts_user_id_id")),
    )
    op.create_index(
        op.f("ix_contacts_user_id_created_at_id"),
        "contacts",
        ["user_id", "created_at", "id"],
        unique=False,
    )
    op.create_table(
        "automation_rules",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("contact_id", sa.UUID(), nullable=True),
        sa.Column("name", sa.String(length=120), nullable=False),
        sa.Column("enabled", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column("priority", sa.Integer(), server_default=sa.text("0"), nullable=False),
        sa.Column("action", sa.String(length=32), nullable=False),
        sa.Column(
            "conditions",
            postgresql.JSONB(none_as_null=True, astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "action IN ('require_approval', 'block_send')",
            name=op.f("ck_automation_rules_action_values"),
        ),
        sa.CheckConstraint(
            "jsonb_typeof(conditions) = 'object'",
            name=op.f("ck_automation_rules_conditions_object"),
        ),
        sa.CheckConstraint(
            "char_length(name) BETWEEN 1 AND 120", name=op.f("ck_automation_rules_name_len")
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "contact_id"],
            ["contacts.user_id", "contacts.id"],
            name=op.f("fk_automation_rules_user_id_contact_id_contacts"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_automation_rules_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_automation_rules")),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_automation_rules_user_id_id")),
    )
    op.create_index(
        op.f("ix_automation_rules_user_id_contact_id_priority_id"),
        "automation_rules",
        ["user_id", "contact_id", "priority", "id"],
        unique=False,
    )
    op.create_table(
        "conversations",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("contact_id", sa.UUID(), nullable=False),
        sa.Column("connection_id", sa.UUID(), nullable=False),
        sa.Column("platform", sa.String(length=32), nullable=False),
        sa.Column("external_thread_id", sa.String(length=256), nullable=True),
        sa.Column(
            "stage",
            postgresql.ENUM(
                "new",
                "introduction",
                "rapport",
                "interest",
                "date_proposal",
                "scheduling",
                "confirmed",
                "follow_up",
                "paused",
                name="conversation_stage",
                create_type=False,
            ),
            server_default="new",
            nullable=False,
        ),
        sa.Column("unread_count", sa.Integer(), server_default=sa.text("0"), nullable=False),
        sa.Column("last_message_preview", sa.Text(), nullable=True),
        sa.Column(
            "last_activity_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "analysis", postgresql.JSONB(none_as_null=True, astext_type=sa.Text()), nullable=True
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "analysis IS NULL OR jsonb_typeof(analysis) = 'object'",
            name=op.f("ck_conversations_analysis_object"),
        ),
        sa.CheckConstraint(
            "last_message_preview IS NULL OR char_length(last_message_preview) <= 160",
            name=op.f("ck_conversations_last_message_preview_len"),
        ),
        sa.CheckConstraint(
            "unread_count >= 0", name=op.f("ck_conversations_unread_count_nonnegative")
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "connection_id", "platform"],
            [
                "platform_connections.user_id",
                "platform_connections.id",
                "platform_connections.platform",
            ],
            name="fk_conversations_user_id_connection_id_platform_connections",
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "contact_id", "connection_id"],
            ["contacts.user_id", "contacts.id", "contacts.connection_id"],
            name=op.f("fk_conversations_user_id_contact_id_connection_id_contacts"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_conversations_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_conversations")),
        sa.UniqueConstraint(
            "user_id", "id", "platform", name=op.f("uq_conversations_user_id_id_platform")
        ),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_conversations_user_id_id")),
    )
    op.create_index(
        op.f("ix_conversations_user_id_contact_id_id"),
        "conversations",
        ["user_id", "contact_id", "id"],
        unique=False,
    )
    op.create_index(
        "ix_conversations_user_id_last_activity_at_id",
        "conversations",
        ["user_id", sa.literal_column("last_activity_at DESC"), sa.literal_column("id DESC")],
        unique=False,
    )
    op.create_index(
        "uq_conversations_user_id_connection_id_external_thread_id",
        "conversations",
        ["user_id", "connection_id", "external_thread_id"],
        unique=True,
        postgresql_where=sa.text("external_thread_id IS NOT NULL"),
    )
    op.create_table(
        "user_preferences",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("contact_id", sa.UUID(), nullable=True),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column(
            "intent_mode",
            postgresql.ENUM(
                "serious",
                "casual_dating",
                "casual_physical",
                "open",
                "custom",
                name="intent_mode",
                create_type=False,
            ),
            nullable=True,
        ),
        sa.Column("intent_custom_description", sa.Text(), nullable=True),
        sa.Column(
            "dealbreakers",
            postgresql.ARRAY(sa.Text()),
            server_default=sa.text("'{}'"),
            nullable=False,
        ),
        sa.Column("style_profile_id", sa.UUID(), nullable=True),
        sa.Column(
            "automation_mode",
            postgresql.ENUM(
                "manual", "assisted", "autopilot", name="automation_mode", create_type=False
            ),
            nullable=True,
        ),
        sa.Column(
            "active_hours",
            postgresql.JSONB(none_as_null=True, astext_type=sa.Text()),
            nullable=True,
        ),
        sa.Column("reply_delay_min_seconds", sa.Integer(), nullable=True),
        sa.Column("reply_delay_max_seconds", sa.Integer(), nullable=True),
        sa.Column("daily_send_limit", sa.Integer(), nullable=True),
        sa.Column(
            "approval_required_for",
            postgresql.ARRAY(
                postgresql.ENUM(
                    "assisted_mode",
                    "sensitive_content",
                    "date_or_commitment",
                    "low_confidence",
                    "first_message",
                    "outside_active_hours",
                    "capability_unverified",
                    "send_limit_reached",
                    "user_rule",
                    name="approval_reason",
                    create_type=False,
                )
            ),
            server_default=sa.text("'{}'"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "(intent_mode = 'custom' AND coalesce(char_length(intent_custom_description), 0) BETWEEN 1 AND 500) OR (intent_mode IS DISTINCT FROM 'custom' AND intent_custom_description IS NULL)",
            name=op.f("ck_user_preferences_intent_custom_description"),
        ),
        sa.CheckConstraint(
            "active_hours IS NULL OR jsonb_typeof(active_hours) = 'object'",
            name=op.f("ck_user_preferences_active_hours_object"),
        ),
        sa.CheckConstraint(
            "(reply_delay_min_seconds IS NULL) = (reply_delay_max_seconds IS NULL)",
            name=op.f("ck_user_preferences_reply_delay_pair"),
        ),
        sa.CheckConstraint(
            "cardinality(dealbreakers) <= 50", name=op.f("ck_user_preferences_dealbreakers_max")
        ),
        sa.CheckConstraint(
            "contact_id IS NOT NULL OR (intent_mode IS NOT NULL AND automation_mode IS NOT NULL AND style_profile_id IS NOT NULL)",
            name=op.f("ck_user_preferences_user_wide_required"),
        ),
        sa.CheckConstraint(
            "daily_send_limit IS NULL OR daily_send_limit BETWEEN 0 AND 1000",
            name=op.f("ck_user_preferences_daily_send_limit_range"),
        ),
        sa.CheckConstraint(
            "reply_delay_min_seconds IS NULL OR (reply_delay_min_seconds BETWEEN 0 AND 86400 AND reply_delay_max_seconds BETWEEN 0 AND 86400 AND reply_delay_min_seconds <= reply_delay_max_seconds)",
            name=op.f("ck_user_preferences_reply_delay_range"),
        ),
        sa.CheckConstraint("version >= 1", name=op.f("ck_user_preferences_version_positive")),
        sa.ForeignKeyConstraint(
            ["user_id", "contact_id"],
            ["contacts.user_id", "contacts.id"],
            name=op.f("fk_user_preferences_user_id_contact_id_contacts"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "style_profile_id"],
            ["style_profiles.user_id", "style_profiles.id"],
            name=op.f("fk_user_preferences_user_id_style_profile_id_style_profiles"),
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_user_preferences_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_user_preferences")),
        sa.UniqueConstraint(
            "user_id",
            "contact_id",
            "version",
            name=op.f("uq_user_preferences_user_id_contact_id_version"),
            postgresql_nulls_not_distinct=True,
        ),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_user_preferences_user_id_id")),
    )
    op.create_index(
        op.f("ix_user_preferences_user_id_contact_id_version_id"),
        "user_preferences",
        ["user_id", "contact_id", "version", "id"],
        unique=False,
    )
    op.create_table(
        "conversation_memories",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("contact_id", sa.UUID(), nullable=False),
        sa.Column("conversation_id", sa.UUID(), nullable=True),
        sa.Column("kind", sa.String(length=16), nullable=False),
        sa.Column("category", sa.String(length=64), nullable=True),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("confidence", sa.Float(), nullable=False),
        sa.Column(
            "source_message_ids",
            postgresql.ARRAY(sa.UUID()),
            server_default=sa.text("'{}'"),
            nullable=False,
        ),
        sa.Column("superseded_by_id", sa.UUID(), nullable=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        # WING-008 provenance (folded into 0001 per D-010).
        sa.Column(
            "origin", sa.String(length=16), server_default=sa.text("'extracted'"), nullable=False
        ),
        sa.Column(
            "basis", sa.String(length=16), server_default=sa.text("'stated'"), nullable=False
        ),
        sa.Column("fact_key", sa.String(length=64), nullable=True),
        sa.Column("stated_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "origin IN ('extracted', 'user_correction')",
            name=op.f("ck_conversation_memories_origin_values"),
        ),
        sa.CheckConstraint(
            "basis IN ('stated', 'inferred')", name=op.f("ck_conversation_memories_basis_values")
        ),
        sa.CheckConstraint(
            "kind <> 'fact' OR cardinality(source_message_ids) >= 1",
            name=op.f("ck_conversation_memories_fact_is_attributed"),
        ),
        sa.CheckConstraint(
            "kind IN ('fact', 'summary')", name=op.f("ck_conversation_memories_kind_values")
        ),
        sa.CheckConstraint(
            "char_length(content) >= 1", name=op.f("ck_conversation_memories_content_nonempty")
        ),
        sa.CheckConstraint(
            "confidence >= 0 AND confidence <= 1",
            name=op.f("ck_conversation_memories_confidence_range"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "contact_id"],
            ["contacts.user_id", "contacts.id"],
            name=op.f("fk_conversation_memories_user_id_contact_id_contacts"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "conversation_id"],
            ["conversations.user_id", "conversations.id"],
            name=op.f("fk_conversation_memories_user_id_conversation_id_conversations"),
            ondelete="SET NULL (conversation_id)",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "superseded_by_id"],
            ["conversation_memories.user_id", "conversation_memories.id"],
            name="fk_conversation_memories_user_id_superseded_by_id_self",
            ondelete="SET NULL (superseded_by_id)",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_conversation_memories_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_conversation_memories")),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_conversation_memories_user_id_id")),
    )
    op.create_index(
        op.f("ix_conversation_memories_user_id_contact_id_created_at_id"),
        "conversation_memories",
        ["user_id", "contact_id", "created_at", "id"],
        unique=False,
    )
    op.create_index(
        op.f("ix_conversation_memories_user_id_contact_id_fact_key"),
        "conversation_memories",
        ["user_id", "contact_id", "fact_key"],
        unique=False,
    )
    op.create_table(
        "date_proposals",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("conversation_id", sa.UUID(), nullable=False),
        sa.Column(
            "state",
            postgresql.ENUM(
                "suggested",
                "offered",
                "accepted",
                "confirmed",
                "canceled",
                name="date_proposal_state",
                create_type=False,
            ),
            server_default="suggested",
            nullable=False,
        ),
        sa.Column("proposed_start", sa.DateTime(timezone=True), nullable=True),
        sa.Column("proposed_end", sa.DateTime(timezone=True), nullable=True),
        sa.Column("venue_text", sa.Text(), nullable=True),
        sa.Column(
            "confirmation_message_ids",
            postgresql.ARRAY(sa.UUID()),
            server_default=sa.text("'{}'"),
            nullable=False,
        ),
        sa.Column("calendar_event_id", sa.String(length=256), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "state <> 'confirmed' OR (proposed_start IS NOT NULL AND cardinality(confirmation_message_ids) >= 1)",
            name=op.f("ck_date_proposals_confirmed_requires_evidence"),
        ),
        sa.CheckConstraint(
            "proposed_end IS NULL OR proposed_start IS NULL OR proposed_end >= proposed_start",
            name=op.f("ck_date_proposals_proposed_range"),
        ),
        sa.CheckConstraint(
            "venue_text IS NULL OR char_length(venue_text) <= 300",
            name=op.f("ck_date_proposals_venue_text_len"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "conversation_id"],
            ["conversations.user_id", "conversations.id"],
            name=op.f("fk_date_proposals_user_id_conversation_id_conversations"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_date_proposals_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_date_proposals")),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_date_proposals_user_id_id")),
    )
    op.create_index(
        op.f("ix_date_proposals_user_id_conversation_id_created_at_id"),
        "date_proposals",
        ["user_id", "conversation_id", "created_at", "id"],
        unique=False,
    )
    op.create_index(
        "uq_date_proposals_user_id_calendar_event_id",
        "date_proposals",
        ["user_id", "calendar_event_id"],
        unique=True,
        postgresql_where=sa.text("calendar_event_id IS NOT NULL"),
    )
    op.create_table(
        "messages",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("conversation_id", sa.UUID(), nullable=False),
        sa.Column("platform", sa.String(length=32), nullable=False),
        sa.Column(
            "direction",
            postgresql.ENUM("inbound", "outbound", name="message_direction", create_type=False),
            nullable=False,
        ),
        sa.Column(
            "origin",
            postgresql.ENUM(
                "contact",
                "user_manual",
                "approved_draft",
                "autopilot",
                name="message_origin",
                create_type=False,
            ),
            nullable=False,
        ),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column(
            "delivery_state",
            postgresql.ENUM(
                "received",
                "queued",
                "sending",
                "sent",
                "delivered",
                "failed",
                "dead_lettered",
                "canceled",
                name="delivery_state",
                create_type=False,
            ),
            nullable=False,
        ),
        sa.Column("external_message_id", sa.String(length=256), nullable=True),
        sa.Column("approval_id", sa.UUID(), nullable=True),
        sa.Column("attempt_count", sa.Integer(), server_default=sa.text("0"), nullable=False),
        sa.Column("failure_code", sa.Text(), nullable=True),
        sa.Column("failure_detail", sa.Text(), nullable=True),
        sa.Column("failure_retryable", sa.Boolean(), nullable=True),
        sa.Column("sent_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("delivered_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "ingested_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("send_after", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "delivery_state <> 'delivered' OR (sent_at IS NOT NULL AND delivered_at IS NOT NULL)",
            name=op.f("ck_messages_delivered_has_timestamps"),
        ),
        sa.CheckConstraint(
            "delivery_state NOT IN ('queued', 'sending', 'canceled') OR (sent_at IS NULL AND delivered_at IS NULL)",
            name=op.f("ck_messages_unsent_has_no_sent_at"),
        ),
        sa.CheckConstraint(
            "direction <> 'inbound' OR (origin = 'contact' AND delivery_state = 'received' AND external_message_id IS NOT NULL)",
            name=op.f("ck_messages_inbound_shape"),
        ),
        sa.CheckConstraint(
            "direction <> 'outbound' OR (origin <> 'contact' AND delivery_state <> 'received')",
            name=op.f("ck_messages_outbound_shape"),
        ),
        sa.CheckConstraint(
            "(failure_code IS NULL) = (failure_retryable IS NULL) AND (failure_code IS NOT NULL OR failure_detail IS NULL)",
            name=op.f("ck_messages_failure_shape"),
        ),
        sa.CheckConstraint(
            "attempt_count >= 0", name=op.f("ck_messages_attempt_count_nonnegative")
        ),
        sa.CheckConstraint(
            "send_after IS NULL OR direction = 'outbound'",
            name=op.f("ck_messages_send_after_outbound_only"),
        ),
        sa.CheckConstraint(
            "external_message_id IS NULL OR char_length(external_message_id) BETWEEN 1 AND 256",
            name=op.f("ck_messages_external_message_id_len"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "conversation_id", "platform"],
            ["conversations.user_id", "conversations.id", "conversations.platform"],
            name=op.f("fk_messages_user_id_conversation_id_platform_conversations"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"], name=op.f("fk_messages_user_id_users"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_messages")),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_messages_user_id_id")),
        sa.UniqueConstraint(
            "user_id",
            "platform",
            "external_message_id",
            name=op.f("uq_messages_user_id_platform_external_message_id"),
        ),
    )
    op.create_index(
        op.f("ix_messages_user_id_conversation_id_created_at_id"),
        "messages",
        ["user_id", "conversation_id", "created_at", "id"],
        unique=False,
    )
    op.create_index(
        op.f("ix_messages_user_id_delivery_state_created_at_id"),
        "messages",
        ["user_id", "delivery_state", "created_at", "id"],
        unique=False,
    )
    op.create_table(
        "agent_actions",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column(
            "type",
            postgresql.ENUM(
                "message_received",
                "analysis_updated",
                "draft_generated",
                "approval_requested",
                "approval_approved",
                "approval_rejected",
                "approval_expired",
                "approval_canceled",
                "policy_blocked",
                "send_queued",
                "send_attempted",
                "send_acknowledged",
                "delivery_confirmed",
                "send_failed",
                "send_dead_lettered",
                "send_canceled",
                "automation_paused",
                "automation_resumed",
                "automation_takeover",
                "preferences_updated",
                name="agent_action_type",
                create_type=False,
            ),
            nullable=False,
        ),
        sa.Column(
            "actor",
            postgresql.ENUM(
                "user",
                "agent",
                "dispatcher",
                "connector",
                "system",
                name="actor",
                create_type=False,
            ),
            nullable=False,
        ),
        sa.Column("conversation_id", sa.UUID(), nullable=True),
        sa.Column("contact_id", sa.UUID(), nullable=True),
        sa.Column("message_id", sa.UUID(), nullable=True),
        sa.Column("approval_id", sa.UUID(), nullable=True),
        sa.Column("reason_code", sa.String(length=64), nullable=True),
        sa.Column(
            "occurred_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "details",
            postgresql.JSONB(none_as_null=True, astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "jsonb_typeof(details) = 'object'", name=op.f("ck_agent_actions_details_object")
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "contact_id"],
            ["contacts.user_id", "contacts.id"],
            name=op.f("fk_agent_actions_user_id_contact_id_contacts"),
            ondelete="SET NULL (contact_id)",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "conversation_id"],
            ["conversations.user_id", "conversations.id"],
            name=op.f("fk_agent_actions_user_id_conversation_id_conversations"),
            ondelete="SET NULL (conversation_id)",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "message_id"],
            ["messages.user_id", "messages.id"],
            name=op.f("fk_agent_actions_user_id_message_id_messages"),
            ondelete="SET NULL (message_id)",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_agent_actions_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_agent_actions")),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_agent_actions_user_id_id")),
    )
    op.create_index(
        "ix_agent_actions_user_id_contact_id_occurred_at_id",
        "agent_actions",
        [
            "user_id",
            "contact_id",
            sa.literal_column("occurred_at DESC"),
            sa.literal_column("id DESC"),
        ],
        unique=False,
    )
    op.create_index(
        "ix_agent_actions_user_id_conversation_id_occurred_at_id",
        "agent_actions",
        [
            "user_id",
            "conversation_id",
            sa.literal_column("occurred_at DESC"),
            sa.literal_column("id DESC"),
        ],
        unique=False,
    )
    op.create_index(
        "ix_agent_actions_user_id_occurred_at_id",
        "agent_actions",
        ["user_id", sa.literal_column("occurred_at DESC"), sa.literal_column("id DESC")],
        unique=False,
    )
    op.create_table(
        "usage_events",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("event_type", sa.String(length=64), nullable=False),
        sa.Column("dedup_key", sa.String(length=256), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("quantity", sa.BigInteger(), server_default=sa.text("1"), nullable=False),
        sa.Column("unit", sa.String(length=32), nullable=True),
        sa.Column("cost_micros", sa.BigInteger(), nullable=True),
        sa.Column("conversation_id", sa.UUID(), nullable=True),
        sa.Column("connection_id", sa.UUID(), nullable=True),
        sa.Column("message_id", sa.UUID(), nullable=True),
        sa.Column(
            "attributes",
            postgresql.JSONB(none_as_null=True, astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "event_type ~ '^[a-z][a-z0-9_.]{1,63}$'", name=op.f("ck_usage_events_event_type_format")
        ),
        sa.CheckConstraint(
            "jsonb_typeof(attributes) = 'object'", name=op.f("ck_usage_events_attributes_object")
        ),
        sa.CheckConstraint(
            "char_length(dedup_key) BETWEEN 1 AND 256", name=op.f("ck_usage_events_dedup_key_len")
        ),
        sa.CheckConstraint(
            "cost_micros IS NULL OR cost_micros >= 0", name=op.f("ck_usage_events_cost_nonnegative")
        ),
        sa.CheckConstraint("quantity >= 0", name=op.f("ck_usage_events_quantity_nonnegative")),
        sa.ForeignKeyConstraint(
            ["user_id", "connection_id"],
            ["platform_connections.user_id", "platform_connections.id"],
            name=op.f("fk_usage_events_user_id_connection_id_platform_connections"),
            ondelete="SET NULL (connection_id)",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "conversation_id"],
            ["conversations.user_id", "conversations.id"],
            name=op.f("fk_usage_events_user_id_conversation_id_conversations"),
            ondelete="SET NULL (conversation_id)",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "message_id"],
            ["messages.user_id", "messages.id"],
            name=op.f("fk_usage_events_user_id_message_id_messages"),
            ondelete="SET NULL (message_id)",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_usage_events_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_usage_events")),
        sa.UniqueConstraint(
            "user_id",
            "event_type",
            "dedup_key",
            name=op.f("uq_usage_events_user_id_event_type_dedup_key"),
        ),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_usage_events_user_id_id")),
    )
    op.create_index(
        op.f("ix_usage_events_user_id_event_type_occurred_at_id"),
        "usage_events",
        ["user_id", "event_type", "occurred_at", "id"],
        unique=False,
    )
    op.create_index(
        op.f("ix_usage_events_user_id_occurred_at_id"),
        "usage_events",
        ["user_id", "occurred_at", "id"],
        unique=False,
    )

    # ---- WING-011 (D-010: folded into 0001): drafts, approvals, device outbox, event
    # ingest record, and the approval FKs that messages/agent_actions were waiting for.
    op.create_table(
        "drafts",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("conversation_id", sa.UUID(), nullable=False),
        sa.Column("contact_id", sa.UUID(), nullable=False),
        sa.Column(
            "decision",
            postgresql.ENUM(
                "send_candidate", "no_send", "ask_user", name="draft_decision", create_type=False
            ),
            nullable=False,
        ),
        sa.Column("text", sa.Text(), nullable=True),
        sa.Column("question_for_user", sa.Text(), nullable=True),
        sa.Column("rationale", sa.Text(), nullable=False),
        sa.Column("confidence", sa.Float(), nullable=False),
        sa.Column("requires_approval", sa.Boolean(), nullable=False),
        sa.Column(
            "approval_reasons",
            postgresql.ARRAY(
                postgresql.ENUM(
                    "assisted_mode",
                    "sensitive_content",
                    "date_or_commitment",
                    "low_confidence",
                    "first_message",
                    "outside_active_hours",
                    "capability_unverified",
                    "send_limit_reached",
                    "user_rule",
                    name="approval_reason",
                    create_type=False,
                )
            ),
            server_default=sa.text("'{}'"),
            nullable=False,
        ),
        sa.Column(
            "intent_mode",
            postgresql.ENUM(
                "serious",
                "casual_dating",
                "casual_physical",
                "open",
                "custom",
                name="intent_mode",
                create_type=False,
            ),
            nullable=False,
        ),
        sa.Column(
            "grounding_memory_ids",
            postgresql.ARRAY(sa.UUID()),
            server_default=sa.text("'{}'"),
            nullable=False,
        ),
        sa.Column(
            "grounding_message_ids",
            postgresql.ARRAY(sa.UUID()),
            server_default=sa.text("'{}'"),
            nullable=False,
        ),
        sa.Column("reason", sa.String(length=64), nullable=False),
        sa.Column("trigger_message_id", sa.UUID(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "(decision = 'send_candidate') = (text IS NOT NULL)",
            name=op.f("ck_drafts_text_iff_send_candidate"),
        ),
        sa.CheckConstraint(
            "decision <> 'ask_user' OR question_for_user IS NOT NULL",
            name=op.f("ck_drafts_ask_user_question"),
        ),
        sa.CheckConstraint(
            "confidence >= 0 AND confidence <= 1", name=op.f("ck_drafts_confidence_range")
        ),
        sa.CheckConstraint(
            "requires_approval = (cardinality(approval_reasons) > 0)",
            name=op.f("ck_drafts_requires_approval_matches_reasons"),
        ),
        sa.CheckConstraint(
            "text IS NULL OR char_length(text) BETWEEN 1 AND 4000", name=op.f("ck_drafts_text_len")
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "trigger_message_id"],
            ["messages.user_id", "messages.id"],
            name=op.f("fk_drafts_user_id_trigger_message_id_messages"),
            ondelete="SET NULL (trigger_message_id)",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "contact_id"],
            ["contacts.user_id", "contacts.id"],
            name=op.f("fk_drafts_user_id_contact_id_contacts"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "conversation_id"],
            ["conversations.user_id", "conversations.id"],
            name=op.f("fk_drafts_user_id_conversation_id_conversations"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"], name=op.f("fk_drafts_user_id_users"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_drafts")),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_drafts_user_id_id")),
    )
    op.create_index(
        "uq_drafts_user_id_trigger_message_id",
        "drafts",
        ["user_id", "trigger_message_id"],
        unique=True,
        postgresql_where=sa.text("trigger_message_id IS NOT NULL"),
    )
    op.create_index(
        op.f("ix_drafts_user_id_conversation_id_created_at_id"),
        "drafts",
        ["user_id", "conversation_id", "created_at", "id"],
        unique=False,
    )
    op.create_table(
        "approvals",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("conversation_id", sa.UUID(), nullable=False),
        sa.Column("contact_id", sa.UUID(), nullable=False),
        sa.Column("draft_id", sa.UUID(), nullable=False),
        sa.Column(
            "status",
            postgresql.ENUM(
                "pending",
                "approved",
                "rejected",
                "expired",
                "canceled",
                name="approval_status",
                create_type=False,
            ),
            server_default="pending",
            nullable=False,
        ),
        sa.Column(
            "reasons",
            postgresql.ARRAY(
                postgresql.ENUM(
                    "assisted_mode",
                    "sensitive_content",
                    "date_or_commitment",
                    "low_confidence",
                    "first_message",
                    "outside_active_hours",
                    "capability_unverified",
                    "send_limit_reached",
                    "user_rule",
                    name="approval_reason",
                    create_type=False,
                )
            ),
            nullable=False,
        ),
        sa.Column("final_text", sa.Text(), nullable=True),
        sa.Column("rejection_reason", sa.Text(), nullable=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("decided_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "(status = 'approved') = (final_text IS NOT NULL)",
            name=op.f("ck_approvals_final_text_iff_approved"),
        ),
        sa.CheckConstraint(
            "(status = 'pending') = (decided_at IS NULL)",
            name=op.f("ck_approvals_decided_iff_not_pending"),
        ),
        sa.CheckConstraint(
            "rejection_reason IS NULL OR (status = 'rejected' AND char_length(rejection_reason) <= 500)",
            name=op.f("ck_approvals_rejection_reason_shape"),
        ),
        sa.CheckConstraint("cardinality(reasons) >= 1", name=op.f("ck_approvals_reasons_nonempty")),
        sa.CheckConstraint(
            "final_text IS NULL OR char_length(final_text) BETWEEN 1 AND 4000",
            name=op.f("ck_approvals_final_text_len"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "contact_id"],
            ["contacts.user_id", "contacts.id"],
            name=op.f("fk_approvals_user_id_contact_id_contacts"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "conversation_id"],
            ["conversations.user_id", "conversations.id"],
            name=op.f("fk_approvals_user_id_conversation_id_conversations"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "draft_id"],
            ["drafts.user_id", "drafts.id"],
            name=op.f("fk_approvals_user_id_draft_id_drafts"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"], name=op.f("fk_approvals_user_id_users"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_approvals")),
        sa.UniqueConstraint("user_id", "draft_id", name=op.f("uq_approvals_user_id_draft_id")),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_approvals_user_id_id")),
    )
    op.create_index(
        op.f("ix_approvals_user_id_contact_id_status"),
        "approvals",
        ["user_id", "contact_id", "status"],
        unique=False,
    )
    op.create_index(
        "ix_approvals_user_id_status_created_at_id",
        "approvals",
        ["user_id", "status", sa.literal_column("created_at DESC"), sa.literal_column("id DESC")],
        unique=False,
    )
    op.create_index(
        "uq_approvals_user_id_conversation_id_pending",
        "approvals",
        ["user_id", "conversation_id"],
        unique=True,
        postgresql_where=sa.text("status = 'pending'"),
    )
    op.create_table(
        "connector_event_ingests",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("event_id", sa.UUID(), nullable=False),
        sa.Column("connection_id", sa.UUID(), nullable=False),
        sa.Column("event_type", sa.String(length=64), nullable=False),
        sa.Column("request_hash", sa.String(length=64), nullable=False),
        sa.Column("outcome", sa.String(length=32), nullable=False),
        sa.Column("message_id", sa.UUID(), nullable=True),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("deferred", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "event_type ~ '^[a-z][a-z_.]{1,63}$'",
            name=op.f("ck_connector_event_ingests_event_type_format"),
        ),
        sa.CheckConstraint(
            "outcome IN ('accepted', 'duplicate_message')",
            name=op.f("ck_connector_event_ingests_outcome_values"),
        ),
        sa.CheckConstraint(
            "request_hash ~ '^[0-9a-f]{64}$'",
            name=op.f("ck_connector_event_ingests_request_hash_sha256"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "connection_id"],
            ["platform_connections.user_id", "platform_connections.id"],
            name=op.f("fk_connector_event_ingests_user_id_connection_id_platform_connections"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "message_id"],
            ["messages.user_id", "messages.id"],
            name=op.f("fk_connector_event_ingests_user_id_message_id_messages"),
            ondelete="SET NULL (message_id)",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_connector_event_ingests_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_connector_event_ingests")),
        sa.UniqueConstraint(
            "user_id", "event_id", name=op.f("uq_connector_event_ingests_user_id_event_id")
        ),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_connector_event_ingests_user_id_id")),
    )
    op.create_index(
        "ix_connector_event_ingests_user_id_message_id_deferred",
        "connector_event_ingests",
        ["user_id", "message_id"],
        unique=False,
        postgresql_where=sa.text("deferred"),
    )
    op.create_table(
        "outbox_instructions",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("connection_id", sa.UUID(), nullable=False),
        sa.Column("outbound_message_id", sa.UUID(), nullable=False),
        sa.Column("attempt", sa.Integer(), nullable=False),
        sa.Column("idempotency_key", sa.String(length=128), nullable=False),
        sa.Column(
            "state",
            postgresql.ENUM(
                "pending",
                "leased",
                "completed",
                "expired",
                "canceled",
                name="outbox_instruction_state",
                create_type=False,
            ),
            server_default="pending",
            nullable=False,
        ),
        sa.Column("device_install_id", sa.UUID(), nullable=True),
        sa.Column("leased_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("lease_expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "(state = 'completed') = (completed_at IS NOT NULL)",
            name=op.f("ck_outbox_instructions_completed_shape"),
        ),
        sa.CheckConstraint(
            "(state IN ('pending', 'canceled')) = (leased_at IS NULL) AND (leased_at IS NULL) = (lease_expires_at IS NULL) AND (leased_at IS NULL) = (device_install_id IS NULL)",
            name=op.f("ck_outbox_instructions_lease_shape"),
        ),
        sa.CheckConstraint(
            "idempotency_key ~ '^[A-Za-z0-9._:-]{8,128}$'",
            name=op.f("ck_outbox_instructions_idempotency_key_format"),
        ),
        sa.CheckConstraint("attempt >= 1", name=op.f("ck_outbox_instructions_attempt_positive")),
        sa.CheckConstraint(
            "lease_expires_at IS NULL OR lease_expires_at > leased_at",
            name=op.f("ck_outbox_instructions_lease_after_start"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "connection_id"],
            ["platform_connections.user_id", "platform_connections.id"],
            name=op.f("fk_outbox_instructions_user_id_connection_id_platform_connections"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id", "outbound_message_id"],
            ["messages.user_id", "messages.id"],
            name=op.f("fk_outbox_instructions_user_id_outbound_message_id_messages"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_outbox_instructions_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_outbox_instructions")),
        sa.UniqueConstraint("user_id", "id", name=op.f("uq_outbox_instructions_user_id_id")),
        sa.UniqueConstraint(
            "user_id",
            "idempotency_key",
            name=op.f("uq_outbox_instructions_user_id_idempotency_key"),
        ),
        sa.UniqueConstraint(
            "user_id",
            "outbound_message_id",
            "attempt",
            name=op.f("uq_outbox_instructions_user_id_outbound_message_id_attempt"),
        ),
    )
    op.create_index(
        op.f("ix_outbox_instructions_user_id_connection_id_state_created_at_id"),
        "outbox_instructions",
        ["user_id", "connection_id", "state", "created_at", "id"],
        unique=False,
    )
    op.create_index(
        "ix_outbox_instructions_user_id_lease_expires_at",
        "outbox_instructions",
        ["user_id", "lease_expires_at"],
        unique=False,
        postgresql_where=sa.text("state = 'leased'"),
    )
    op.create_index(
        "uq_outbox_instructions_user_id_outbound_message_id_live",
        "outbox_instructions",
        ["user_id", "outbound_message_id"],
        unique=True,
        postgresql_where=sa.text("state IN ('pending', 'leased')"),
    )
    op.create_foreign_key(
        op.f("fk_agent_actions_user_id_approval_id_approvals"),
        "agent_actions",
        "approvals",
        ["user_id", "approval_id"],
        ["user_id", "id"],
        ondelete="SET NULL (approval_id)",
    )
    op.create_index(
        "uq_messages_user_id_approval_id",
        "messages",
        ["user_id", "approval_id"],
        unique=True,
        postgresql_where=sa.text("approval_id IS NOT NULL"),
    )
    op.create_foreign_key(
        op.f("fk_messages_user_id_approval_id_approvals"),
        "messages",
        "approvals",
        ["user_id", "approval_id"],
        ["user_id", "id"],
        ondelete="SET NULL (approval_id)",
    )


def downgrade() -> None:
    # ---- WING-011
    op.drop_constraint(
        op.f("fk_messages_user_id_approval_id_approvals"), "messages", type_="foreignkey"
    )
    op.drop_index(
        "uq_messages_user_id_approval_id",
        table_name="messages",
        postgresql_where=sa.text("approval_id IS NOT NULL"),
    )
    op.drop_constraint(
        op.f("fk_agent_actions_user_id_approval_id_approvals"), "agent_actions", type_="foreignkey"
    )
    op.drop_index(
        "uq_outbox_instructions_user_id_outbound_message_id_live",
        table_name="outbox_instructions",
        postgresql_where=sa.text("state IN ('pending', 'leased')"),
    )
    op.drop_index(
        "ix_outbox_instructions_user_id_lease_expires_at",
        table_name="outbox_instructions",
        postgresql_where=sa.text("state = 'leased'"),
    )
    op.drop_index(
        op.f("ix_outbox_instructions_user_id_connection_id_state_created_at_id"),
        table_name="outbox_instructions",
    )
    op.drop_table("outbox_instructions")
    op.drop_index(
        "ix_connector_event_ingests_user_id_message_id_deferred",
        table_name="connector_event_ingests",
        postgresql_where=sa.text("deferred"),
    )
    op.drop_table("connector_event_ingests")
    op.drop_index(
        "uq_approvals_user_id_conversation_id_pending",
        table_name="approvals",
        postgresql_where=sa.text("status = 'pending'"),
    )
    op.drop_index("ix_approvals_user_id_status_created_at_id", table_name="approvals")
    op.drop_index(op.f("ix_approvals_user_id_contact_id_status"), table_name="approvals")
    op.drop_table("approvals")
    op.drop_index(
        "uq_drafts_user_id_trigger_message_id",
        table_name="drafts",
        postgresql_where=sa.text("trigger_message_id IS NOT NULL"),
    )
    op.drop_index(op.f("ix_drafts_user_id_conversation_id_created_at_id"), table_name="drafts")
    op.drop_table("drafts")
    op.drop_index(op.f("ix_usage_events_user_id_occurred_at_id"), table_name="usage_events")
    op.drop_index(
        op.f("ix_usage_events_user_id_event_type_occurred_at_id"), table_name="usage_events"
    )
    op.drop_table("usage_events")
    op.drop_index("ix_agent_actions_user_id_occurred_at_id", table_name="agent_actions")
    op.drop_index(
        "ix_agent_actions_user_id_conversation_id_occurred_at_id", table_name="agent_actions"
    )
    op.drop_index("ix_agent_actions_user_id_contact_id_occurred_at_id", table_name="agent_actions")
    op.drop_table("agent_actions")
    op.drop_index(op.f("ix_messages_user_id_delivery_state_created_at_id"), table_name="messages")
    op.drop_index(op.f("ix_messages_user_id_conversation_id_created_at_id"), table_name="messages")
    op.drop_table("messages")
    op.drop_index(
        "uq_date_proposals_user_id_calendar_event_id",
        table_name="date_proposals",
        postgresql_where=sa.text("calendar_event_id IS NOT NULL"),
    )
    op.drop_index(
        op.f("ix_date_proposals_user_id_conversation_id_created_at_id"), table_name="date_proposals"
    )
    op.drop_table("date_proposals")
    op.drop_index(
        op.f("ix_conversation_memories_user_id_contact_id_fact_key"),
        table_name="conversation_memories",
    )
    op.drop_index(
        op.f("ix_conversation_memories_user_id_contact_id_created_at_id"),
        table_name="conversation_memories",
    )
    op.drop_table("conversation_memories")
    op.drop_index(
        op.f("ix_user_preferences_user_id_contact_id_version_id"), table_name="user_preferences"
    )
    op.drop_table("user_preferences")
    op.drop_index(
        "uq_conversations_user_id_connection_id_external_thread_id",
        table_name="conversations",
        postgresql_where=sa.text("external_thread_id IS NOT NULL"),
    )
    op.drop_index("ix_conversations_user_id_last_activity_at_id", table_name="conversations")
    op.drop_index(op.f("ix_conversations_user_id_contact_id_id"), table_name="conversations")
    op.drop_table("conversations")
    op.drop_index(
        op.f("ix_automation_rules_user_id_contact_id_priority_id"), table_name="automation_rules"
    )
    op.drop_table("automation_rules")
    op.drop_index(op.f("ix_contacts_user_id_created_at_id"), table_name="contacts")
    op.drop_table("contacts")
    op.drop_index(
        "uq_subscriptions_user_id_provider_external_subscription_id",
        table_name="subscriptions",
        postgresql_where=sa.text("external_subscription_id IS NOT NULL"),
    )
    op.drop_index(
        "uq_subscriptions_user_id_live",
        table_name="subscriptions",
        postgresql_where=sa.text("status IN ('trialing', 'active', 'past_due')"),
    )
    op.drop_index(op.f("ix_subscriptions_user_id_created_at_id"), table_name="subscriptions")
    op.drop_table("subscriptions")
    op.drop_index(op.f("ix_style_profiles_user_id_created_at_id"), table_name="style_profiles")
    op.drop_table("style_profiles")
    op.drop_index(
        op.f("ix_platform_connections_user_id_created_at_id"), table_name="platform_connections"
    )
    op.drop_table("platform_connections")
    op.drop_index(op.f("ix_idempotency_keys_user_id_expires_at"), table_name="idempotency_keys")
    op.drop_table("idempotency_keys")
    op.drop_table("users")
    bind = op.get_bind()
    for name in reversed(ENUMS):
        postgresql.ENUM(name=name).drop(bind, checkfirst=False)
