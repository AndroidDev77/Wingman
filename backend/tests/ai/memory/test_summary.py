"""Rolling summaries: compressed, superseding, bounded, and validated."""

from datetime import timedelta

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.memory import SUMMARY_OPERATION, ContextLimits
from app.ai.providers import SchemaValidationError
from app.db.repositories import MemoryRepository
from tests.ai.memory.conftest import NOW, Harness, make_thread, make_user, say


async def test_rolling_summary_folds_only_new_messages(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "first chat about hiking", at=NOW - timedelta(days=3))
    harness.provider.push({"summary": "Talked about hiking."})
    s1 = await harness.service.update_summary(session, scope, t.conversation_id)
    assert s1 is not None and s1.kind == "summary"
    assert harness.sink.records[-1].operation == SUMMARY_OPERATION

    # Nothing new: no model call, same summary.
    calls = harness.provider.calls
    same = await harness.service.update_summary(session, scope, t.conversation_id)
    assert same is not None and same.id == s1.id and harness.provider.calls == calls

    m2 = await say(session, t, "planning a trip to Peru", at=NOW - timedelta(days=1))
    harness.provider.push({"summary": "Talked about hiking; planning a Peru trip."})
    s2 = await harness.service.update_summary(session, scope, t.conversation_id)
    assert s2 is not None and s2.id != s1.id
    assert s2.source_message_ids == (m2,)
    prompt = harness.provider.requests[-1].messages[1].content
    assert "Talked about hiking." in prompt and "Peru" in prompt
    assert "first chat about hiking" not in prompt
    old = await MemoryRepository(session, scope).require(s1.id)
    assert old.superseded_by_id == s2.id

    ctx = await harness.service.get_context(session, scope, t.conversation_id, "peru")
    assert ctx.summary is not None and ctx.summary.id == s2.id


async def test_summary_truncated_to_limits(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "x")
    harness.provider.push({"summary": "word " * 300})
    await harness.service.update_summary(session, scope, t.conversation_id)
    ctx = await harness.service.get_context(
        session, scope, t.conversation_id, "x", ContextLimits(max_summary_chars=100)
    )
    assert ctx.summary is not None and ctx.summary.truncated
    assert len(ctx.summary.content) <= 100
    assert len(ctx.render()) == ctx.char_count


async def test_render_never_exceeds_max_chars(session: AsyncSession, harness: Harness) -> None:
    from tests.ai.memory.conftest import fact, facts

    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "x")
    harness.provider.push({"summary": "word " * 300})
    await harness.service.update_summary(session, scope, t.conversation_id)
    harness.provider.push(
        facts(*(fact(f"interests.t{i}", f"Likes t{i} " * 5, ["m1"]) for i in range(30)))
    )
    await harness.service.extract_from_messages(session, scope, t.conversation_id)
    for max_chars in (0, 10, 50, 120, 333, 1500, 5000):
        ctx = await harness.service.get_context(
            session, scope, t.conversation_id, "likes", ContextLimits(max_chars=max_chars)
        )
        assert len(ctx.render()) == ctx.char_count <= max_chars


async def test_invalid_summary_rejected(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "x")
    harness.provider.push({"summary": ""})
    with pytest.raises(SchemaValidationError):
        await harness.service.update_summary(session, scope, t.conversation_id)
    assert await harness.service.list_facts(session, scope, t.contact_id) == []


async def test_no_messages_no_summary(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    assert await harness.service.update_summary(session, scope, t.conversation_id) is None
    assert harness.provider.calls == 0


async def test_deleted_summary_not_retrieved(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "x")
    harness.provider.push({"summary": "Said x."})
    s = await harness.service.update_summary(session, scope, t.conversation_id)
    assert s is not None
    await harness.service.delete_fact(session, scope, s.id)
    ctx = await harness.service.get_context(session, scope, t.conversation_id, "x")
    assert ctx.summary is None
