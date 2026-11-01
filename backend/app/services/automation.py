"""Automation controller (WING-011): policy engine, approval queue, pause and takeover,
and the end-to-end inbound pipeline.

**LLM output is never authorization.** :func:`evaluate_policy` decides from settings
(effective preferences), state (global pause, contact status, today's sends, the last
send) and the connection's capabilities. The draft contributes exactly two inputs, both
computed in code by WING-010: ``decision`` (anything but ``send_candidate`` stops) and
its content ``approval_reasons`` (each forces approval). Model text, rationale and
confidence never authorize anything.

Policy checks, in order (``docs/architecture.md`` step 5; the first stop wins):

1. ``decision != send_candidate``                      -> stop ``draft_not_sendable``
2. contact ``taken_over``                               -> stop ``taken_over``
3. connection ``send`` ``UNSUPPORTED``                  -> stop ``capability_unsupported``
   (``policy_blocked``)
4. effective mode ``manual``                            -> stop ``manual_mode``
   (the draft is stored and available on request only)
5. approval-type checks, all collected:
   ``assisted_mode`` (mode ``assisted``), ``capability_unverified`` (``send`` not
   ``VERIFIED``), ``outside_active_hours``, ``send_limit_reached``, the draft's content
   reasons, and ``user_rule`` (an ``approval_required_for`` entry matched, or an enabled
   ``require_approval`` rule); global pause or contact ``paused`` also forces approval
   (``ContactAutomationStatus``: "the agent may still queue drafts for approval").
   Any reason -> **approval queue**.
6. otherwise (autopilot, all clear)                     -> **dispatch** (``autopilot``)

``block_send`` rules stop like (3). The reply delay never blocks: it sets the message's
``send_after`` (uniform random within ``reply_delay_seconds``), and the dispatcher
holds the message until then. An inbound message that arrives while an earlier
automated reply is still delayed cancels the stale reply, so the conversation never
gets an answer to an old message.

Concurrency. ``evaluate_policy`` runs with the user row locked ``FOR UPDATE`` and the
contact row ``FOR SHARE``: decisions for one user serialize (the daily limit cannot be
overrun by parallel inbound events), and a pause or takeover (which writes those rows)
waits for an in-flight decision and then cancels what it produced, or the decision
waits for the takeover and then sees it.

Audit. Every outcome appends an ``agent_actions`` row: ``approval_requested`` (one
reason code, all reasons in ``details``), ``policy_blocked`` (``capability_unsupported``,
``user_rule``), or a ``send_queued`` from the dispatcher. ``details`` carry only IDs,
codes and counts, never message or draft text.
"""

import dataclasses
import logging
import random
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, time, timedelta
from enum import StrEnum
from typing import Any
from zoneinfo import ZoneInfo

from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.analyzer import ConversationAnalyzer
from app.ai.memory import MemoryService
from app.ai.providers.errors import LLMError
from app.ai.reply_generator import DraftResult, ReplyGenerator
from app.db.base import utc_now
from app.db.enums import (
    Actor,
    AgentActionType,
    ApprovalReason,
    ApprovalStatus,
    AutomationMode,
    CapabilityVerification,
    ContactAutomationStatus,
    DraftDecision,
    GlobalAutomationStatus,
    MessageOrigin,
)
from app.db.errors import NotFoundError
from app.db.models import Approval, Contact, Conversation, Draft, Message, User
from app.db.repositories import (
    AgentActionRepository,
    ApprovalRepository,
    AutomationRuleRepository,
    ConnectionRepository,
    ContactRepository,
    ConversationRepository,
    DraftRepository,
    MessageRepository,
    UserRepository,
)
from app.db.scope import UserScope
from app.services.dispatcher import (
    AUTOMATED_ORIGINS,
    PAUSE_CANCELS,
    Dispatcher,
    DispatchResult,
)
from app.services.preferences import ActiveHours, EffectivePreferences, get_effective_preferences

__all__ = [
    "CONTENT_REASONS",
    "DEFAULT_DAILY_SEND_LIMIT",
    "ApprovalNotPending",
    "SendCapabilityUnsupported",
    "AutomationController",
    "PipelineOutcome",
    "PipelineResult",
    "PolicyDecision",
    "PolicyInput",
    "PolicyOutcome",
    "StopReason",
    "draft_to_contract",
    "evaluate_policy",
    "within_active_hours",
]

logger = logging.getLogger(__name__)

# Server default for ``AutomationSettings.daily_send_limit: null`` (automated sends per
# user per local day).
DEFAULT_DAILY_SEND_LIMIT = 50
# How long an approval stays pending before it expires.
APPROVAL_TTL = timedelta(hours=24)
# Reasons WING-010 computes from the draft's content (D-013).
CONTENT_REASONS = frozenset(
    {
        ApprovalReason.DATE_OR_COMMITMENT,
        ApprovalReason.SENSITIVE_CONTENT,
        ApprovalReason.LOW_CONFIDENCE,
        ApprovalReason.FIRST_MESSAGE,
    }
)
_WEEKDAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")


# --------------------------------------------------------------------------- pure policy


class PolicyOutcome(StrEnum):
    DISPATCH = "dispatch"
    APPROVAL = "approval"
    STOP = "stop"


class StopReason(StrEnum):
    DRAFT_NOT_SENDABLE = "draft_not_sendable"
    TAKEN_OVER = "taken_over"
    CAPABILITY_UNSUPPORTED = "capability_unsupported"
    MANUAL_MODE = "manual_mode"
    USER_RULE = "user_rule"


@dataclass(frozen=True)
class PolicyInput:
    """Everything the policy may look at. No model text is in here on purpose."""

    draft_decision: DraftDecision
    content_reasons: tuple[ApprovalReason, ...]
    mode: AutomationMode
    global_status: GlobalAutomationStatus
    contact_status: ContactAutomationStatus
    send_capability: CapabilityVerification
    now: datetime
    active_hours: ActiveHours | None
    daily_limit: int
    sends_today: int
    approval_required_for: tuple[ApprovalReason, ...]
    rule_actions: tuple[str, ...] = ()


@dataclass(frozen=True)
class PolicyDecision:
    outcome: PolicyOutcome
    reasons: tuple[ApprovalReason, ...] = ()
    stop: StopReason | None = None
    checks: dict[str, Any] = field(default_factory=dict)

    @property
    def reason_code(self) -> str | None:
        if self.stop is not None:
            return self.stop.value
        return self.reasons[0].value if self.reasons else None


def within_active_hours(active: ActiveHours | None, now: datetime) -> bool:
    """``True`` when ``now`` falls in a window (``start`` inclusive, ``end`` exclusive;
    ``end < start`` wraps past midnight and belongs to the start day). ``None`` = always."""
    if active is None:
        return True
    local = now.astimezone(ZoneInfo(active.timezone))
    minutes = local.hour * 60 + local.minute
    today = _WEEKDAYS[local.weekday()]
    yesterday = _WEEKDAYS[(local.weekday() - 1) % 7]
    for window in active.windows:
        start = _minutes(window.start)
        end = _minutes(window.end)
        if start == end:
            if today in window.days:
                return True
            continue
        if start < end:
            if today in window.days and start <= minutes < end:
                return True
        else:
            if today in window.days and minutes >= start:
                return True
            if yesterday in window.days and minutes < end:
                return True
    return False


def _minutes(hhmm: str) -> int:
    hours, mins = hhmm.split(":")
    return int(hours) * 60 + int(mins)


def evaluate_policy(p: PolicyInput) -> PolicyDecision:
    """Decide dispatch, approval or stop. Pure: same input, same decision."""
    checks: dict[str, Any] = {
        "mode": p.mode.value,
        "send_capability": p.send_capability.value,
        "global_status": p.global_status.value,
        "contact_status": p.contact_status.value,
    }
    if p.draft_decision is not DraftDecision.SEND_CANDIDATE:
        return PolicyDecision(PolicyOutcome.STOP, stop=StopReason.DRAFT_NOT_SENDABLE, checks=checks)
    if p.contact_status is ContactAutomationStatus.TAKEN_OVER:
        return PolicyDecision(PolicyOutcome.STOP, stop=StopReason.TAKEN_OVER, checks=checks)
    if p.send_capability is CapabilityVerification.UNSUPPORTED:
        return PolicyDecision(
            PolicyOutcome.STOP, stop=StopReason.CAPABILITY_UNSUPPORTED, checks=checks
        )
    if "block_send" in p.rule_actions:
        return PolicyDecision(PolicyOutcome.STOP, stop=StopReason.USER_RULE, checks=checks)
    if p.mode is AutomationMode.MANUAL:
        return PolicyDecision(PolicyOutcome.STOP, stop=StopReason.MANUAL_MODE, checks=checks)

    reasons: list[ApprovalReason] = []
    if p.mode is AutomationMode.ASSISTED:
        reasons.append(ApprovalReason.ASSISTED_MODE)
    if p.send_capability is not CapabilityVerification.VERIFIED:
        reasons.append(ApprovalReason.CAPABILITY_UNVERIFIED)
    in_hours = within_active_hours(p.active_hours, p.now)
    checks["within_active_hours"] = in_hours
    if not in_hours:
        reasons.append(ApprovalReason.OUTSIDE_ACTIVE_HOURS)
    checks["sends_today"] = p.sends_today
    checks["daily_limit"] = p.daily_limit
    if p.sends_today >= p.daily_limit:
        reasons.append(ApprovalReason.SEND_LIMIT_REACHED)
    content = [r for r in p.content_reasons if r in CONTENT_REASONS]
    reasons += content
    # ``approval_required_for``: listed situations always go to the queue, even in
    # autopilot. Every reason above already does; a match is marked ``user_rule`` so the
    # queue shows the user's own setting asked for it.
    matched = sorted(r.value for r in set(reasons) & set(p.approval_required_for))
    checks["approval_required_for_matched"] = matched
    if matched:
        reasons.append(ApprovalReason.USER_RULE)
    if "require_approval" in p.rule_actions:
        reasons.append(ApprovalReason.USER_RULE)
    paused = (
        p.global_status is GlobalAutomationStatus.PAUSED
        or p.contact_status is ContactAutomationStatus.PAUSED
    )
    checks["paused"] = paused
    if paused and not reasons:
        # No automated send while paused; the draft may still be queued for approval.
        # The contract has no ``paused`` ApprovalReason, so the user's pause is reported
        # as ``user_rule`` (``checks.paused`` says why). Reported as a contract gap.
        reasons.append(ApprovalReason.USER_RULE)
    unique = tuple(dict.fromkeys(reasons))
    if unique:
        return PolicyDecision(PolicyOutcome.APPROVAL, reasons=unique, checks=checks)
    return PolicyDecision(PolicyOutcome.DISPATCH, checks=checks)


def local_day_start(now: datetime, active: ActiveHours | None) -> datetime:
    """Start of the user's local day (the ``active_hours`` time zone, else UTC)."""
    zone = ZoneInfo(active.timezone) if active is not None else UTC
    local = now.astimezone(zone)
    start = datetime.combine(local.date(), time(0), tzinfo=zone)
    return start.astimezone(UTC)


# --------------------------------------------------------------------------- serialization


def _ts(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def draft_to_contract(draft: Draft) -> dict[str, Any]:
    """A stored draft as the contract ``Draft``."""
    return {
        "id": str(draft.id),
        "conversation_id": str(draft.conversation_id),
        "decision": draft.decision.value,
        "text": draft.text,
        "question_for_user": draft.question_for_user,
        "rationale": draft.rationale,
        "confidence": draft.confidence,
        "requires_approval": draft.requires_approval,
        "approval_reasons": [r.value for r in draft.approval_reasons],
        "intent_mode": draft.intent_mode.value,
        "grounding": {
            "memory_ids": [str(m) for m in draft.grounding_memory_ids],
            "message_ids": [str(m) for m in draft.grounding_message_ids],
        },
        "created_at": _ts(draft.created_at),
    }


# --------------------------------------------------------------------------- results


class ApprovalNotPending(Exception):  # noqa: N818 - maps to the contract error code
    """Only a ``pending`` approval can be approved or rejected."""


class SendCapabilityUnsupported(Exception):  # noqa: N818 - maps to the contract error code
    """The conversation's connection cannot send at all (``capability_unsupported``).
    Raised before anything is queued or decided: no send, no state change."""


class PipelineOutcome(StrEnum):
    DUPLICATE = "duplicate"  # inbound message already handled: nothing done
    TAKEN_OVER = "taken_over"  # agent stays out entirely (no draft)
    MANUAL = "manual"  # draft stored, available on request only
    NO_SEND = "no_send"  # draft decided not to reply (no_send / ask_user)
    APPROVAL = "approval"
    DISPATCHED = "dispatched"
    BLOCKED = "blocked"  # capability unsupported / block rule


@dataclass(frozen=True)
class PipelineResult:
    outcome: PipelineOutcome
    message_id: uuid.UUID | None = None
    draft_id: uuid.UUID | None = None
    approval_id: uuid.UUID | None = None
    outbound_message_id: uuid.UUID | None = None
    dispatch: DispatchResult | None = None
    decision: PolicyDecision | None = None


# --------------------------------------------------------------------------- controller


class AutomationController:
    """Orchestrates the agent for one user at a time. Never commits; never sends itself.

    ``generator`` (WING-010) and ``analyzer`` (WING-009) may be ``None`` for routes that
    only decide (approve, reject, pause); ``memory`` needs a gateway for extraction.
    """

    def __init__(
        self,
        dispatcher: Dispatcher,
        *,
        generator: ReplyGenerator | None = None,
        analyzer: ConversationAnalyzer | None = None,
        memory: MemoryService | None = None,
        now: Callable[[], datetime] = utc_now,
        rng: random.Random | None = None,
        default_daily_limit: int = DEFAULT_DAILY_SEND_LIMIT,
        approval_ttl: timedelta = APPROVAL_TTL,
    ) -> None:
        self.dispatcher = dispatcher
        self.generator = generator
        self.analyzer = analyzer
        self.memory = memory
        self._now = now
        self._rng = rng or random.Random()  # noqa: S311 - jitter, not security
        self.default_daily_limit = default_daily_limit
        self.approval_ttl = approval_ttl

    # ------------------------------------------------------------------ pipeline

    async def handle_inbound_message(
        self,
        session: AsyncSession,
        scope: UserScope,
        message_id: uuid.UUID,
    ) -> PipelineResult:
        """Steps 2-7 for one newly ingested inbound message.

        memory -> analysis -> draft -> policy -> approval or dispatch -> audit. Model
        failures degrade to no send (WING-008/009/010 are each fail-safe; any other
        model error here stops the pipeline without a draft). Running it twice for the
        same message is a no-op the second time (one draft per inbound message).
        """
        message = await MessageRepository(session, scope).require(message_id)
        conversation = await ConversationRepository(session, scope).require(message.conversation_id)
        drafts = DraftRepository(session, scope)
        if await drafts.for_trigger(message_id) is not None:
            return PipelineResult(PipelineOutcome.DUPLICATE, message_id=message_id)
        contact = await ContactRepository(session, scope).require(conversation.contact_id)
        if contact.automation_status is ContactAutomationStatus.TAKEN_OVER:
            # Takeover: no agent-initiated drafts or approvals until resumed.
            return PipelineResult(PipelineOutcome.TAKEN_OVER, message_id=message_id)
        if self.memory is not None and self.memory.gateway is not None:
            try:
                async with session.begin_nested():
                    await self.memory.extract_from_messages(
                        session, scope, conversation.id, message_ids=[message_id]
                    )
            except LLMError as exc:
                logger.warning(
                    "pipeline memory skipped user=%s message=%s error_class=%s",
                    scope.user_id,
                    message_id,
                    exc.error_class,
                )
        if self.analyzer is not None:
            await self.analyzer.analyze_conversation(session, scope, conversation.id)
        if self.generator is None:
            return PipelineResult(PipelineOutcome.MANUAL, message_id=message_id)
        try:
            result = await self.generator.generate_draft(session, scope, conversation.id)
        except LLMError as exc:  # pragma: no cover - the generator already degrades
            logger.warning(
                "pipeline draft failed user=%s error_class=%s", scope.user_id, exc.error_class
            )
            return PipelineResult(PipelineOutcome.NO_SEND, message_id=message_id)
        draft = await self._store_draft(session, scope, result, trigger_message_id=message_id)
        if draft is None:
            return PipelineResult(PipelineOutcome.DUPLICATE, message_id=message_id)
        return await self.decide(session, scope, draft, trigger_message_id=message_id)

    async def _store_draft(
        self,
        session: AsyncSession,
        scope: UserScope,
        result: DraftResult,
        *,
        trigger_message_id: uuid.UUID | None,
    ) -> Draft | None:
        return await DraftRepository(session, scope).create(
            id=result.id,
            conversation_id=result.conversation_id,
            contact_id=result.contact_id,
            decision=DraftDecision(result.decision.value),
            text=result.text,
            question_for_user=result.question_for_user,
            rationale=result.rationale,
            confidence=result.confidence,
            approval_reasons=result.approval_reasons,
            intent_mode=result.intent_mode,
            grounding_memory_ids=result.grounding.memory_ids,
            grounding_message_ids=result.grounding.message_ids,
            reason=result.reason.value,
            created_at=result.created_at,
            trigger_message_id=trigger_message_id,
        )

    async def _supersede_stale(
        self, session: AsyncSession, scope: UserScope, conversation: Conversation
    ) -> None:
        now = self._now()
        approvals = ApprovalRepository(session, scope)
        stale = await approvals.cancel_pending(at=now, conversation_id=conversation.id)
        audit = AgentActionRepository(session, scope)
        for approval in stale:
            await audit.record(
                type=AgentActionType.APPROVAL_CANCELED,
                actor=Actor.AGENT,
                conversation_id=conversation.id,
                contact_id=conversation.contact_id,
                approval_id=approval.id,
                reason_code="superseded",
                occurred_at=now,
                details={"draft_id": str(approval.draft_id)},
            )
        # A delayed autopilot reply to an older message is now stale.
        for message in await MessageRepository(session, scope).queued_automated(
            contact_id=conversation.contact_id, origins=(MessageOrigin.AUTOPILOT,)
        ):
            if message.conversation_id != conversation.id:
                continue
            await self.dispatcher.cancel_queued_message(
                session, scope, message.id, reason_code="superseded", actor=Actor.AGENT
            )

    # ------------------------------------------------------------------ decision

    async def _policy_input(
        self,
        session: AsyncSession,
        scope: UserScope,
        draft: Draft,
        conversation: Conversation,
        user: User,
        contact: Contact,
    ) -> tuple[PolicyInput, EffectivePreferences]:
        prefs = await get_effective_preferences(session, scope, conversation.contact_id)
        connection = await ConnectionRepository(session, scope).require(conversation.connection_id)
        now = self._now()
        automation = prefs.automation
        limit = (
            automation.daily_send_limit
            if automation.daily_send_limit is not None
            else self.default_daily_limit
        )
        sends_today = await MessageRepository(session, scope).count_automated_since(
            local_day_start(now, automation.active_hours)
        )
        rules = await AutomationRuleRepository(session, scope).applicable(contact.id)
        return (
            PolicyInput(
                draft_decision=draft.decision,
                content_reasons=tuple(draft.approval_reasons),
                mode=automation.mode,
                global_status=user.global_automation_status,
                contact_status=contact.automation_status,
                send_capability=connection.send_capability,
                now=now,
                active_hours=automation.active_hours,
                daily_limit=limit,
                sends_today=sends_today,
                approval_required_for=tuple(automation.approval_required_for),
                rule_actions=tuple(r.action for r in rules),
            ),
            prefs,
        )

    async def decide(
        self,
        session: AsyncSession,
        scope: UserScope,
        draft: Draft,
        *,
        trigger_message_id: uuid.UUID | None = None,
    ) -> PipelineResult:
        """Apply the policy to a stored draft: stop, queue for approval, or dispatch."""
        conversation = await ConversationRepository(session, scope).require(draft.conversation_id)
        user = await UserRepository(session).lock(scope)
        contact = await ContactRepository(session, scope).lock_shared(conversation.contact_id)
        if user is None or contact is None:  # pragma: no cover - deleted concurrently
            raise NotFoundError("user or contact not found")
        if trigger_message_id is not None:
            # A newer inbound message supersedes what the agent proposed for older ones.
            await self._supersede_stale(session, scope, conversation)
        policy, prefs = await self._policy_input(session, scope, draft, conversation, user, contact)
        decision = evaluate_policy(policy)
        base = PipelineResult(
            PipelineOutcome.NO_SEND,
            message_id=trigger_message_id,
            draft_id=draft.id,
            decision=decision,
        )
        audit = AgentActionRepository(session, scope)
        common = {
            "conversation_id": conversation.id,
            "contact_id": conversation.contact_id,
            "message_id": trigger_message_id,
        }
        details = {"draft_id": str(draft.id), "checks": decision.checks}

        if decision.outcome is PolicyOutcome.STOP:
            stop = decision.stop
            if stop in (StopReason.CAPABILITY_UNSUPPORTED, StopReason.USER_RULE):
                await audit.record(
                    type=AgentActionType.POLICY_BLOCKED,
                    actor=Actor.AGENT,
                    reason_code=stop.value if stop else None,
                    occurred_at=policy.now,
                    details=details,
                    **common,
                )
                return _replace(base, PipelineOutcome.BLOCKED)
            if stop is StopReason.MANUAL_MODE:
                return _replace(base, PipelineOutcome.MANUAL)
            if stop is StopReason.TAKEN_OVER:
                return _replace(base, PipelineOutcome.TAKEN_OVER)
            return base

        if decision.outcome is PolicyOutcome.APPROVAL:
            approval = await ApprovalRepository(session, scope).create(
                conversation_id=conversation.id,
                contact_id=conversation.contact_id,
                draft_id=draft.id,
                reasons=decision.reasons,
                expires_at=policy.now + self.approval_ttl,
                created_at=policy.now,
            )
            await audit.record(
                type=AgentActionType.APPROVAL_REQUESTED,
                actor=Actor.AGENT,
                approval_id=approval.id,
                reason_code=decision.reason_code,
                occurred_at=policy.now,
                details=details | {"reasons": [r.value for r in decision.reasons]},
                **common,
            )
            return _replace(base, PipelineOutcome.APPROVAL, approval_id=approval.id)

        if draft.text is None:  # pragma: no cover - DB CHECK: send_candidate has text
            return base
        delay = prefs.automation.reply_delay_seconds
        send_after = None
        if delay is not None and delay.max > 0:
            send_after = policy.now + timedelta(seconds=self._rng.randint(delay.min, delay.max))
        message = await self.dispatcher.queue(
            session,
            scope,
            conversation=conversation,
            origin=MessageOrigin.AUTOPILOT,
            text=draft.text,
            actor=Actor.AGENT,
            send_after=send_after,
        )
        result = await self.dispatcher.dispatch(session, scope, message.id)
        return _replace(
            base,
            PipelineOutcome.DISPATCHED,
            outbound_message_id=message.id,
            dispatch=result,
        )

    # ------------------------------------------------------------------ approvals

    async def approve(
        self,
        session: AsyncSession,
        scope: UserScope,
        approval_id: uuid.UUID,
        *,
        edited_text: str | None,
    ) -> tuple[Approval, Message]:
        """User approval (optionally edited): queue exactly one send (``approved_draft``).

        The caller then calls ``dispatcher.dispatch(message.id)``.

        Raises ``NotFoundError`` (another user's / missing) or :class:`ApprovalNotPending`.
        Lock order: user -> contact -> approval, like pause/takeover, so a concurrent
        takeover either wins (approval canceled, 409 here) or waits for this commit and
        then cancels the queued message.
        """
        approvals = ApprovalRepository(session, scope)
        peek = await approvals.require(approval_id)
        await UserRepository(session).lock(scope, shared=True)
        await ContactRepository(session, scope).lock_shared(peek.contact_id)
        approval = await approvals.lock(approval_id)
        if approval is None:  # pragma: no cover - deleted concurrently
            raise NotFoundError("approval not found")
        now = self._now()
        if approval.status is ApprovalStatus.PENDING and _expired(approval, now):
            await self._expire(session, scope, approval, now)
        if approval.status is not ApprovalStatus.PENDING:
            raise ApprovalNotPending(approval.status.value)
        draft = await DraftRepository(session, scope).require(approval.draft_id)
        conversation = await ConversationRepository(session, scope).require(
            approval.conversation_id
        )
        await self._require_send_capability(session, scope, conversation)
        final_text = edited_text if edited_text is not None else draft.text
        if final_text is None:  # pragma: no cover - approvals only exist for send_candidates
            raise ApprovalNotPending("draft has no text")
        approval = await approvals.decide(
            approval.id,
            status=ApprovalStatus.APPROVED,
            final_text=final_text,
            decided_at=now,
        )
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.APPROVAL_APPROVED,
            actor=Actor.USER,
            conversation_id=conversation.id,
            contact_id=conversation.contact_id,
            approval_id=approval.id,
            occurred_at=now,
            details={
                "edited": edited_text is not None and edited_text != draft.text,
                "draft_id": str(draft.id),
            },
        )
        message = await self.dispatcher.queue(
            session,
            scope,
            conversation=conversation,
            origin=MessageOrigin.APPROVED_DRAFT,
            text=final_text,
            actor=Actor.USER,
            approval_id=approval.id,
        )
        # The caller dispatches (after serializing the queued message for the response).
        return approval, message

    async def reject(
        self,
        session: AsyncSession,
        scope: UserScope,
        approval_id: uuid.UUID,
        *,
        reason: str | None,
    ) -> Approval:
        approvals = ApprovalRepository(session, scope)
        peek = await approvals.require(approval_id)
        await ContactRepository(session, scope).lock_shared(peek.contact_id)
        approval = await approvals.lock(approval_id)
        if approval is None:  # pragma: no cover
            raise NotFoundError("approval not found")
        now = self._now()
        if approval.status is ApprovalStatus.PENDING and _expired(approval, now):
            await self._expire(session, scope, approval, now)
        if approval.status is not ApprovalStatus.PENDING:
            raise ApprovalNotPending(approval.status.value)
        approval = await approvals.decide(
            approval.id,
            status=ApprovalStatus.REJECTED,
            rejection_reason=reason,
            decided_at=now,
        )
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.APPROVAL_REJECTED,
            actor=Actor.USER,
            conversation_id=approval.conversation_id,
            contact_id=approval.contact_id,
            approval_id=approval.id,
            occurred_at=now,
            details={"draft_id": str(approval.draft_id), "has_reason": reason is not None},
        )
        return approval

    async def _expire(
        self, session: AsyncSession, scope: UserScope, approval: Approval, now: datetime
    ) -> None:
        approval.status = ApprovalStatus.EXPIRED
        await ApprovalRepository(session, scope).decide(
            approval.id, status=ApprovalStatus.EXPIRED, decided_at=now
        )
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.APPROVAL_EXPIRED,
            actor=Actor.SYSTEM,
            conversation_id=approval.conversation_id,
            contact_id=approval.contact_id,
            approval_id=approval.id,
            occurred_at=now,
            details={},
        )

    async def expire_approvals(self, session: AsyncSession, scope: UserScope) -> int:
        """Periodic job: pending approvals past ``expires_at`` become ``expired``."""
        now = self._now()
        expired = await ApprovalRepository(session, scope).expire_due(now=now)
        audit = AgentActionRepository(session, scope)
        for approval in expired:
            await audit.record(
                type=AgentActionType.APPROVAL_EXPIRED,
                actor=Actor.SYSTEM,
                conversation_id=approval.conversation_id,
                contact_id=approval.contact_id,
                approval_id=approval.id,
                occurred_at=now,
                details={},
            )
        return len(expired)

    # ------------------------------------------------------------------ pause / takeover

    async def pause_all(self, session: AsyncSession, scope: UserScope) -> tuple[User, int]:
        """Global pause, effective before returning: queued automated messages canceled."""
        users = UserRepository(session)
        user = await users.lock(scope)
        if user is None:  # pragma: no cover
            raise NotFoundError("user not found")
        now = self._now()
        changed = user.global_automation_status is not GlobalAutomationStatus.PAUSED
        if changed:
            user = await users.set_global_automation_status(
                scope, GlobalAutomationStatus.PAUSED, at=now
            )
        canceled = await self.dispatcher.cancel_queued_automated(
            session,
            scope,
            contact_id=None,
            reason_code="automation_paused",
            origins=PAUSE_CANCELS,
        )
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.AUTOMATION_PAUSED,
            actor=Actor.USER,
            occurred_at=now,
            details={"scope": "global", "changed": changed, "canceled_messages": canceled},
        )
        return user, canceled

    async def resume_all(self, session: AsyncSession, scope: UserScope) -> User:
        users = UserRepository(session)
        user = await users.lock(scope)
        if user is None:  # pragma: no cover
            raise NotFoundError("user not found")
        now = self._now()
        changed = user.global_automation_status is not GlobalAutomationStatus.ACTIVE
        if changed:
            user = await users.set_global_automation_status(
                scope, GlobalAutomationStatus.ACTIVE, at=now
            )
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.AUTOMATION_RESUMED,
            actor=Actor.USER,
            occurred_at=now,
            details={"scope": "global", "changed": changed},
        )
        return user

    async def set_contact_status(
        self,
        session: AsyncSession,
        scope: UserScope,
        contact_id: uuid.UUID,
        status: ContactAutomationStatus,
    ) -> tuple[Contact, int, int]:
        """Pause, resume or take over one contact, effective before returning.

        Returns ``(contact, canceled_approvals, canceled_messages)``. Pause and takeover
        cancel queued automated messages (and their unclaimed device instructions);
        takeover also cancels every pending approval. Resume restores nothing.
        """
        contacts = ContactRepository(session, scope)
        await contacts.require(contact_id)
        # FOR UPDATE on the user: serializes with policy decisions and device claims,
        # which hold it while they read the contact's status.
        await UserRepository(session).lock(scope)
        contact = await contacts.lock_for_update(contact_id)
        if contact is None:  # pragma: no cover
            raise NotFoundError("contact not found")
        now = self._now()
        changed = contact.automation_status is not status
        if changed:
            contact = await contacts.set_automation_status(contact_id, status, at=now)
        canceled_approvals = 0
        canceled_messages = 0
        audit = AgentActionRepository(session, scope)
        if status is ContactAutomationStatus.TAKEN_OVER:
            for approval in await ApprovalRepository(session, scope).cancel_pending(
                at=now, contact_id=contact_id
            ):
                canceled_approvals += 1
                await audit.record(
                    type=AgentActionType.APPROVAL_CANCELED,
                    actor=Actor.USER,
                    conversation_id=approval.conversation_id,
                    contact_id=contact_id,
                    approval_id=approval.id,
                    reason_code="automation_takeover",
                    occurred_at=now,
                    details={"draft_id": str(approval.draft_id)},
                )
        if status is not ContactAutomationStatus.ACTIVE:
            canceled_messages = await self.dispatcher.cancel_queued_automated(
                session,
                scope,
                contact_id=contact_id,
                reason_code=(
                    "automation_takeover"
                    if status is ContactAutomationStatus.TAKEN_OVER
                    else "automation_paused"
                ),
                origins=(
                    AUTOMATED_ORIGINS
                    if status is ContactAutomationStatus.TAKEN_OVER
                    else PAUSE_CANCELS
                ),
            )
        action = {
            ContactAutomationStatus.ACTIVE: AgentActionType.AUTOMATION_RESUMED,
            ContactAutomationStatus.PAUSED: AgentActionType.AUTOMATION_PAUSED,
            ContactAutomationStatus.TAKEN_OVER: AgentActionType.AUTOMATION_TAKEOVER,
        }[status]
        await audit.record(
            type=action,
            actor=Actor.USER,
            contact_id=contact_id,
            occurred_at=now,
            details={
                "scope": "contact",
                "changed": changed,
                "canceled_approvals": canceled_approvals,
                "canceled_messages": canceled_messages,
            },
        )
        return contact, canceled_approvals, canceled_messages

    # ------------------------------------------------------------------ manual send

    async def send_manual(
        self, session: AsyncSession, scope: UserScope, conversation_id: uuid.UUID, text: str
    ) -> Message:
        """``sendMessage``: the user's own text, through the dispatcher. No mode checks;
        the dispatcher still checks the ``send`` capability (``UNSUPPORTED`` cancels)."""
        conversation = await ConversationRepository(session, scope).require(conversation_id)
        await self._require_send_capability(session, scope, conversation)
        # The caller dispatches (after serializing the queued message for the response).
        return await self.dispatcher.queue(
            session,
            scope,
            conversation=conversation,
            origin=MessageOrigin.USER_MANUAL,
            text=text,
            actor=Actor.USER,
        )

    async def _require_send_capability(
        self, session: AsyncSession, scope: UserScope, conversation: Conversation
    ) -> None:
        connection = await ConnectionRepository(session, scope).require(conversation.connection_id)
        if connection.send_capability is CapabilityVerification.UNSUPPORTED:
            raise SendCapabilityUnsupported(connection.platform)


def _expired(approval: Approval, now: datetime) -> bool:
    return approval.expires_at is not None and approval.expires_at <= now


def _replace(base: PipelineResult, outcome: PipelineOutcome, **changes: Any) -> PipelineResult:
    return dataclasses.replace(base, outcome=outcome, **changes)
