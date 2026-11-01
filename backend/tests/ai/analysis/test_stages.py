"""Pure unit tests for the deterministic stage rules (no DB, no model)."""

import uuid

import pytest

from app.ai.stages import (
    ALLOWED_TRANSITIONS,
    AgreementEvidence,
    CitedMessage,
    DecisionReason,
    Disengagement,
    NextAction,
    Polarity,
    SignalEvidence,
    StagePolicy,
    StageProposal,
    check_confirmation,
    decide_stage,
    fallback_decision,
)
from app.db.enums import ConversationStage

S = ConversationStage


def msgs(*inbound: bool) -> list[CitedMessage]:
    return [CitedMessage(id=uuid.uuid4(), inbound=i, position=n) for n, i in enumerate(inbound)]


def by_id(ms: list[CitedMessage]) -> dict[uuid.UUID, CitedMessage]:
    return {m.id: m for m in ms}


def pos(m: CitedMessage) -> SignalEvidence:
    return SignalEvidence(Polarity.POSITIVE, m.id)


def test_transition_table_covers_every_stage_and_never_lists_paused() -> None:
    assert set(ALLOWED_TRANSITIONS) == set(S)
    for source, targets in ALLOWED_TRANSITIONS.items():
        assert S.PAUSED not in targets
        assert source not in targets
    assert S.FOLLOW_UP not in ALLOWED_TRANSITIONS[S.NEW]


def test_policy_thresholds_must_be_ordered() -> None:
    with pytest.raises(ValueError):
        StagePolicy(move_threshold=0.9, confirm_threshold=0.8)
    with pytest.raises(ValueError):
        StagePolicy(pause_threshold=0.7, move_threshold=0.6)


def test_disallowed_jump_is_held_and_uncertain() -> None:
    ms = msgs(True)
    d = decide_stage(
        S.INTRODUCTION,
        StageProposal(S.FOLLOW_UP, 0.95, NextAction.REPLY, signals=(pos(ms[0]),)),
        by_id(ms),
    )
    assert (d.stage, d.uncertain, d.reasons) == (
        S.INTRODUCTION,
        True,
        (DecisionReason.TRANSITION_NOT_ALLOWED,),
    )
    assert d.next_action is NextAction.ASK_USER


def test_change_without_cited_evidence_is_held() -> None:
    ms = msgs(True)
    d = decide_stage(S.RAPPORT, StageProposal(S.INTEREST, 0.9, NextAction.REPLY), by_id(ms))
    assert (d.stage, d.reasons) == (S.RAPPORT, (DecisionReason.NO_EVIDENCE,))


def test_regression_allowed_with_evidence() -> None:
    ms = msgs(False, True)
    neg = SignalEvidence(Polarity.NEGATIVE, ms[1].id)
    d = decide_stage(
        S.CONFIRMED,
        StageProposal(S.SCHEDULING, 0.8, NextAction.ASK_QUESTION, signals=(neg,)),
        by_id(ms),
    )
    assert (d.stage, d.uncertain, d.changed) == (S.SCHEDULING, False, True)


def test_model_proposing_paused_without_cited_stop_does_not_pause() -> None:
    ms = msgs(True)
    d = decide_stage(
        S.RAPPORT,
        StageProposal(S.PAUSED, 0.9, NextAction.STOP, signals=(pos(ms[0]),)),
        by_id(ms),
    )
    assert (d.stage, d.reasons) == (S.RAPPORT, (DecisionReason.PAUSE_UNSUPPORTED,))


def test_stop_cited_on_users_own_message_does_not_pause() -> None:
    ms = msgs(False)
    d = decide_stage(
        S.RAPPORT,
        StageProposal(
            S.RAPPORT,
            0.9,
            NextAction.REPLY,
            disengagement=Disengagement.STOP_REQUEST,
            disengagement_message_id=ms[0].id,
        ),
        by_id(ms),
    )
    assert (d.stage, d.uncertain, d.reasons) == (
        S.RAPPORT,
        True,
        (DecisionReason.PAUSE_UNSUPPORTED,),
    )


def test_rejection_below_pause_threshold_stays_but_never_advances() -> None:
    ms = msgs(True)
    d = decide_stage(
        S.INTEREST,
        StageProposal(
            S.SCHEDULING,
            0.2,
            NextAction.SUGGEST_DATE,
            signals=(pos(ms[0]),),
            disengagement=Disengagement.EXPLICIT_REJECTION,
            disengagement_message_id=ms[0].id,
        ),
        by_id(ms),
    )
    assert (d.stage, d.uncertain, d.next_action) == (S.INTEREST, True, NextAction.ASK_USER)
    assert d.reasons == (DecisionReason.DISENGAGEMENT_LOW_CONFIDENCE,)


@pytest.mark.parametrize("current", list(S))
def test_fallback_never_moves(current: ConversationStage) -> None:
    d = fallback_decision(current)
    assert (d.stage, d.uncertain, d.fallback, d.changed) == (current, True, True, False)
    if current is S.PAUSED:
        assert d.next_action is NextAction.STOP


class TestConfirmationGate:
    policy = StagePolicy()

    def proposal(self, agreement: AgreementEvidence, **kw: object) -> StageProposal:
        return StageProposal(
            S.CONFIRMED,
            float(kw.pop("confidence", 0.9)),
            NextAction.REPLY,
            agreement=agreement,
            **kw,  # type: ignore[arg-type]
        )

    def test_both_sides_after_plan(self) -> None:
        plan, yes = msgs(False, True)
        a = AgreementEvidence(plan.id, True, True, plan.id, yes.id)
        assert check_confirmation(self.proposal(a), by_id([plan, yes]), self.policy)

    def test_only_the_proposer(self) -> None:
        plan, other = msgs(False, True)
        a = AgreementEvidence(plan.id, True, True, plan.id, None)
        assert not check_confirmation(self.proposal(a), by_id([plan, other]), self.policy)

    def test_plan_message_cannot_count_for_both(self) -> None:
        (plan,) = msgs(True)
        a = AgreementEvidence(plan.id, True, True, plan.id, plan.id)
        assert not check_confirmation(self.proposal(a), by_id([plan]), self.policy)

    def test_sides_must_match_direction(self) -> None:
        plan, yes = msgs(False, False)  # "contact" agreement is actually the user's
        a = AgreementEvidence(plan.id, True, True, plan.id, yes.id)
        assert not check_confirmation(self.proposal(a), by_id([plan, yes]), self.policy)

    def test_agreement_before_plan_does_not_count(self) -> None:
        early_yes, plan, again = msgs(True, False, False)
        a = AgreementEvidence(plan.id, True, True, again.id, early_yes.id)
        assert not check_confirmation(
            self.proposal(a), by_id([early_yes, plan, again]), self.policy
        )

    def test_needs_time_and_place(self) -> None:
        plan, yes = msgs(False, True)
        for time, place in ((False, True), (True, False)):
            a = AgreementEvidence(plan.id, time, place, plan.id, yes.id)
            assert not check_confirmation(self.proposal(a), by_id([plan, yes]), self.policy)

    def test_needs_confirm_threshold(self) -> None:
        plan, yes = msgs(False, True)
        a = AgreementEvidence(plan.id, True, True, plan.id, yes.id)
        assert not check_confirmation(
            self.proposal(a, confidence=0.7), by_id([plan, yes]), self.policy
        )

    def test_cancellation_after_agreement_undoes_it(self) -> None:
        plan, yes, cancel = msgs(False, True, True)
        a = AgreementEvidence(plan.id, True, True, plan.id, yes.id)
        p = self.proposal(a, signals=(SignalEvidence(Polarity.NEGATIVE, cancel.id),))
        assert not check_confirmation(p, by_id([plan, yes, cancel]), self.policy)

    def test_unknown_ids_never_confirm(self) -> None:
        plan, yes = msgs(False, True)
        a = AgreementEvidence(plan.id, True, True, plan.id, uuid.uuid4())
        assert not check_confirmation(self.proposal(a), by_id([plan, yes]), self.policy)

    def test_failed_gate_from_scheduling_stays_in_scheduling(self) -> None:
        plan, maybe = msgs(False, True)
        a = AgreementEvidence(plan.id, True, True, plan.id, None)
        p = StageProposal(S.CONFIRMED, 0.99, NextAction.REPLY, signals=(pos(maybe),), agreement=a)
        d = decide_stage(S.SCHEDULING, p, by_id([plan, maybe]))
        assert (d.stage, d.uncertain, d.next_action) == (
            S.SCHEDULING,
            True,
            NextAction.CONFIRM_DETAILS,
        )

    def test_failed_gate_without_any_plan_stays(self) -> None:
        (m,) = msgs(True)
        p = StageProposal(S.CONFIRMED, 0.99, NextAction.REPLY, signals=(pos(m),))
        d = decide_stage(S.RAPPORT, p, by_id([m]))
        assert (d.stage, d.reasons) == (S.RAPPORT, (DecisionReason.CONFIRMATION_UNSUPPORTED,))


def test_decisions_are_pure_and_repeatable() -> None:
    ms = msgs(True, False)
    p = StageProposal(S.INTEREST, 0.8, NextAction.REPLY, signals=(pos(ms[0]),))
    results = {decide_stage(S.RAPPORT, p, by_id(ms)) for _ in range(20)}
    assert len(results) == 1
