"""Idempotency-Key store (ADR 0004), keyset pagination (ADR 0003) and UUIDv7 (ADR 0001)."""

import uuid
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy.exc import DBAPIError, IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import Actor, AgentActionType
from app.db.errors import InvalidCursorError
from app.db.ids import uuid7
from app.db.pagination import CursorPosition, encode_cursor
from app.db.repositories import (
    AgentActionRepository,
    ClaimOutcome,
    ContactRepository,
    ConversationRepository,
    IdempotencyRepository,
    MessageRepository,
    fingerprint,
)
from tests.db.conftest import make_connection, make_user

T0 = datetime(2026, 11, 1, 12, 0, tzinfo=UTC)

# ------------------------------------------------------------------ idempotency


async def test_idempotency_claim_replay_mismatch_in_progress(session: AsyncSession) -> None:
    scope = await make_user(session)
    repo = IdempotencyRepository(session, scope)
    body = {"text": "hi"}
    args = {"key": "k-0001-abcd", "method": "POST", "route": "/v1/conversations/{id}/messages"}

    first = await repo.claim(**args, request_hash=fingerprint(body), now=T0)
    assert first.outcome is ClaimOutcome.CLAIMED

    busy = await repo.claim(**args, request_hash=fingerprint(body), now=T0)
    assert busy.outcome is ClaimOutcome.IN_PROGRESS

    await repo.complete(first.record.id, status=202, body={"id": "m1", "delivery_state": "queued"})
    replay = await repo.claim(**args, request_hash=fingerprint({"text": "hi"}), now=T0)
    assert replay.outcome is ClaimOutcome.REPLAY
    assert replay.record.response_status == 202
    assert replay.record.response_body == {"id": "m1", "delivery_state": "queued"}

    mismatch = await repo.claim(**args, request_hash=fingerprint({"text": "other"}), now=T0)
    assert mismatch.outcome is ClaimOutcome.MISMATCH

    # Different route or method: independent key space.
    other = await repo.claim(
        key=args["key"],
        method="POST",
        route="/v1/automation/pause",
        request_hash=fingerprint(body),
        now=T0,
    )
    assert other.outcome is ClaimOutcome.CLAIMED


async def test_idempotency_release_on_5xx_and_expiry(session: AsyncSession) -> None:
    scope = await make_user(session)
    repo = IdempotencyRepository(session, scope)
    args = {"key": "k-retry-0001", "method": "POST", "route": "/v1/automation/pause"}
    h = fingerprint({})
    c = await repo.claim(**args, request_hash=h, now=T0)
    with pytest.raises(ValueError, match="5xx"):
        await repo.complete(c.record.id, status=503, body=None)
    await repo.release(c.record.id)
    again = await repo.claim(**args, request_hash=h, now=T0)
    assert again.outcome is ClaimOutcome.CLAIMED
    await repo.complete(again.record.id, status=200, body={"status": "paused"})
    assert again.record.expires_at == T0 + timedelta(hours=24)

    # After the 24 h window the key is reusable, even with a different body.
    later = T0 + timedelta(hours=25)
    fresh = await repo.claim(**args, request_hash=fingerprint({"x": 1}), now=later)
    assert fresh.outcome is ClaimOutcome.CLAIMED
    assert await repo.purge_expired(now=later + timedelta(hours=25)) == 1


async def test_idempotency_key_format_enforced(session: AsyncSession) -> None:
    scope = await make_user(session)
    repo = IdempotencyRepository(session, scope)
    for bad_key in ("short", "has space in it", "x" * 129):
        with pytest.raises(DBAPIError, match="ck_idempotency_keys_key_format|value too long"):
            async with session.begin_nested():
                await repo.claim(
                    key=bad_key, method="POST", route="/v1/x", request_hash=fingerprint({})
                )
    with pytest.raises(IntegrityError, match="ck_idempotency_keys_request_hash_sha256"):
        async with session.begin_nested():
            await repo.claim(key="valid-key-1", method="POST", route="/v1/x", request_hash="nope")


def test_fingerprint_is_canonical() -> None:
    assert fingerprint({"a": 1, "b": [1, 2]}) == fingerprint({"b": [1, 2], "a": 1})
    assert fingerprint({"a": 1}) != fingerprint({"a": 2})


# ------------------------------------------------------------------ pagination


async def test_message_thread_keyset_pagination(session: AsyncSession) -> None:
    scope = await make_user(session)
    conn_id = await make_connection(session, scope)
    contact = await ContactRepository(session, scope).get_or_create(
        connection_id=conn_id, external_contact_id="c", display_name="Sam"
    )
    conv = await ConversationRepository(session, scope).create(
        contact_id=contact.id, connection_id=conn_id, platform="mock"
    )
    repo = MessageRepository(session, scope)
    # 7 messages; 3 share a timestamp so the id tiebreak matters.
    times = [
        T0,
        T0,
        T0,
        T0 + timedelta(seconds=1),
        T0 + timedelta(seconds=2),
        T0 + timedelta(seconds=3),
        T0 + timedelta(seconds=4),
    ]
    for i, t in enumerate(times):
        await repo.ingest_inbound(
            conversation_id=conv.id,
            platform="mock",
            external_message_id=f"m{i}",
            text=str(i),
            sent_at_source=t,
        )

    seen: list[uuid.UUID] = []
    cursor = None
    pages = 0
    while True:
        page = await repo.list_thread(conv.id, cursor=cursor, limit=3)
        seen.extend(m.id for m in page.items)
        pages += 1
        if page.next_cursor is None:
            break
        cursor = page.next_cursor
    assert pages == 3
    assert len(seen) == 7 == len(set(seen))
    rows = [await repo.require(i) for i in seen]
    keys = [(m.created_at, m.id) for m in rows]
    assert keys == sorted(keys), "chronological, then id"

    with pytest.raises(InvalidCursorError):
        await repo.list_thread(conv.id, cursor="garbage!!")
    foreign = encode_cursor("audit", CursorPosition(T0, uuid.uuid4()))
    with pytest.raises(InvalidCursorError):
        await repo.list_thread(conv.id, cursor=foreign)
    with pytest.raises(ValueError, match="limit"):
        await repo.list_thread(conv.id, limit=101)


async def test_audit_is_newest_first(session: AsyncSession) -> None:
    scope = await make_user(session)
    repo = AgentActionRepository(session, scope)
    for i in range(5):
        await repo.record(
            type=AgentActionType.POLICY_BLOCKED,
            actor=Actor.SYSTEM,
            occurred_at=T0 + timedelta(minutes=i),
            reason_code=str(i),
        )
    p1 = await repo.list(limit=2)
    p2 = await repo.list(limit=2, cursor=p1.next_cursor)
    p3 = await repo.list(limit=2, cursor=p2.next_cursor)
    codes = [a.reason_code for a in p1.items + p2.items + p3.items]
    assert codes == ["4", "3", "2", "1", "0"]
    assert p3.next_cursor is None
    filtered = await repo.list(type=AgentActionType.SEND_FAILED)
    assert filtered.items == []


# ------------------------------------------------------------------ ids


def test_uuid7_is_version_7_and_monotonic() -> None:
    ids = [uuid7() for _ in range(5000)]
    assert all(i.version == 7 and i.variant == uuid.RFC_4122 for i in ids)
    assert ids == sorted(ids)
    assert len(set(ids)) == len(ids)
    assert str(ids[0]) == str(ids[0]).lower()
