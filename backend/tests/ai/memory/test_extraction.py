"""Extraction: attribution, strict output, sensitive inference, expiry at write time."""

import json
import uuid
from datetime import timedelta

import pytest
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.memory import EXTRACT_OPERATION, FactBasis, FactOrigin, SkipReason
from app.ai.providers import LLMServerError, SchemaValidationError
from app.ai.providers.errors import LLMOutputError, MalformedOutputError
from app.db.errors import NotFoundError
from app.db.models import ConversationMemory
from app.db.repositories import MemoryRepository
from tests.ai.memory.conftest import (
    TODAY,
    Harness,
    fact,
    facts,
    make_thread,
    make_user,
    say,
)


async def _count(session: AsyncSession) -> int:
    n = await session.scalar(select(func.count()).select_from(ConversationMemory))
    return int(n or 0)


async def test_source_attribution_recorded_and_verified(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    m1 = await say(session, t, "hey! just got back from a climbing trip")
    m2 = await say(session, t, "I'm a nurse, so night shifts this week")
    harness.provider.push(
        facts(
            fact("interests.climbing", "Goes rock climbing", ["m1"]),
            fact("work.job_title", "Works as a nurse", ["m2"]),
            fact(
                "plans.week",
                "Working night shifts this week",
                ["m1", "m2"],
                category="availability",
                valid_until=TODAY + timedelta(days=6),
            ),
        )
    )
    result = await harness.service.extract_from_messages(session, scope, t.conversation_id)

    by_key = {f.key: f for f in result.created}
    assert set(by_key) == {"interests.climbing", "work.job_title", "plans.week"}
    assert by_key["interests.climbing"].source_message_ids == (m1,)
    assert by_key["work.job_title"].source_message_ids == (m2,)
    assert set(by_key["plans.week"].source_message_ids) == {m1, m2}
    for f in result.created:
        assert f.contact_id == t.contact_id and f.conversation_id == t.conversation_id
        assert f.origin is FactOrigin.EXTRACTED and f.basis is FactBasis.STATED
    # stated_at is the latest cited message's time; expiry is the end of valid_until.
    stored = await MemoryRepository(session, scope).require(by_key["plans.week"].id)
    assert stored.source_message_ids == [m1, m2]
    assert stored.expires_at is not None and stored.expires_at.date() == TODAY + timedelta(days=7)

    # The prompt cites messages by ref only and the usage record is labeled.
    req = harness.provider.requests[0]
    payload = json.loads(req.messages[1].content.split("Messages (JSON):\n", 1)[1])
    assert [p["ref"] for p in payload] == ["m1", "m2"]
    assert all(p["from"] == "contact" for p in payload)
    assert str(m1) not in req.messages[1].content
    assert harness.sink.records[0].operation == EXTRACT_OPERATION
    assert harness.sink.records[0].user_id == str(scope.user_id)


async def test_extract_specific_messages_only(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "old message")
    m2 = await say(session, t, "I have a dog named Pixel")
    harness.provider.push(facts(fact("pets.dog", "Has a dog named Pixel", ["m1"])))
    res = await harness.service.extract_from_messages(
        session, scope, t.conversation_id, message_ids=[m2]
    )
    assert res.created[0].source_message_ids == (m2,)
    assert "old message" not in harness.provider.requests[0].messages[1].content


async def test_message_ids_from_other_conversation_rejected(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    a = await make_thread(session, scope)
    b = await make_thread(session, scope)
    other = await say(session, b, "secret from b")
    with pytest.raises(NotFoundError):
        await harness.service.extract_from_messages(
            session, scope, a.conversation_id, message_ids=[other]
        )
    assert harness.provider.calls == 0


@pytest.mark.parametrize(
    "bad",
    [
        "not json at all",
        "[]",
        json.dumps({"facts": [fact("work.job", "x", ["m1"]) | {"confidence": 1.5}]}),
        json.dumps({"facts": [fact("work.job", "x", [])]}),
        json.dumps({"facts": [fact("work.job", "x", ["m1"]) | {"extra": True}]}),
        json.dumps({"facts": [fact("work.job", "x", ["m1"]) | {"category": "astrology"}]}),
        json.dumps({"facts": [fact("Work Job!", "x", ["m1"])]}),
        json.dumps({"facts": [fact("work.job", "x", ["msg-1"])]}),
        json.dumps({"facts": [fact("work.job", "x", ["m1"]) | {"confidence": "0.9"}]}),
        json.dumps({"facts": [{"key": "work.job", "content": "x"}]}),
    ],
)
async def test_malformed_output_rejected_and_not_stored(
    session: AsyncSession, harness: Harness, bad: str
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "hello")
    before = await _count(session)
    harness.provider.push(bad)
    with pytest.raises(LLMOutputError):
        await harness.service.extract_from_messages(session, scope, t.conversation_id)
    assert await _count(session) == before


async def test_unknown_source_ref_rejects_whole_output(
    session: AsyncSession, harness: Harness
) -> None:
    """A fact citing a message the model was never shown is fabricated attribution."""
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "I love jazz")
    harness.provider.push(
        facts(
            fact("interests.jazz", "Loves jazz", ["m1"]),
            fact("location.city", "Lives in Paris", ["m7"]),
        )
    )
    with pytest.raises(SchemaValidationError) as err:
        await harness.service.extract_from_messages(session, scope, t.conversation_id)
    assert err.value.issues == ("facts.1.source_refs: unknown_ref",)
    assert await _count(session) == 0


async def test_provider_failure_stores_nothing(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "hello")
    harness.provider.push(LLMServerError("boom"))
    with pytest.raises(LLMServerError):
        await harness.service.extract_from_messages(session, scope, t.conversation_id)
    assert await _count(session) == 0


async def test_refused_output_rejected(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "hello")
    harness.provider.push("")
    with pytest.raises(MalformedOutputError):
        await harness.service.extract_from_messages(session, scope, t.conversation_id)


async def test_inferred_sensitive_fact_never_stored_as_established(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "can't do drinks, early church thing on sunday, and my knee is acting up")
    harness.provider.push(
        facts(
            # Inferred, sensitive by category: dropped.
            fact("religion.affiliation", "Is Christian", ["m1"], basis="inferred"),
            # Inferred, sensitive by key prefix even with a non-sensitive category: dropped.
            fact(
                "health.alcohol", "May be sober", ["m1"], category="preferences", basis="inferred"
            ),
            # Stated sensitive fact: the contact said it, stored as stated.
            fact("health.knee", "Mentioned a sore knee", ["m1"], category="health"),
            # Inferred, not sensitive: stored, labeled inferred, confidence capped.
            fact(
                "preferences.mornings",
                "Seems to be a morning person",
                ["m1"],
                category="preferences",
                basis="inferred",
                confidence=0.95,
            ),
        )
    )
    res = await harness.service.extract_from_messages(session, scope, t.conversation_id)
    skipped = {s.key: s.reason for s in res.skipped}
    assert skipped == {
        "religion.affiliation": SkipReason.SENSITIVE_INFERRED,
        "health.alcohol": SkipReason.SENSITIVE_INFERRED,
    }
    created = {f.key: f for f in res.created}
    assert set(created) == {"health.knee", "preferences.mornings"}
    assert created["health.knee"].sensitive and created["health.knee"].established
    morning = created["preferences.mornings"]
    assert morning.basis is FactBasis.INFERRED and not morning.established
    assert morning.confidence == pytest.approx(0.6)

    rows = (await session.scalars(select(ConversationMemory))).all()
    for row in rows:
        if row.basis == "inferred":
            assert row.category not in {
                "health",
                "religion",
                "sexuality",
                "politics",
                "ethnicity",
                "finances",
            }
            assert not (row.fact_key or "").startswith(("health", "religion"))


async def test_low_confidence_and_already_expired_skipped(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "busy last weekend; maybe into hiking?")
    harness.provider.push(
        facts(
            fact("interests.hiking", "Might like hiking", ["m1"], confidence=0.3),
            fact(
                "plans.last_weekend",
                "Was busy last weekend",
                ["m1"],
                category="plans",
                valid_until=TODAY - timedelta(days=1),
            ),
        )
    )
    res = await harness.service.extract_from_messages(session, scope, t.conversation_id)
    assert res.created == ()
    assert {s.key: s.reason for s in res.skipped} == {
        "interests.hiking": SkipReason.LOW_CONFIDENCE,
        "plans.last_weekend": SkipReason.ALREADY_EXPIRED,
    }


async def test_duplicate_extraction_is_idempotent(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "I'm a nurse")
    for _ in range(2):
        harness.provider.push(facts(fact("work.job_title", "Works as a nurse", ["m1"])))
    first = await harness.service.extract_from_messages(session, scope, t.conversation_id)
    second = await harness.service.extract_from_messages(session, scope, t.conversation_id)
    assert len(first.created) == 1
    assert second.created == ()
    assert [s.reason for s in second.skipped] == [SkipReason.DUPLICATE]
    assert await _count(session) == 1


async def test_empty_conversation_makes_no_model_call(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    res = await harness.service.extract_from_messages(session, scope, t.conversation_id)
    assert res.created == () and harness.provider.calls == 0


async def test_other_users_conversation_is_not_found(
    session: AsyncSession, harness: Harness
) -> None:
    a = await make_user(session)
    b = await make_user(session)
    t = await make_thread(session, a)
    await say(session, t, "hello")
    with pytest.raises(NotFoundError):
        await harness.service.extract_from_messages(session, b, t.conversation_id)
    with pytest.raises(NotFoundError):
        await harness.service.extract_from_messages(session, a, uuid.uuid4())
    assert harness.provider.calls == 0
