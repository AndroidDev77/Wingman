"""Scripted conversations end to end: real Postgres, MockLLMProvider, persisted results."""

import json
from typing import Any

import pytest
from jsonschema import Draft202012Validator
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.analyzer import ANALYZE_OPERATION, ConversationAnalysis
from app.ai.providers import LLMServerError, LLMTimeoutError
from app.ai.providers.mock import MockReply
from app.ai.stages import DecisionReason, NextAction
from app.db.enums import AgentActionType, ConversationStage
from app.db.errors import NotFoundError
from app.db.models import AgentAction
from app.db.repositories import ConversationRepository
from tests.ai.analysis.conftest import (
    NOW,
    Harness,
    Thread,
    agreement,
    build_harness,
    contact_says,
    make_thread,
    make_user,
    output,
    signal,
    user_says,
)
from tests.preferences.conftest import contract_validator

S = ConversationStage


def analysis_validator() -> Draft202012Validator:
    return contract_validator("ConversationAnalysis")


async def run(db: AsyncSession, h: Harness, thread: Thread, step: Any) -> ConversationAnalysis:
    h.provider.push(step)
    return await h.analyzer.analyze_conversation(db, thread.scope, thread.conversation_id)


async def stored(db: AsyncSession, thread: Thread) -> tuple[S, dict[str, Any] | None]:
    conv = await ConversationRepository(db, thread.scope).require(thread.conversation_id)
    await db.refresh(conv)
    return conv.stage, conv.analysis


async def assert_persisted(db: AsyncSession, thread: Thread, a: ConversationAnalysis) -> None:
    stage, body = await stored(db, thread)
    assert stage is a.stage
    assert body == a.to_contract()
    analysis_validator().validate(body)


# --------------------------------------------------------------------------- progression


async def test_normal_progression_through_to_confirmed(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    m1 = await contact_says(session, t, "Hey! Loved your hiking photos")
    a = await run(
        session,
        harness,
        t,
        output("introduction", 0.8, signals=[signal("opens with a compliment", "positive", "m1")]),
    )
    assert (a.stage, a.uncertain, a.reasons) == (S.INTRODUCTION, False, (DecisionReason.ACCEPTED,))
    assert a.source_message_ids == (m1,)
    await assert_persisted(session, t, a)

    await user_says(session, t, "Thanks! Do you hike much?")
    await contact_says(session, t, "Every weekend, Mt Tam is my favourite. You?")
    a = await run(
        session,
        harness,
        t,
        output("rapport", 0.75, signals=[signal("asks follow-up", "positive", "m3")]),
    )
    assert (a.previous_stage, a.stage, a.uncertain) == (S.INTRODUCTION, S.RAPPORT, False)

    await contact_says(session, t, "Honestly you seem really fun, I'd like to meet you")
    a = await run(
        session,
        harness,
        t,
        output("interest", 0.8, signals=[signal("wants to meet", "positive", "m4")]),
    )
    assert a.stage is S.INTEREST

    await user_says(session, t, "Want to grab coffee at Blue Bottle Saturday at 10?")
    a = await run(
        session,
        harness,
        t,
        output(
            "date_proposal",
            0.85,
            signals=[signal("plan proposed", "neutral", "m5")],
            agreement=agreement("m5", user="m5", contact=None),
            next_action="wait",
        ),
    )
    assert (a.stage, a.uncertain, a.next_action) == (S.DATE_PROPOSAL, False, NextAction.WAIT)

    await contact_says(session, t, "Yes! Saturday 10 at Blue Bottle works, see you there")
    a = await run(
        session,
        harness,
        t,
        output(
            "confirmed",
            0.92,
            signals=[signal("accepts the plan", "positive", "m6")],
            agreement=agreement("m5", user="m5", contact="m6"),
            next_action="confirm_details",
        ),
    )
    assert (a.previous_stage, a.stage, a.uncertain) == (S.DATE_PROPOSAL, S.CONFIRMED, False)
    assert a.reasons == (DecisionReason.ACCEPTED,)
    await assert_persisted(session, t, a)
    assert harness.provider.calls == 5


async def test_mutual_explicit_agreement_is_confirmed(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.SCHEDULING)
    await contact_says(session, t, "How about Thursday 7pm at Lupa?")
    await user_says(session, t, "Thursday 7pm at Lupa, perfect, it's a date")
    a = await run(
        session,
        harness,
        t,
        output(
            "confirmed",
            0.9,
            signals=[signal("contact proposed the plan", "positive", "m1")],
            agreement=agreement("m1", user="m2", contact="m1"),
        ),
    )
    assert (a.stage, a.uncertain) == (S.CONFIRMED, False)
    await assert_persisted(session, t, a)


# --------------------------------------------------------------------------- holding back


async def test_rejection_moves_to_paused(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.INTEREST)
    await user_says(session, t, "Would you like to get dinner this week?")
    await contact_says(session, t, "Sorry, I'm not interested. Best of luck though!")
    a = await run(
        session,
        harness,
        t,
        output(
            "date_proposal",  # the model even tries to push forward
            0.7,
            signals=[signal("declines", "negative", "m2")],
            disengagement="explicit_rejection",
            disengagement_ref="m2",
            next_action="suggest_date",
        ),
    )
    assert (a.stage, a.next_action) == (S.PAUSED, NextAction.STOP)
    assert a.reasons == (DecisionReason.DISENGAGEMENT_PAUSE,)
    await assert_persisted(session, t, a)


async def test_stop_request_pauses_even_at_modest_confidence(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.RAPPORT)
    await contact_says(session, t, "please stop texting me")
    a = await run(
        session,
        harness,
        t,
        output("rapport", 0.45, disengagement="stop_request", disengagement_ref="m1"),
    )
    assert (a.stage, a.uncertain, a.next_action) == (S.PAUSED, True, NextAction.STOP)


async def test_paused_does_not_resume_without_new_contact_engagement(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.RAPPORT)
    await contact_says(session, t, "I'm not feeling it, sorry")
    a = await run(
        session,
        harness,
        t,
        output("paused", 0.8, disengagement="explicit_rejection", disengagement_ref="m1"),
    )
    assert a.stage is S.PAUSED

    # The user keeps writing; the model wrongly cites the user's message as positive.
    await user_says(session, t, "Come on, one drink?")
    a = await run(
        session,
        harness,
        t,
        output("rapport", 0.9, signals=[signal("enthusiasm", "positive", "m2")]),
    )
    assert (a.stage, a.uncertain) == (S.PAUSED, True)
    assert a.reasons == (DecisionReason.RESUME_UNSUPPORTED,)
    assert a.next_action in {NextAction.STOP, NextAction.GIVE_SPACE, NextAction.WAIT}

    # The model re-cites the OLD inbound message (before the pause): still no resume.
    a = await run(
        session,
        harness,
        t,
        output("rapport", 0.9, signals=[signal("old message", "positive", "m1")]),
    )
    assert (a.stage, a.reasons) == (S.PAUSED, (DecisionReason.RESUME_UNSUPPORTED,))

    # The contact genuinely re-engages.
    await contact_says(session, t, "Actually, sorry, rough week. I'd love to chat again")
    a = await run(
        session,
        harness,
        t,
        output("rapport", 0.8, signals=[signal("re-engages", "positive", "m3")]),
    )
    assert (a.previous_stage, a.stage, a.uncertain) == (S.PAUSED, S.RAPPORT, False)


async def test_polite_deflection_stays_put(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.INTEREST)
    await user_says(session, t, "Want to get drinks Friday?")
    await contact_says(session, t, "Haha maybe! Anyway did you see the game last night?")
    a = await run(
        session,
        harness,
        t,
        output(
            "scheduling",
            0.8,
            signals=[signal("changes subject", "uncertain", "m2")],
            disengagement="soft_deflection",
            disengagement_ref="m2",
            next_action="suggest_date",
        ),
    )
    assert (a.stage, a.next_action) == (S.INTEREST, NextAction.GIVE_SPACE)
    assert a.reasons == (DecisionReason.DEFLECTION_HOLD,)
    await assert_persisted(session, t, a)


async def test_busy_stays_put_and_waits(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.DATE_PROPOSAL)
    await contact_says(session, t, "Crazy week at work, can I get back to you?")
    a = await run(
        session,
        harness,
        t,
        output("scheduling", 0.9, disengagement="busy", disengagement_ref="m1"),
    )
    assert (a.stage, a.next_action, a.reasons) == (
        S.DATE_PROPOSAL,
        NextAction.WAIT,
        (DecisionReason.BUSY_HOLD,),
    )


async def test_proposed_time_without_acceptance_is_not_confirmed(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.INTEREST)
    await user_says(session, t, "Dinner at Nopa, Friday at 8?")
    await contact_says(session, t, "Ooh Nopa is great")  # enthusiasm, not acceptance
    a = await run(
        session,
        harness,
        t,
        output(
            "confirmed",
            0.97,  # very confident: still not enough
            signals=[signal("likes the venue", "positive", "m2")],
            agreement=agreement("m1", user="m1", contact=None),
        ),
    )
    assert a.stage is not S.CONFIRMED
    assert (a.stage, a.uncertain) == (S.DATE_PROPOSAL, True)
    assert a.reasons == (DecisionReason.CONFIRMATION_UNSUPPORTED,)
    assert a.next_action is NextAction.CONFIRM_DETAILS
    await assert_persisted(session, t, a)


async def test_contact_agreement_without_a_specific_plan_is_not_confirmed(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.SCHEDULING)
    await user_says(session, t, "We should get dinner sometime next week")
    await contact_says(session, t, "Yes definitely!")
    a = await run(
        session,
        harness,
        t,
        output(
            "confirmed",
            0.95,
            signals=[signal("agrees", "positive", "m2")],
            agreement=agreement("m1", user="m1", contact="m2", time=False, place=True),
        ),
    )
    assert (a.stage, a.uncertain) == (S.SCHEDULING, True)


async def test_low_confidence_keeps_stage_and_is_uncertain(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.RAPPORT)
    await contact_says(session, t, "lol ok")
    a = await run(
        session,
        harness,
        t,
        output(
            "interest",
            0.4,
            signals=[signal("short reply", "uncertain", "m1")],
            ambiguities=["tone of the short reply is unclear"],
        ),
    )
    assert (a.stage, a.uncertain, a.next_action) == (S.RAPPORT, True, NextAction.ASK_USER)
    assert a.reasons == (DecisionReason.LOW_CONFIDENCE,)
    assert a.ambiguities == ("tone of the short reply is unclear",)
    stage, body = await stored(session, t)
    assert stage is S.RAPPORT
    assert body is not None and body["uncertain"] is True


# --------------------------------------------------------------------------- model failures


@pytest.mark.parametrize(
    "step",
    [
        "this is not json",
        json.dumps({"stage": "confirmed"}),  # schema-invalid: missing fields
        json.dumps(output("rapport", 0.8) | {"confidence": "0.8"}),  # strict: no coercion
        json.dumps(output("married", 0.8)),  # not a contract stage
        json.dumps(output("rapport", 0.8) | {"send_now": True}),  # extra field forbidden
        LLMServerError("boom"),
    ],
    ids=["not-json", "missing-fields", "string-number", "bad-stage", "extra-field", "5xx"],
)
async def test_model_failure_uses_rule_fallback(
    session: AsyncSession, harness: Harness, step: Any
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.INTEREST)
    await contact_says(session, t, "So when are we getting that drink?")
    a = await run(session, harness, t, step)
    assert (a.stage, a.uncertain, a.fallback) == (S.INTEREST, True, True)
    assert a.next_action is NextAction.ASK_USER
    assert a.reasons == (DecisionReason.MODEL_UNAVAILABLE,)
    assert a.proposed_stage is None
    assert a.confidence == 0.0
    await assert_persisted(session, t, a)


async def test_timeout_uses_rule_fallback(session: AsyncSession) -> None:
    h = build_harness(timeout_s=0.05)
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.SCHEDULING)
    await contact_says(session, t, "Sounds great")
    a = await run(session, h, t, MockReply(content=json.dumps(output("confirmed", 1)), delay_s=1))
    assert (a.stage, a.uncertain, a.fallback, a.fallback_error) == (
        S.SCHEDULING,
        True,
        True,
        "timeout",
    )
    # A raised timeout error behaves the same.
    a = await run(session, h, t, LLMTimeoutError("slow"))
    assert (a.stage, a.fallback_error) == (S.SCHEDULING, "timeout")


async def test_fallback_on_paused_never_unpauses(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.PAUSED)
    await contact_says(session, t, "hey")
    a = await run(session, harness, t, "garbage")
    assert (a.stage, a.next_action) == (S.PAUSED, NextAction.STOP)


async def test_unknown_cited_message_refs_are_rejected(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.RAPPORT)
    await contact_says(session, t, "Would love to meet up")
    for bad in (
        output("interest", 0.9, signals=[signal("wants to meet", "positive", "m7")]),
        output("rapport", 0.9, disengagement="stop_request", disengagement_ref="m2"),
        output(
            "confirmed",
            0.99,
            signals=[signal("x", "positive", "m1")],
            agreement=agreement("m1", user="m9", contact="m1"),
        ),
        output("interest", 0.9, signals=[signal("bad ref", "positive", "msg-1")]),
    ):
        a = await run(session, harness, t, bad)
        assert (a.stage, a.fallback, a.uncertain) == (S.RAPPORT, True, True)
        assert a.fallback_error in {"schema_invalid"}
    _, body = await stored(session, t)
    assert body is not None and body["stage"] == "rapport"


async def test_empty_conversation_does_not_call_model(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    a = await harness.analyzer.analyze_conversation(session, scope, t.conversation_id)
    assert (a.stage, a.uncertain, a.fallback_error) == (S.NEW, True, "no_messages")
    assert harness.provider.calls == 0


# --------------------------------------------------------------------------- determinism


SCRIPT: list[tuple[str, str, dict[str, Any]]] = [
    ("contact", "Hi there!", output("introduction", 0.8, signals=[signal("hi", "positive", "m1")])),
    ("user", "Hey! How's your week?", output("introduction", 0.7)),
    (
        "contact",
        "Good! You seem fun, drinks Friday 8 at Zeitgeist?",
        output(
            "date_proposal",
            0.85,
            signals=[signal("proposes", "positive", "m3")],
            agreement=agreement("m3", user=None, contact="m3"),
        ),
    ),
    (
        "user",
        "Friday 8 at Zeitgeist, I'm in",
        output(
            "confirmed",
            0.9,
            signals=[signal("both agree", "positive", "m4")],
            agreement=agreement("m3", user="m4", contact="m3"),
        ),
    ),
]


async def _replay(session: AsyncSession) -> list[tuple[str, bool, str, tuple[str, ...]]]:
    h = build_harness()
    scope = await make_user(session)
    t = await make_thread(session, scope)
    trace = []
    for who, text, step in SCRIPT:
        if who == "contact":
            await contact_says(session, t, text)
        else:
            await user_says(session, t, text)
        a = await run(session, h, t, step)
        trace.append((a.stage.value, a.uncertain, a.next_action.value, tuple(map(str, a.reasons))))
    return trace


async def test_stage_changes_are_reproducible(session: AsyncSession) -> None:
    first = await _replay(session)
    second = await _replay(session)
    assert first == second
    # And the trace itself is the expected one (the model said "date_proposal" ->
    # "confirmed"; the rules check that both parties agreed to the specific plan).
    assert [s for s, *_ in first] == ["introduction", "introduction", "date_proposal", "confirmed"]


async def test_prompt_is_deterministic_and_bounded(session: AsyncSession) -> None:
    from app.ai.analyzer import AnalyzerLimits, ConversationAnalyzer
    from app.ai.memory import MemoryService

    h = build_harness()
    analyzer = ConversationAnalyzer(
        h.gateway,
        memory=MemoryService(now=lambda: NOW),
        limits=AnalyzerLimits(max_messages=3),
        now=lambda: NOW,
    )
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.RAPPORT)
    for i in range(6):
        await contact_says(session, t, f"message number {i}")
    h.provider.push(output("rapport", 0.8), output("rapport", 0.8))
    await analyzer.analyze_conversation(session, scope, t.conversation_id)
    await analyzer.analyze_conversation(session, scope, t.conversation_id)
    first, second = h.provider.requests
    assert first.messages == second.messages
    prompt = first.messages[-1].content
    assert "message number 5" in prompt and "message number 3" in prompt
    assert "message number 2" not in prompt  # outside the bounded window
    assert '"ref": "m4"' not in prompt
    assert h.sink.records[0].operation == ANALYZE_OPERATION
    assert first.temperature == 0


async def test_window_refs_map_to_latest_messages(session: AsyncSession) -> None:
    """With a window, m1 is the oldest message in the window, not in the thread."""
    from app.ai.analyzer import AnalyzerLimits, ConversationAnalyzer
    from app.ai.memory import MemoryService

    h = build_harness()
    analyzer = ConversationAnalyzer(
        h.gateway, memory=MemoryService(), limits=AnalyzerLimits(max_messages=2)
    )
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.RAPPORT)
    for i in range(4):
        await contact_says(session, t, f"m{i}")
    h.provider.push(output("interest", 0.8, signals=[signal("x", "positive", "m1")]))
    a = await analyzer.analyze_conversation(session, scope, t.conversation_id)
    assert a.source_message_ids == (t.messages[2],)


# --------------------------------------------------------------------------- isolation & audit


async def test_cross_user_isolation(session: AsyncSession, harness: Harness) -> None:
    alice = await make_user(session)
    bob = await make_user(session)
    t = await make_thread(session, alice, stage=S.RAPPORT)
    await contact_says(session, t, "hello")
    harness.provider.push(output("interest", 0.9, signals=[signal("x", "positive", "m1")]))
    with pytest.raises(NotFoundError):
        await harness.analyzer.analyze_conversation(session, bob, t.conversation_id)
    assert harness.provider.calls == 0  # nothing of Alice's reached the model
    stage, body = await stored(session, t)
    assert (stage, body) == (S.RAPPORT, None)
    bob_actions = (
        await session.scalars(select(AgentAction).where(AgentAction.user_id == bob.user_id))
    ).all()
    assert bob_actions == []

    # Bob's own thread never sees Alice's messages in its prompt.
    tb = await make_thread(session, bob, stage=S.RAPPORT)
    await contact_says(session, tb, "bob's contact message")
    a = await harness.analyzer.analyze_conversation(session, bob, tb.conversation_id)
    prompt = harness.provider.requests[-1].messages[-1].content
    assert "hello" not in prompt and "bob's contact message" in prompt
    assert a.stage is S.INTEREST
    # The scripted output cited m1 in Bob's prompt, which maps to Bob's message only.
    assert a.source_message_ids == (tb.messages[0],)


async def test_audit_entry_is_redacted(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope, stage=S.RAPPORT)
    private_text = "my address is 12 Secret Lane"
    await contact_says(session, t, private_text)
    await run(
        session,
        harness,
        t,
        output(
            "interest",
            0.8,
            signals=[signal("shares address 12 Secret Lane", "positive", "m1")],
            ambiguities=["Secret Lane note"],
        ),
    )
    actions = (
        await session.scalars(select(AgentAction).where(AgentAction.user_id == scope.user_id))
    ).all()
    assert [x.type for x in actions] == [AgentActionType.ANALYSIS_UPDATED]
    action = actions[0]
    assert action.conversation_id == t.conversation_id
    assert action.reason_code == "accepted"
    assert action.details["stage"] == "interest"
    assert "Secret" not in json.dumps(action.details)
