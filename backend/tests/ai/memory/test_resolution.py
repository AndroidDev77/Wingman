"""Supersession, correction and deletion resolve predictably and keep audit history."""

import uuid
from datetime import timedelta

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.memory import FactOrigin, MemoryStateError, SkipReason
from app.db.errors import NotFoundError
from app.db.repositories import MemoryRepository
from tests.ai.memory.conftest import NOW, Harness, fact, facts, make_thread, make_user, say


async def _active_contents(harness: Harness, session: AsyncSession, scope, conv) -> list[str]:  # type: ignore[no-untyped-def]
    ctx = await harness.service.get_context(session, scope, conv, "job work city")
    return sorted(f.content for f in ctx.facts)


async def test_newer_statement_supersedes_older(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "I'm a nurse", at=NOW - timedelta(days=10))
    harness.provider.push(facts(fact("work.job_title", "Works as a nurse", ["m1"])))
    first = await harness.service.extract_from_messages(session, scope, t.conversation_id)
    old_id = first.created[0].id

    m2 = await say(session, t, "actually I just switched to teaching!", at=NOW - timedelta(days=1))
    harness.provider.push(facts(fact("work.job_title", "Works as a teacher", ["m1"])))
    second = await harness.service.extract_from_messages(
        session, scope, t.conversation_id, message_ids=[m2]
    )
    assert second.superseded_ids == (old_id,)
    new = second.created[0]
    assert await _active_contents(harness, session, scope, t.conversation_id) == [
        "Works as a teacher"
    ]
    # History is kept and linked.
    old = await MemoryRepository(session, scope).require(old_id)
    assert old.superseded_by_id == new.id and old.deleted_at is None
    hist = await harness.service.history(session, scope, new.id)
    assert [h.content for h in hist] == ["Works as a nurse", "Works as a teacher"]


async def test_reextracting_older_message_does_not_override_newer(
    session: AsyncSession, harness: Harness
) -> None:
    """Order of extraction calls does not matter: the latest *statement* wins."""
    scope = await make_user(session)
    t = await make_thread(session, scope)
    m_old = await say(session, t, "I live in Austin", at=NOW - timedelta(days=20))
    m_new = await say(session, t, "moved to Denver last week", at=NOW - timedelta(days=2))
    harness.provider.push(facts(fact("location.city", "Lives in Denver", ["m1"])))
    newer = await harness.service.extract_from_messages(
        session, scope, t.conversation_id, message_ids=[m_new]
    )
    harness.provider.push(facts(fact("location.city", "Lives in Austin", ["m1"])))
    late = await harness.service.extract_from_messages(
        session, scope, t.conversation_id, message_ids=[m_old]
    )
    assert late.created == () and late.superseded_ids == ()
    assert len(late.stored_as_history_ids) == 1
    hist_row = await MemoryRepository(session, scope).require(late.stored_as_history_ids[0])
    assert hist_row.superseded_by_id == newer.created[0].id
    assert await _active_contents(harness, session, scope, t.conversation_id) == ["Lives in Denver"]


async def test_contradiction_in_one_batch_newest_message_wins(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "I'm 29", at=NOW - timedelta(hours=3))
    await say(session, t, "wait no, 30 as of yesterday", at=NOW - timedelta(hours=2))
    harness.provider.push(
        facts(
            fact("identity.age", "Is 30", ["m2"]),
            fact("identity.age", "Is 29", ["m1"]),
        )
    )
    res = await harness.service.extract_from_messages(session, scope, t.conversation_id)
    assert [f.content for f in res.created] == ["Is 30"]
    assert len(res.stored_as_history_ids) + len(res.superseded_ids) == 1
    ctx = await harness.service.get_context(session, scope, t.conversation_id, "age")
    assert [f.content for f in ctx.facts] == ["Is 30"]


async def test_user_correction_overrides_extraction_until_contact_says_otherwise(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    m1 = await say(session, t, "I work at the hospital", at=NOW - timedelta(days=3))
    harness.provider.push(facts(fact("work.job_title", "Works as a doctor", ["m1"])))
    extracted = (
        await harness.service.extract_from_messages(session, scope, t.conversation_id)
    ).created[0]

    corrected = await harness.service.correct_fact(
        session, scope, extracted.id, content="Works as a hospital pharmacist"
    )
    assert corrected.origin is FactOrigin.USER_CORRECTION
    assert corrected.confidence == 1.0 and corrected.established
    assert corrected.key == "work.job_title"
    assert corrected.source_message_ids == (m1,)
    old = await MemoryRepository(session, scope).require(extracted.id)
    assert old.superseded_by_id == corrected.id

    # Re-extracting the same old message cannot undo the user's correction.
    harness.provider.push(facts(fact("work.job_title", "Works as a doctor", ["m1"])))
    again = await harness.service.extract_from_messages(
        session, scope, t.conversation_id, message_ids=[m1]
    )
    assert again.created == ()
    assert await _active_contents(harness, session, scope, t.conversation_id) == [
        "Works as a hospital pharmacist"
    ]

    # Something the contact says after the correction does replace it.
    m2 = await say(
        session, t, "big news, I start med school in the fall", at=NOW + timedelta(hours=1)
    )
    later = harness.service
    harness.provider.push(facts(fact("work.job_title", "Starting med school in the fall", ["m1"])))
    newer = await later.extract_from_messages(session, scope, t.conversation_id, message_ids=[m2])
    assert newer.superseded_ids == (corrected.id,)


async def test_correction_is_idempotent_and_validated(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "I have two cats")
    harness.provider.push(facts(fact("pets.cats", "Has two cats", ["m1"])))
    f = (await harness.service.extract_from_messages(session, scope, t.conversation_id)).created[0]
    a = await harness.service.correct_fact(session, scope, f.id, content="Has three cats")
    b = await harness.service.correct_fact(session, scope, f.id, content="Has three cats")
    assert a.id == b.id
    with pytest.raises(MemoryStateError):
        await harness.service.correct_fact(session, scope, f.id, content="   ")
    with pytest.raises(NotFoundError):
        await harness.service.correct_fact(session, scope, uuid.uuid4(), content="x")
    other = await make_user(session)
    with pytest.raises(NotFoundError):
        await harness.service.correct_fact(session, other, a.id, content="hijack")


async def test_deleted_fact_excluded_and_not_resurrected(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "I'm a nurse", at=NOW - timedelta(days=5))
    harness.provider.push(facts(fact("work.job_title", "Works as a nurse", ["m1"])))
    f = (await harness.service.extract_from_messages(session, scope, t.conversation_id)).created[0]

    deleted = await harness.service.delete_fact(session, scope, f.id)
    assert deleted.deleted_at is not None
    again = await harness.service.delete_fact(session, scope, f.id)  # idempotent
    assert again.deleted_at == deleted.deleted_at

    ctx = await harness.service.get_context(session, scope, t.conversation_id, "nurse job work")
    assert f.id not in ctx.memory_ids and ctx.facts == ()
    assert (
        await harness.service.list_facts(session, scope, t.contact_id, include_history=True) == []
    )

    # Re-extracting the same message does not bring it back.
    harness.provider.push(facts(fact("work.job_title", "Works as a nurse", ["m1"])))
    res = await harness.service.extract_from_messages(session, scope, t.conversation_id)
    assert res.created == ()
    assert [s.reason for s in res.skipped] == [SkipReason.DELETED_BY_USER]

    # New evidence (a new message) may create a new fact for the slot.
    m2 = await say(session, t, "I'm a paramedic now", at=NOW - timedelta(hours=1))
    harness.provider.push(facts(fact("work.job_title", "Works as a paramedic", ["m1"])))
    res2 = await harness.service.extract_from_messages(
        session, scope, t.conversation_id, message_ids=[m2]
    )
    assert [x.content for x in res2.created] == ["Works as a paramedic"]


async def test_deleting_active_fact_does_not_resurrect_history(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "Austin", at=NOW - timedelta(days=9))
    harness.provider.push(facts(fact("location.city", "Lives in Austin", ["m1"])))
    await harness.service.extract_from_messages(session, scope, t.conversation_id)
    m2 = await say(session, t, "Denver now", at=NOW - timedelta(days=1))
    harness.provider.push(facts(fact("location.city", "Lives in Denver", ["m1"])))
    new = (
        await harness.service.extract_from_messages(
            session, scope, t.conversation_id, message_ids=[m2]
        )
    ).created[0]
    await harness.service.delete_fact(session, scope, new.id)
    ctx = await harness.service.get_context(session, scope, t.conversation_id, "city lives")
    assert ctx.facts == ()


async def test_summary_cannot_be_corrected(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "hello there")
    harness.provider.push({"summary": "They said hello."})
    s = await harness.service.update_summary(session, scope, t.conversation_id)
    assert s is not None
    with pytest.raises(MemoryStateError):
        await harness.service.correct_fact(session, scope, s.id, content="x")
