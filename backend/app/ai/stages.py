"""Deterministic conversation-stage rules (WING-009). The model proposes; these rules decide.

This module is pure: no database, no LLM, no I/O, no clock. Given the stored stage, the
model's (already schema-validated) proposal and the messages that proposal may cite, it
returns a :class:`StageDecision`. The same inputs always give the same decision.

Rules, applied in order (the first that applies decides):

1. **Disengagement.** A cited *inbound* ``stop_request`` or ``explicit_rejection`` moves
   the conversation to ``paused`` (next action ``stop``) whenever the model's confidence
   is at least ``StagePolicy.pause_threshold``, a deliberately lower bar than for moving
   forward: when in doubt the analyzer errs towards stopping. ``busy`` and
   ``soft_deflection`` never move the stage: it stays put (next action ``wait`` or
   ``give_space``), however keen the model is to push on.
2. **No change proposed.** The stage stays.
3. **Confidence.** Below ``StagePolicy.move_threshold`` the stage does not move and the
   result is labeled uncertain (next action ``ask_user``).
4. **Allowed transitions.** The target must be in :data:`ALLOWED_TRANSITIONS` for the
   current stage. Regressions are in the table where real conversations go backwards
   (a plan falls through, a confirmed date gets rescheduled).
5. **Evidence.** Every change must cite at least one message.
6. **Stage gates.**

   * ``confirmed`` needs explicit evidence that BOTH parties agreed to ONE specific plan
     (a time and a place or activity): a cited outbound message from the user and a
     cited inbound message from the contact, at least one of them strictly after the
     message stating the plan, no negative signal after the agreement, and confidence of
     at least ``StagePolicy.confirm_threshold``. A proposed time alone, one-sided
     enthusiasm, or model confidence by itself is never confirmation. When the gate
     fails the stage stays, except that a cited plan moves an earlier stage
     (``new``, ``introduction``, ``rapport``, ``interest``) to ``date_proposal``: a plan
     was proposed, nothing more. Either way the result is uncertain.
   * ``paused`` is entered only through rule 1. A model that merely proposes ``paused``
     without a cited stop or rejection leaves the stage where it is.
   * Leaving ``paused`` needs a positive signal citing an inbound message newer than
     anything the stored analysis cited (the contact re-engaged).

Any rule that withholds a move the model proposed labels the result uncertain.
:func:`fallback_decision` is the rule-only result used when the model fails: keep the
stage, uncertain, ``ask_user``, never advance.
"""

import uuid
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from enum import StrEnum

from app.db.enums import ConversationStage

__all__ = [
    "ALLOWED_TRANSITIONS",
    "AgreementEvidence",
    "CitedMessage",
    "DecisionReason",
    "Disengagement",
    "NextAction",
    "Polarity",
    "SignalEvidence",
    "StageDecision",
    "StagePolicy",
    "StageProposal",
    "check_confirmation",
    "decide_stage",
    "fallback_decision",
]

S = ConversationStage


class NextAction(StrEnum):
    """``ConversationAnalysis.next_action`` in the contract (value for value)."""

    REPLY = "reply"
    WAIT = "wait"
    ASK_QUESTION = "ask_question"
    SUGGEST_DATE = "suggest_date"
    CONFIRM_DETAILS = "confirm_details"
    GIVE_SPACE = "give_space"
    STOP = "stop"
    ASK_USER = "ask_user"


class Polarity(StrEnum):
    """``ConversationAnalysis.interest_signals[].polarity`` in the contract."""

    POSITIVE = "positive"
    NEGATIVE = "negative"
    NEUTRAL = "neutral"
    UNCERTAIN = "uncertain"


class Disengagement(StrEnum):
    """How (if at all) the contact is pulling back, as the model reads it."""

    NONE = "none"
    SOFT_DEFLECTION = "soft_deflection"  # "haha maybe", changing the subject
    BUSY = "busy"  # "crazy week, talk later"
    EXPLICIT_REJECTION = "explicit_rejection"  # "I'm not interested"
    STOP_REQUEST = "stop_request"  # "please stop messaging me"


class DecisionReason(StrEnum):
    """Stable codes explaining a decision. Safe to log and audit (no message content)."""

    ACCEPTED = "accepted"
    NO_CHANGE_PROPOSED = "no_change_proposed"
    LOW_CONFIDENCE = "low_confidence"
    TRANSITION_NOT_ALLOWED = "transition_not_allowed"
    NO_EVIDENCE = "no_evidence"
    CONFIRMATION_UNSUPPORTED = "confirmation_unsupported"
    DISENGAGEMENT_PAUSE = "disengagement_pause"
    DISENGAGEMENT_LOW_CONFIDENCE = "disengagement_low_confidence"
    DEFLECTION_HOLD = "deflection_hold"
    BUSY_HOLD = "busy_hold"
    PAUSE_UNSUPPORTED = "pause_unsupported"
    RESUME_UNSUPPORTED = "resume_unsupported"
    MODEL_UNAVAILABLE = "model_unavailable"


# Moves the rules allow between stages, besides staying put. ``paused`` is reachable from
# every other stage, but only through the disengagement rule, so it is not listed here.
ALLOWED_TRANSITIONS: Mapping[ConversationStage, frozenset[ConversationStage]] = {
    # ``new`` means "never analyzed": the first analysis of an imported thread may land
    # anywhere up to ``confirmed`` (which is still gated). It cannot be ``follow_up``.
    S.NEW: frozenset(
        {S.INTRODUCTION, S.RAPPORT, S.INTEREST, S.DATE_PROPOSAL, S.SCHEDULING, S.CONFIRMED}
    ),
    S.INTRODUCTION: frozenset({S.RAPPORT, S.INTEREST, S.DATE_PROPOSAL}),
    S.RAPPORT: frozenset({S.INTRODUCTION, S.INTEREST, S.DATE_PROPOSAL, S.CONFIRMED}),
    S.INTEREST: frozenset({S.RAPPORT, S.DATE_PROPOSAL, S.SCHEDULING, S.CONFIRMED}),
    S.DATE_PROPOSAL: frozenset({S.RAPPORT, S.INTEREST, S.SCHEDULING, S.CONFIRMED}),
    S.SCHEDULING: frozenset({S.RAPPORT, S.INTEREST, S.DATE_PROPOSAL, S.CONFIRMED}),
    S.CONFIRMED: frozenset({S.DATE_PROPOSAL, S.SCHEDULING, S.FOLLOW_UP}),
    S.FOLLOW_UP: frozenset({S.RAPPORT, S.INTEREST, S.DATE_PROPOSAL, S.SCHEDULING}),
    S.PAUSED: frozenset({S.INTRODUCTION, S.RAPPORT, S.INTEREST}),
}

# Next actions that make sense while a conversation is paused.
_PAUSED_ACTIONS = frozenset({NextAction.STOP, NextAction.GIVE_SPACE, NextAction.WAIT})
_BEFORE_PROPOSAL = frozenset({S.NEW, S.INTRODUCTION, S.RAPPORT, S.INTEREST})
_HARD_STOP = frozenset({Disengagement.STOP_REQUEST, Disengagement.EXPLICIT_REJECTION})


@dataclass(frozen=True)
class StagePolicy:
    """Confidence thresholds. ``pause_threshold <= move_threshold <= confirm_threshold``."""

    # Below this, the stage never moves (except into ``paused``, see rule 1).
    move_threshold: float = 0.6
    # ``confirmed`` needs at least this much, on top of the two-party evidence.
    confirm_threshold: float = 0.8
    # A cited stop or rejection pauses at or above this.
    pause_threshold: float = 0.35

    def __post_init__(self) -> None:
        if not 0 <= self.pause_threshold <= self.move_threshold <= self.confirm_threshold <= 1:
            raise ValueError(
                "need 0 <= pause_threshold <= move_threshold <= confirm_threshold <= 1"
            )


@dataclass(frozen=True)
class CitedMessage:
    """A message the model was shown: who sent it and where it sits in the window."""

    id: uuid.UUID
    inbound: bool  # from the contact; False means the user sent it
    position: int  # chronological index in the analyzed window (0 = oldest)


@dataclass(frozen=True)
class SignalEvidence:
    polarity: Polarity
    message_id: uuid.UUID | None


@dataclass(frozen=True)
class AgreementEvidence:
    """The model's claim that a specific plan exists and who agreed to it."""

    plan_message_id: uuid.UUID
    has_specific_time: bool
    has_place_or_activity: bool
    user_agreement_message_id: uuid.UUID | None
    contact_agreement_message_id: uuid.UUID | None


@dataclass(frozen=True)
class StageProposal:
    """The model's proposal with message refs already resolved to IDs."""

    stage: ConversationStage
    confidence: float
    next_action: NextAction
    signals: tuple[SignalEvidence, ...] = ()
    disengagement: Disengagement = Disengagement.NONE
    disengagement_message_id: uuid.UUID | None = None
    agreement: AgreementEvidence | None = None


@dataclass(frozen=True)
class StageDecision:
    previous_stage: ConversationStage
    # ``None`` when the model produced nothing usable (fallback).
    proposed_stage: ConversationStage | None
    stage: ConversationStage
    uncertain: bool
    next_action: NextAction
    reasons: tuple[DecisionReason, ...] = field(default=())

    @property
    def changed(self) -> bool:
        return self.stage is not self.previous_stage

    @property
    def fallback(self) -> bool:
        return DecisionReason.MODEL_UNAVAILABLE in self.reasons


def fallback_decision(current: ConversationStage) -> StageDecision:
    """Rule-only result when the model fails: keep the stage, uncertain, never advance."""
    return StageDecision(
        previous_stage=current,
        proposed_stage=None,
        stage=current,
        uncertain=True,
        next_action=_paused_action(NextAction.ASK_USER)
        if current is S.PAUSED
        else NextAction.ASK_USER,
        reasons=(DecisionReason.MODEL_UNAVAILABLE,),
    )


def check_confirmation(
    proposal: StageProposal,
    messages: Mapping[uuid.UUID, CitedMessage],
    policy: StagePolicy,
) -> bool:
    """True only with explicit evidence that both parties agreed to one specific plan."""
    agreement = proposal.agreement
    if agreement is None or proposal.confidence < policy.confirm_threshold:
        return False
    if proposal.disengagement is not Disengagement.NONE:
        return False
    if not (agreement.has_specific_time and agreement.has_place_or_activity):
        return False
    if agreement.user_agreement_message_id is None:
        return False
    if agreement.contact_agreement_message_id is None:
        return False
    plan = messages.get(agreement.plan_message_id)
    user = messages.get(agreement.user_agreement_message_id)
    contact = messages.get(agreement.contact_agreement_message_id)
    if plan is None or user is None or contact is None:
        return False
    # Each side's agreement must come from that side.
    if user.inbound or not contact.inbound:
        return False
    # Nobody agrees to a plan before it is stated, and someone must answer the plan:
    # the proposer's own message may count as their agreement, but not as both.
    if user.position < plan.position or contact.position < plan.position:
        return False
    if max(user.position, contact.position) <= plan.position:
        return False
    # A negative signal after the agreement (cancellation, second thoughts) undoes it.
    agreed_at = max(user.position, contact.position)
    for signal in proposal.signals:
        if signal.polarity is Polarity.NEGATIVE and signal.message_id is not None:
            cited = messages.get(signal.message_id)
            if cited is not None and cited.position > agreed_at:
                return False
    return True


def decide_stage(
    current: ConversationStage,
    proposal: StageProposal,
    messages: Mapping[uuid.UUID, CitedMessage],
    *,
    policy: StagePolicy | None = None,
    resume_after_position: int | None = None,
) -> StageDecision:
    """Apply the rules (see module docstring) and return the decided stage.

    ``messages`` are the messages the model was shown, keyed by ID; every ID in
    ``proposal`` must be among them (the analyzer rejects output that cites anything
    else before calling this). ``resume_after_position``: leaving ``paused`` needs a
    positive signal on an inbound message at a later position than this.
    """
    policy = policy or StagePolicy()
    target = proposal.stage
    confident = proposal.confidence >= policy.move_threshold

    def decide(
        stage: ConversationStage,
        reason: DecisionReason,
        *,
        uncertain: bool,
        action: NextAction | None = None,
    ) -> StageDecision:
        next_action = action or proposal.next_action
        if uncertain and action is None:
            next_action = NextAction.ASK_USER
        if stage is S.PAUSED:
            next_action = _paused_action(next_action)
        return StageDecision(
            previous_stage=current,
            proposed_stage=target,
            stage=stage,
            uncertain=uncertain,
            next_action=next_action,
            reasons=(reason,),
        )

    # 1. Disengagement.
    if proposal.disengagement in _HARD_STOP:
        cited = _get(messages, proposal.disengagement_message_id)
        if cited is not None and cited.inbound:
            if proposal.confidence >= policy.pause_threshold:
                return decide(
                    S.PAUSED,
                    DecisionReason.DISENGAGEMENT_PAUSE,
                    uncertain=not confident,
                    action=NextAction.STOP,
                )
            return decide(current, DecisionReason.DISENGAGEMENT_LOW_CONFIDENCE, uncertain=True)
        # A "stop" that is not the contact's own message: no pause, and no advance.
        return decide(current, DecisionReason.PAUSE_UNSUPPORTED, uncertain=True)
    if proposal.disengagement is Disengagement.BUSY:
        return decide(
            current, DecisionReason.BUSY_HOLD, uncertain=not confident, action=NextAction.WAIT
        )
    if proposal.disengagement is Disengagement.SOFT_DEFLECTION:
        return decide(
            current,
            DecisionReason.DEFLECTION_HOLD,
            uncertain=not confident,
            action=NextAction.GIVE_SPACE,
        )

    # 2. Nothing to change.
    if target is current:
        return decide(current, DecisionReason.NO_CHANGE_PROPOSED, uncertain=not confident)

    # 3. Confidence.
    if not confident:
        return decide(current, DecisionReason.LOW_CONFIDENCE, uncertain=True)

    # 6b. Entering ``paused`` happens only through rule 1.
    if target is S.PAUSED:
        return decide(current, DecisionReason.PAUSE_UNSUPPORTED, uncertain=True)

    # 4. Allowed transitions.
    if target not in ALLOWED_TRANSITIONS[current]:
        return decide(current, DecisionReason.TRANSITION_NOT_ALLOWED, uncertain=True)

    # 5. Every change cites evidence.
    cited_signals = [s for s in proposal.signals if _get(messages, s.message_id) is not None]
    if not cited_signals:
        return decide(current, DecisionReason.NO_EVIDENCE, uncertain=True)

    # 6c. Leaving ``paused`` needs the contact to have re-engaged.
    if current is S.PAUSED and not _reengaged(proposal, messages, resume_after_position):
        return decide(current, DecisionReason.RESUME_UNSUPPORTED, uncertain=True)

    # 6a. ``confirmed`` needs two-party agreement on a specific plan.
    if target is S.CONFIRMED and not check_confirmation(proposal, messages, policy):
        fallback = current
        if (
            proposal.agreement is not None
            and proposal.agreement.plan_message_id in messages
            and current in _BEFORE_PROPOSAL
            and S.DATE_PROPOSAL in ALLOWED_TRANSITIONS[current]
        ):
            fallback = S.DATE_PROPOSAL
        action = proposal.next_action
        if action is NextAction.REPLY or action is NextAction.ASK_USER:
            action = NextAction.CONFIRM_DETAILS
        return decide(
            fallback, DecisionReason.CONFIRMATION_UNSUPPORTED, uncertain=True, action=action
        )

    return decide(target, DecisionReason.ACCEPTED, uncertain=False)


def _get(
    messages: Mapping[uuid.UUID, CitedMessage], message_id: uuid.UUID | None
) -> CitedMessage | None:
    return None if message_id is None else messages.get(message_id)


def _reengaged(
    proposal: StageProposal,
    messages: Mapping[uuid.UUID, CitedMessage],
    resume_after_position: int | None,
) -> bool:
    floor = -1 if resume_after_position is None else resume_after_position
    positives: Sequence[CitedMessage] = [
        m
        for s in proposal.signals
        if s.polarity is Polarity.POSITIVE and (m := _get(messages, s.message_id)) is not None
    ]
    return any(m.inbound and m.position > floor for m in positives)


def _paused_action(action: NextAction) -> NextAction:
    return action if action in _PAUSED_ACTIONS else NextAction.STOP
