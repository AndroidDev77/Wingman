"""Negative paths: FK violations, invalid enum values, contract CHECK rules, conventions."""

import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError, IntegrityError, StatementError
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.base import Base
from app.db.enums import (
    Actor,
    AgentActionType,
    AutomationMode,
    DateProposalState,
    DeliveryState,
    IntentMode,
    MessageOrigin,
)
from app.db.errors import VersionConflictError
from app.db.models import AgentAction, Message, Subscription
from app.db.repositories import (
    AgentActionRepository,
    ConnectionRepository,
    ContactRepository,
    ConversationRepository,
    DateProposalRepository,
    MemoryRepository,
    MessageRepository,
    PreferenceRepository,
    StyleProfileRepository,
    SubscriptionRepository,
    UsageEventRepository,
    UserRepository,
)
from app.db.scope import UserScope
from tests.db.conftest import capabilities_doc, make_connection, make_user

T0 = datetime(2026, 11, 1, 12, 0, tzinfo=UTC)


async def _thread(session: AsyncSession, scope: UserScope) -> tuple[uuid.UUID, uuid.UUID]:
    conn_id = await make_connection(session, scope)
    contact = await ContactRepository(session, scope).get_or_create(
        connection_id=conn_id, external_contact_id="c", display_name="Sam"
    )
    conv = await ConversationRepository(session, scope).create(
        contact_id=contact.id, connection_id=conn_id, platform="mock"
    )
    return contact.id, conv.id


async def _fails(session: AsyncSession, sql: str, params: dict[str, Any], match: str) -> None:
    with pytest.raises((IntegrityError, DBAPIError), match=match):
        async with session.begin_nested():
            await session.execute(text(sql), params)


# ------------------------------------------------------------------ foreign keys


async def test_fk_unknown_user_rejected(session: AsyncSession) -> None:
    ghost = UserScope(uuid.uuid4())
    with pytest.raises(IntegrityError, match="fk_style_profiles_user_id_users"):
        async with session.begin_nested():
            await StyleProfileRepository(session, ghost).create()


async def test_fk_unknown_parents_rejected(session: AsyncSession) -> None:
    scope = await make_user(session)
    with pytest.raises(IntegrityError, match="fk_contacts_user_id_connection_id"):
        async with session.begin_nested():
            await ContactRepository(session, scope).get_or_create(
                connection_id=uuid.uuid4(), external_contact_id="x", display_name="x"
            )
    contact_id, _ = await _thread(session, scope)
    with pytest.raises(IntegrityError, match="fk_date_proposals_user_id_conversation_id"):
        async with session.begin_nested():
            await DateProposalRepository(session, scope).create(conversation_id=uuid.uuid4())
    with pytest.raises(IntegrityError, match="fk_agent_actions_user_id_message_id"):
        async with session.begin_nested():
            await AgentActionRepository(session, scope).record(
                type=AgentActionType.SEND_QUEUED, actor=Actor.DISPATCHER, message_id=uuid.uuid4()
            )
    assert contact_id


async def test_conversation_contact_must_be_on_same_connection(session: AsyncSession) -> None:
    scope = await make_user(session)
    contact_id, _ = await _thread(session, scope)
    other_conn = await make_connection(session, scope, "sms")
    with pytest.raises(IntegrityError, match="fk_conversations_user_id_contact_id_connection_id"):
        async with session.begin_nested():
            await ConversationRepository(session, scope).create(
                contact_id=contact_id, connection_id=other_conn, platform="sms"
            )


async def test_deleting_user_cascades_and_audit_survives_message_delete(
    session: AsyncSession,
) -> None:
    scope = await make_user(session)
    _, conv_id = await _thread(session, scope)
    msg = await MessageRepository(session, scope).ingest_inbound(
        conversation_id=conv_id,
        platform="mock",
        external_message_id="m",
        text="x",
        sent_at_source=T0,
    )
    act = await AgentActionRepository(session, scope).record(
        type=AgentActionType.MESSAGE_RECEIVED,
        actor=Actor.CONNECTOR,
        message_id=msg.message.id,
        conversation_id=conv_id,
    )
    await session.execute(text("DELETE FROM messages WHERE id = :id"), {"id": msg.message.id})
    await session.refresh(act)
    assert act.message_id is None, "audit entry outlives the message (SET NULL of that column)"
    assert act.user_id == scope.user_id and act.conversation_id == conv_id

    await session.execute(text("DELETE FROM users WHERE id = :id"), {"id": scope.user_id})
    for table in ("platform_connections", "conversations", "agent_actions", "contacts"):
        n = await session.scalar(
            text(f"SELECT count(*) FROM {table} WHERE user_id = :u"),  # noqa: S608
            {"u": scope.user_id},
        )
        assert n == 0, table


# ------------------------------------------------------------------ enums


@pytest.mark.parametrize(
    ("table", "column"),
    [
        ("conversations", "stage"),
        ("messages", "delivery_state"),
        ("agent_actions", "type"),
        ("date_proposals", "state"),
        ("user_preferences", "intent_mode"),
        ("platform_connections", "send_capability"),
        ("contacts", "automation_status"),
    ],
)
async def test_invalid_enum_value_rejected_by_database(
    session: AsyncSession, table: str, column: str
) -> None:
    await _fails(
        session,
        f"UPDATE {table} SET {column} = 'definitely_not_valid' WHERE false",  # noqa: S608
        {},
        "invalid input value for enum",
    )


async def test_invalid_enum_value_rejected_by_orm(session: AsyncSession) -> None:
    scope = await make_user(session)
    with pytest.raises((StatementError, LookupError, ValueError)):
        async with session.begin_nested():
            session.add(
                AgentAction(user_id=scope.user_id, type="made_up", actor="agent", details={})
            )
            await session.flush()


async def test_unknown_approval_reason_rejected(session: AsyncSession) -> None:
    scope = await make_user(session)
    style = await StyleProfileRepository(session, scope).create()
    with pytest.raises((StatementError, DBAPIError, ValueError)):
        async with session.begin_nested():
            await PreferenceRepository(session, scope).save_version(
                expected_version=0,
                intent_mode=IntentMode.OPEN,
                style_profile_id=style.id,
                automation_mode=AutomationMode.MANUAL,
                approval_required_for=["not_a_reason"],  # type: ignore[list-item]
            )


async def test_capabilities_must_state_all_five(session: AsyncSession) -> None:
    scope = await make_user(session)
    doc = capabilities_doc("mock")
    del doc["capabilities"]["swipe"]
    with pytest.raises(ValueError, match="swipe"):
        await ConnectionRepository(session, scope).create(
            platform="mock", display_name="m", capabilities=doc
        )


async def test_capability_columns_must_match_document(session: AsyncSession) -> None:
    scope = await make_user(session)
    conn_id = await make_connection(session, scope)
    await _fails(
        session,
        "UPDATE platform_connections SET send_capability = 'UNSUPPORTED' WHERE id = :id",
        {"id": conn_id},
        "ck_platform_connections_send_capability_matches",
    )
    conn = await ConnectionRepository(session, scope).set_capabilities(
        conn_id, capabilities_doc("mock", send="UNVERIFIED")
    )
    assert conn.send_capability.value == "UNVERIFIED"


# ------------------------------------------------------------------ contract CHECK rules


async def test_message_state_rules(session: AsyncSession) -> None:
    scope = await make_user(session)
    _, conv_id = await _thread(session, scope)
    repo = MessageRepository(session, scope)
    out = await repo.create_outbound(
        conversation_id=conv_id, platform="mock", origin=MessageOrigin.AUTOPILOT, text="hi"
    )
    with pytest.raises(IntegrityError, match="ck_messages_delivered_has_timestamps"):
        async with session.begin_nested():
            await repo.update_delivery(out.id, delivery_state=DeliveryState.DELIVERED)
    with pytest.raises(IntegrityError, match="ck_messages_unsent_has_no_sent_at"):
        async with session.begin_nested():
            await repo.update_delivery(out.id, sent_at=T0)
    with pytest.raises(IntegrityError, match="ck_messages_outbound_shape"):
        async with session.begin_nested():
            await repo.update_delivery(out.id, delivery_state=DeliveryState.RECEIVED)
    with pytest.raises(IntegrityError, match="ck_messages_failure_shape"):
        async with session.begin_nested():
            await repo.update_delivery(
                out.id, delivery_state=DeliveryState.FAILED, failure_code="timeout"
            )
    ok = await repo.update_delivery(
        out.id,
        delivery_state=DeliveryState.DELIVERED,
        sent_at=T0,
        delivered_at=T0,
        external_message_id="ext-out",
    )
    assert ok.delivery_state is DeliveryState.DELIVERED
    with pytest.raises(ValueError, match="not a delivery field"):
        await repo.update_delivery(out.id, text="rewritten")
    with pytest.raises(IntegrityError, match="ck_messages_inbound_shape"):
        async with session.begin_nested():
            session.add(
                Message(
                    user_id=scope.user_id,
                    conversation_id=conv_id,
                    platform="mock",
                    direction="inbound",
                    origin="autopilot",
                    text="x",
                    delivery_state="received",
                    external_message_id="bad",
                )
            )
            await session.flush()


async def test_date_cannot_be_confirmed_without_evidence(session: AsyncSession) -> None:
    scope = await make_user(session)
    _, conv_id = await _thread(session, scope)
    repo = DateProposalRepository(session, scope)
    dp = await repo.create(
        conversation_id=conv_id, state=DateProposalState.OFFERED, proposed_start=T0
    )
    with pytest.raises(IntegrityError, match="ck_date_proposals_confirmed_requires_evidence"):
        async with session.begin_nested():
            await repo.update(dp.id, state=DateProposalState.CONFIRMED)
    msg = await MessageRepository(session, scope).ingest_inbound(
        conversation_id=conv_id,
        platform="mock",
        external_message_id="yes",
        text="see you",
        sent_at_source=T0,
    )
    done = await repo.update(
        dp.id, state=DateProposalState.CONFIRMED, confirmation_message_ids=[msg.message.id]
    )
    assert done.state is DateProposalState.CONFIRMED


async def test_preference_rules_and_versioning(session: AsyncSession) -> None:
    scope = await make_user(session)
    style = await StyleProfileRepository(session, scope).create()
    repo = PreferenceRepository(session, scope)
    base: dict[str, Any] = {
        "style_profile_id": style.id,
        "automation_mode": AutomationMode.MANUAL,
    }
    with pytest.raises(IntegrityError, match="ck_user_preferences_intent_custom_description"):
        async with session.begin_nested():
            await repo.save_version(expected_version=0, intent_mode=IntentMode.CUSTOM, **base)
    with pytest.raises(IntegrityError, match="ck_user_preferences_intent_custom_description"):
        async with session.begin_nested():
            await repo.save_version(
                expected_version=0,
                intent_mode=IntentMode.OPEN,
                intent_custom_description="not allowed",
                **base,
            )
    with pytest.raises(IntegrityError, match="ck_user_preferences_user_wide_required"):
        async with session.begin_nested():
            await repo.save_version(
                expected_version=0,
                intent_mode=None,
                style_profile_id=style.id,
                automation_mode=AutomationMode.MANUAL,
            )
    with pytest.raises(IntegrityError, match="ck_user_preferences_reply_delay_range"):
        async with session.begin_nested():
            await repo.save_version(
                expected_version=0,
                intent_mode=IntentMode.OPEN,
                reply_delay_min_seconds=10,
                reply_delay_max_seconds=5,
                **base,
            )
    v1 = await repo.save_version(expected_version=0, intent_mode=IntentMode.SERIOUS, **base)
    v2 = await repo.save_version(
        expected_version=1,
        intent_mode=IntentMode.CUSTOM,
        intent_custom_description="slow burn",
        **base,
    )
    assert (v1.version, v2.version) == (1, 2)
    with pytest.raises(VersionConflictError):
        await repo.save_version(expected_version=1, intent_mode=IntentMode.OPEN, **base)
    page = await repo.list_versions()
    assert [p.version for p in page.items] == [2, 1]
    # Contact override: everything nullable, independent version sequence.
    contact_id, _ = await _thread(session, scope)
    o1 = await repo.save_version(
        expected_version=0,
        contact_id=contact_id,
        intent_mode=IntentMode.CASUAL_DATING,
        style_profile_id=None,
        automation_mode=None,
    )
    assert o1.version == 1
    cur = await repo.current()
    assert cur is not None and cur.id == v2.id, "override must not modify user-wide preferences"


async def test_memory_rules(session: AsyncSession) -> None:
    scope = await make_user(session)
    contact_id, _ = await _thread(session, scope)
    repo = MemoryRepository(session, scope)
    with pytest.raises(IntegrityError, match="ck_conversation_memories_fact_is_attributed"):
        async with session.begin_nested():
            await repo.add(
                contact_id=contact_id, content="x", confidence=0.5, source_message_ids=[]
            )
    with pytest.raises(IntegrityError, match="ck_conversation_memories_confidence_range"):
        async with session.begin_nested():
            await repo.add(
                contact_id=contact_id,
                content="x",
                confidence=1.5,
                source_message_ids=[uuid.uuid4()],
            )
    src = [uuid.uuid4()]
    old = await repo.add(
        contact_id=contact_id, content="lives in Austin", confidence=0.8, source_message_ids=src
    )
    new = await repo.add(
        contact_id=contact_id, content="moved to Denver", confidence=0.9, source_message_ids=src
    )
    expired = await repo.add(
        contact_id=contact_id,
        content="busy this week",
        confidence=0.9,
        source_message_ids=src,
        expires_at=T0 - timedelta(days=1),
    )
    gone = await repo.add(
        contact_id=contact_id, content="deleted", confidence=0.9, source_message_ids=src
    )
    await repo.supersede(old.id, new.id)
    await repo.soft_delete(gone.id)
    active = await repo.active_for_contact(contact_id, now=T0)
    assert [m.id for m in active] == [new.id]
    assert expired.id not in {m.id for m in active}


async def test_subscription_and_usage_rules(session: AsyncSession) -> None:
    scope = await make_user(session)
    subs = SubscriptionRepository(session, scope)
    await subs.create(plan="pro", status="trialing")
    with pytest.raises(IntegrityError, match="uq_subscriptions_user_id_live"):
        async with session.begin_nested():
            await subs.create(plan="premium", status="active")
    with pytest.raises(IntegrityError, match="ck_subscriptions_plan_values"):
        async with session.begin_nested():
            session.add(Subscription(user_id=scope.user_id, plan="gold", status="expired"))
            await session.flush()
    usage = UsageEventRepository(session, scope)
    first = await usage.record(event_type="message.sent", dedup_key="send:1", occurred_at=T0)
    again = await usage.record(event_type="message.sent", dedup_key="send:1", occurred_at=T0)
    assert first.created and not again.created and again.event.id == first.event.id
    total = await usage.total("message.sent", since=T0, until=T0 + timedelta(seconds=1))
    assert total == 1, "retried events count once"


async def test_naive_datetimes_rejected(session: AsyncSession) -> None:
    scope = await make_user(session)
    _, conv_id = await _thread(session, scope)
    with pytest.raises(StatementError, match="naive datetime"):
        await DateProposalRepository(session, scope).create(
            conversation_id=conv_id,
            proposed_start=datetime(2026, 11, 1, 12, 0),  # noqa: DTZ001
        )


async def test_timestamps_are_utc_and_server_generated(session: AsyncSession) -> None:
    scope = await make_user(session)
    user = await UserRepository(session).get(scope)
    assert user is not None
    assert user.created_at.utcoffset() == timedelta(0)
    assert user.id.version == 7


async def test_users_get_or_create_is_idempotent(session: AsyncSession) -> None:
    repo = UserRepository(session)
    u1 = await repo.get_or_create_by_auth("https://iss", "abc")
    u2 = await repo.get_or_create_by_auth("https://iss", "abc")
    u3 = await repo.get_or_create_by_auth("https://other-iss", "abc")
    assert u1.id == u2.id != u3.id


# ------------------------------------------------------------------ schema conventions

USER_OWNED_TABLES = sorted(
    t.name for t in Base.metadata.sorted_tables if "user_id" in t.c and t.name != "users"
)


async def test_every_user_owned_index_starts_with_user_id(session: AsyncSession) -> None:
    rows = (
        await session.execute(
            text(
                "SELECT i.relname, t.relname, a.attname"
                " FROM pg_index x"
                " JOIN pg_class i ON i.oid = x.indexrelid"
                " JOIN pg_class t ON t.oid = x.indrelid"
                " JOIN pg_namespace n ON n.oid = t.relnamespace"
                " LEFT JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = x.indkey[0]"
                " WHERE n.nspname = 'public' AND NOT x.indisprimary"
            )
        )
    ).all()
    seen = set()
    for index, table, first_col in rows:
        if table in USER_OWNED_TABLES:
            seen.add(table)
            assert first_col == "user_id", f"{index} on {table} must start with user_id"
    assert seen == set(USER_OWNED_TABLES)


async def test_keyset_indexes_end_in_id(session: AsyncSession) -> None:
    rows = (
        await session.execute(
            text("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'")
        )
    ).all()
    defs = dict(rows)
    for name in (
        "ix_conversations_user_id_last_activity_at_id",
        "ix_messages_user_id_conversation_id_created_at_id",
        "ix_agent_actions_user_id_occurred_at_id",
        "ix_platform_connections_user_id_created_at_id",
        "ix_user_preferences_user_id_contact_id_version_id",
    ):
        cols = defs[name].split("(", 1)[1].rstrip(")")
        assert cols.split(",")[-1].strip().split()[0] == "id", defs[name]


def test_every_table_has_timestamps_and_uuid_pk() -> None:
    for table in Base.metadata.sorted_tables:
        assert [c.name for c in table.primary_key.columns] == ["id"], table.name
        for col in ("created_at", "updated_at"):
            assert col in table.c and not table.c[col].nullable, (table.name, col)
            assert getattr(table.c[col].type, "timezone", None) is True or (
                table.c[col].type.impl.timezone  # type: ignore[attr-defined]
            ), (table.name, col)


def test_agent_action_repository_is_append_only() -> None:
    public = {n for n in dir(AgentActionRepository) if not n.startswith("_")}
    assert not {n for n in public if n.startswith(("update", "delete", "set_"))}
    assert isinstance(AgentAction.__table__.c["details"].type, object)


async def test_capability_document_missing_fields_rejected_by_database(
    session: AsyncSession,
) -> None:
    """A document lacking `platform` or a capability `status` must not pass the CHECKs."""
    scope = await make_user(session)
    conn_id = await make_connection(session, scope)
    await _fails(
        session,
        "UPDATE platform_connections SET capabilities = capabilities - 'platform' WHERE id = :id",
        {"id": conn_id},
        "ck_platform_connections_capabilities_platform_matches",
    )
    await _fails(
        session,
        "UPDATE platform_connections SET capabilities ="
        " capabilities #- '{capabilities,send,status}' WHERE id = :id",
        {"id": conn_id},
        "ck_platform_connections_send_capability_matches",
    )


async def test_custom_intent_requires_description_at_db_level(session: AsyncSession) -> None:
    scope = await make_user(session)
    style = await StyleProfileRepository(session, scope).create()
    for desc in (None, ""):
        with pytest.raises(IntegrityError, match="ck_user_preferences_intent_custom_description"):
            async with session.begin_nested():
                await PreferenceRepository(session, scope).save_version(
                    expected_version=0,
                    intent_mode=IntentMode.CUSTOM,
                    intent_custom_description=desc,
                    style_profile_id=style.id,
                    automation_mode=AutomationMode.MANUAL,
                )
