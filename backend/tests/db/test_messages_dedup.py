"""Inbound dedup on (user_id, platform, external_message_id)."""

import uuid
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import DeliveryState, MessageOrigin
from app.db.models import Conversation, Message
from app.db.repositories import ContactRepository, ConversationRepository, MessageRepository
from app.db.scope import UserScope
from tests.db.conftest import make_connection, make_user

T0 = datetime(2026, 11, 1, 12, 0, tzinfo=UTC)


async def _conversation(
    session: AsyncSession, scope: UserScope, platform: str = "mock", ext_contact: str = "c1"
) -> Conversation:
    conn_id = await make_connection(session, scope, platform)
    contact = await ContactRepository(session, scope).get_or_create(
        connection_id=conn_id, external_contact_id=ext_contact, display_name="Sam"
    )
    return await ConversationRepository(session, scope).create(
        contact_id=contact.id, connection_id=conn_id, platform=platform, last_activity_at=T0
    )


async def _count(session: AsyncSession, scope: UserScope) -> int:
    n = await session.scalar(
        select(func.count()).select_from(Message).where(Message.user_id == scope.user_id)
    )
    return int(n or 0)


async def test_duplicate_external_message_yields_one_row(session: AsyncSession) -> None:
    scope = await make_user(session)
    conv = await _conversation(session, scope)
    repo = MessageRepository(session, scope)

    first = await repo.ingest_inbound(
        conversation_id=conv.id,
        platform="mock",
        external_message_id="m-1",
        text="hey",
        sent_at_source=T0,
    )
    second = await repo.ingest_inbound(
        conversation_id=conv.id,
        platform="mock",
        external_message_id="m-1",
        text="hey (redelivered)",
        sent_at_source=T0 + timedelta(seconds=5),
    )

    assert first.created is True
    assert second.created is False
    assert second.message.id == first.message.id
    assert second.message.text == "hey", "duplicate must not overwrite the stored message"
    assert await _count(session, scope) == 1

    await session.refresh(conv)
    assert conv.unread_count == 1, "duplicate must not bump unread_count"
    assert first.message.delivery_state is DeliveryState.RECEIVED
    assert first.message.origin is MessageOrigin.CONTACT
    assert first.message.created_at == T0


async def test_same_external_id_allowed_across_platforms_and_users(session: AsyncSession) -> None:
    a = await make_user(session)
    b = await make_user(session)
    conv_a_mock = await _conversation(session, a, "mock")
    conv_a_sms = await _conversation(session, a, "sms")
    conv_b_mock = await _conversation(session, b, "mock")

    for scope, conv, platform in (
        (a, conv_a_mock, "mock"),
        (a, conv_a_sms, "sms"),
        (b, conv_b_mock, "mock"),
    ):
        res = await MessageRepository(session, scope).ingest_inbound(
            conversation_id=conv.id,
            platform=platform,
            external_message_id="same-id",
            text="x",
            sent_at_source=T0,
        )
        assert res.created
    assert await _count(session, a) == 2
    assert await _count(session, b) == 1


async def test_raw_duplicate_insert_violates_unique_constraint(session: AsyncSession) -> None:
    scope = await make_user(session)
    conv = await _conversation(session, scope)
    repo = MessageRepository(session, scope)
    await repo.ingest_inbound(
        conversation_id=conv.id,
        platform="mock",
        external_message_id="m-1",
        text="a",
        sent_at_source=T0,
    )
    with pytest.raises(IntegrityError, match="uq_messages_user_id_platform_external_message_id"):
        async with session.begin_nested():
            session.add(
                Message(
                    user_id=scope.user_id,
                    conversation_id=conv.id,
                    platform="mock",
                    direction="inbound",
                    origin="contact",
                    text="b",
                    delivery_state="received",
                    external_message_id="m-1",
                )
            )
            await session.flush()


async def test_message_platform_must_match_conversation(session: AsyncSession) -> None:
    scope = await make_user(session)
    conv = await _conversation(session, scope, "mock")
    with pytest.raises(IntegrityError, match="fk_messages_user_id_conversation_id_platform"):
        async with session.begin_nested():
            await MessageRepository(session, scope).ingest_inbound(
                conversation_id=conv.id,
                platform="sms",  # dedup key would be on the wrong platform
                external_message_id="m-1",
                text="x",
                sent_at_source=T0,
            )


async def test_outbound_messages_without_external_id_do_not_collide(
    session: AsyncSession,
) -> None:
    scope = await make_user(session)
    conv = await _conversation(session, scope)
    repo = MessageRepository(session, scope)
    m1 = await repo.create_outbound(
        conversation_id=conv.id, platform="mock", origin=MessageOrigin.USER_MANUAL, text="1"
    )
    m2 = await repo.create_outbound(
        conversation_id=conv.id, platform="mock", origin=MessageOrigin.USER_MANUAL, text="2"
    )
    assert m1.external_message_id is None and m2.external_message_id is None
    assert m1.delivery_state is DeliveryState.QUEUED
    assert await _count(session, scope) == 2


async def test_ingest_unknown_conversation_rejected(session: AsyncSession) -> None:
    scope = await make_user(session)
    with pytest.raises(IntegrityError):
        async with session.begin_nested():
            await MessageRepository(session, scope).ingest_inbound(
                conversation_id=uuid.uuid4(),
                platform="mock",
                external_message_id="m-x",
                text="x",
                sent_at_source=T0,
            )


async def test_concurrent_duplicate_ingest_creates_one_row(migrated_db_url: str) -> None:
    """Two connections ingest the same external message at once: exactly one row."""
    import asyncio

    from sqlalchemy import delete
    from sqlalchemy.ext.asyncio import create_async_engine
    from sqlalchemy.pool import NullPool

    from app.db.models import User
    from app.db.session import create_session_factory

    engine = create_async_engine(migrated_db_url, poolclass=NullPool)
    factory = create_session_factory(engine)
    try:
        async with factory() as setup:
            scope = await make_user(setup)
            conv = await _conversation(setup, scope)
            await setup.commit()

        async def ingest(text_: str) -> bool:
            async with factory() as s:
                res = await MessageRepository(s, scope).ingest_inbound(
                    conversation_id=conv.id,
                    platform="mock",
                    external_message_id="race-1",
                    text=text_,
                    sent_at_source=T0,
                )
                await asyncio.sleep(0.05)  # hold the transaction open to overlap the other
                await s.commit()
                return res.created

        results = await asyncio.gather(*(ingest(f"copy {i}") for i in range(5)))
        assert sorted(results) == [False, False, False, False, True]
        async with factory() as check:
            assert await _count(check, scope) == 1
            await check.execute(delete(User).where(User.id == scope.user_id))
            await check.commit()
    finally:
        await engine.dispose()
