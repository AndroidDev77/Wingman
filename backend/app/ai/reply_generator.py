"""Personalized, grounded reply drafts (WING-010).

Public API for WING-011 and the drafts endpoint::

    generator = ReplyGenerator(gateway, memory=MemoryService())
    draft = await generator.generate_draft(session, scope, conversation_id, guidance=None)
    draft.decision            # send_candidate | no_send | ask_user
    draft.requires_approval   # content-based flags only (see below)
    draft.to_contract()       # the contract ``Draft`` object, JSON-ready

    # or, with the gateway built from settings:
    draft = await generate_draft(session, scope, conversation_id, guidance=None)

Flow
    1. Load the conversation in the caller's scope (another user's ID: ``NotFoundError``).
    2. Compose the context: effective preferences for the contact (intent and style,
       WING-007), the stored analysis and stage (WING-009), a bounded window of recent
       messages and bounded memory (WING-008 ``MemoryService.get_context``).
    3. Short-circuit without calling the model when the answer is already clear:
       ``paused`` stage or ``stop`` next action gives ``no_send``; nothing to reply to
       and no guidance gives ``ask_user``.
    4. Ask the model, through the LLM gateway, for a strict :class:`ReplyOutput`.
    5. Check the output in code (the prompt asks; these checks decide):
       * every cited ref must be one the prompt handed out, else ``no_send``
         (``invalid_grounding``), as WING-008/009 do;
       * statements of the user's location, profession, relationship status or
         availability must be grounded in the user's own messages, the guidance or the
         user's goal text, else ``ask_user`` (``fabricated_personal_fact``);
       * a proposed or confirming date/time/place sets ``date_or_commitment``; nothing
         is ever marked confirmed;
       * escalation must cite matching interest in the contact's own messages, and
         explicit or coercive content is refused (``no_send``);
       * style caps (emoji, punctuation, length) are applied or flagged.
    6. Append a ``draft_generated`` audit entry with redacted details (no text).

Model failure (timeout, transport error, malformed or schema-invalid output) gives a
typed ``no_send`` with ``reason=model_unavailable`` and the error class: never a
fabricated fallback reply.

Approval flags
    ``approval_reasons`` holds the content-based reasons only (``date_or_commitment``,
    ``sensitive_content``, ``low_confidence``, ``first_message``). Policy reasons
    (``assisted_mode``, ``outside_active_hours``, ``capability_unverified``,
    ``send_limit_reached``, ``user_rule``) belong to the automation controller.

Advisory only
    A draft is a suggestion and grants no authorization. This module never imports or
    calls a connector, the dispatcher or the automation controller (enforced by a test
    in ``tests/ai/replies``), and it never sends.
"""

import logging
import re
import uuid
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.llm_gateway import LLMGateway, build_gateway
from app.ai.memory import ContextFact, ContextLimits, MemoryContext, MemoryService
from app.ai.prompts import (
    BREVITY_MAX_CHARS,
    EMOJI_MAX,
    GUIDANCE_REF,
    INTENT_REF,
    AnalysisView,
    MemoryLine,
    PromptMessage,
    ReplyPromptContext,
    build_reply_messages,
)
from app.ai.providers.errors import LLMError
from app.db.base import utc_now
from app.db.enums import (
    Actor,
    AgentActionType,
    ApprovalReason,
    ConversationStage,
    IntentMode,
    MessageDirection,
    Punctuation,
    ResponsePace,
)
from app.db.ids import uuid7
from app.db.models import Message
from app.db.repositories import AgentActionRepository, ConversationRepository
from app.db.scope import UserScope
from app.services.preferences import EffectivePreferences, get_effective_preferences

__all__ = [
    "GENERATE_OPERATION",
    "ClaimKind",
    "Commitment",
    "DraftDecision",
    "DraftLimits",
    "DraftReason",
    "DraftResult",
    "Grounding",
    "GuardrailViolation",
    "ReplyGenerator",
    "ReplyOutput",
    "UserClaim",
    "detect_commitment",
    "detect_personal_claims",
    "detect_unsafe_content",
    "generate_draft",
]

logger = logging.getLogger(__name__)

GENERATE_OPERATION = "reply.generate"
REF_PATTERN = r"^(m[1-9][0-9]{0,3}|f[1-9][0-9]{0,3}|s1|guidance|intent)$"
_REF_RE = re.compile(REF_PATTERN)
MAX_TEXT_CHARS = 4000
MAX_QUESTION_CHARS = 500
MAX_RATIONALE_CHARS = 500


# --------------------------------------------------------------------------- vocabulary


class DraftDecision(StrEnum):
    """``DraftDecision`` in the contract (value for value)."""

    SEND_CANDIDATE = "send_candidate"
    NO_SEND = "no_send"
    ASK_USER = "ask_user"


class ClaimKind(StrEnum):
    """Personal facts about the USER that must never be invented."""

    LOCATION = "location"
    PROFESSION = "profession"
    RELATIONSHIP_STATUS = "relationship_status"
    AVAILABILITY = "availability"


class Commitment(StrEnum):
    NONE = "none"
    PROPOSAL = "proposal"
    CONFIRMATION = "confirmation"


class DraftReason(StrEnum):
    """Why the draft has its decision. Stable codes, safe to log and audit."""

    MODEL_DECISION = "model_decision"
    CONVERSATION_PAUSED = "conversation_paused"
    ANALYSIS_STOP = "analysis_stop"
    NOTHING_TO_REPLY_TO = "nothing_to_reply_to"
    MODEL_UNAVAILABLE = "model_unavailable"
    INVALID_GROUNDING = "invalid_grounding"
    FABRICATED_PERSONAL_FACT = "fabricated_personal_fact"
    UNSUPPORTED_ESCALATION = "unsupported_escalation"
    UNSAFE_CONTENT = "unsafe_content"


# --------------------------------------------------------------------------- model output


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


def _check_refs(refs: Iterable[str]) -> None:
    for ref in refs:
        if not _REF_RE.fullmatch(ref):
            raise ValueError("refs must look like m1, f1, s1, guidance or intent")


class UserClaim(_Strict):
    """A statement in the draft about the user, and where it comes from."""

    kind: ClaimKind
    refs: list[str] = Field(
        max_length=10,
        description="Refs supporting it: the user's own messages (m#), guidance or intent.",
    )

    @model_validator(mode="after")
    def _refs_ok(self) -> "UserClaim":
        _check_refs(self.refs)
        return self


class ReplyOutput(_Strict):
    """What the model must return. Every field is required (no silent defaults)."""

    decision: DraftDecision
    text: str | None = Field(max_length=MAX_TEXT_CHARS)
    question_for_user: str | None = Field(max_length=MAX_QUESTION_CHARS)
    rationale: str = Field(min_length=1, max_length=MAX_RATIONALE_CHARS)
    confidence: float = Field(ge=0, le=1)
    message_refs: list[str] = Field(max_length=40)
    memory_refs: list[str] = Field(max_length=40)
    user_claims: list[UserClaim] = Field(max_length=10)
    commitment: Commitment
    escalation: bool
    interest_evidence_refs: list[str] = Field(max_length=20)

    @model_validator(mode="after")
    def _shape(self) -> "ReplyOutput":
        _check_refs(self.message_refs)
        _check_refs(self.memory_refs)
        _check_refs(self.interest_evidence_refs)
        if self.decision is DraftDecision.SEND_CANDIDATE:
            if self.text is None or not self.text.strip():
                raise ValueError("send_candidate needs non-empty text")
            if self.question_for_user is not None:
                raise ValueError("send_candidate must not set question_for_user")
        elif self.decision is DraftDecision.ASK_USER:
            if self.text is not None:
                raise ValueError("ask_user must not set text")
            if self.question_for_user is None or not self.question_for_user.strip():
                raise ValueError("ask_user needs question_for_user")
        else:
            if self.text is not None or self.question_for_user is not None:
                raise ValueError("no_send must not set text or question_for_user")
        return self

    def all_refs(self) -> list[str]:
        out = [*self.message_refs, *self.memory_refs, *self.interest_evidence_refs]
        for claim in self.user_claims:
            out += claim.refs
        return out


# --------------------------------------------------------------------------- result


class Grounding(_Strict):
    memory_ids: tuple[uuid.UUID, ...]
    message_ids: tuple[uuid.UUID, ...]


class GuardrailViolation(_Strict):
    """A check that failed or flagged. ``code`` is a stable, loggable string."""

    code: str
    detail: str


class DraftResult(_Strict):
    """A draft suggestion. ``to_contract()`` is the contract ``Draft``; the rest explains it.

    Nothing here authorizes a send: WING-011 decides from settings and state.
    """

    # Contract fields.
    id: uuid.UUID
    conversation_id: uuid.UUID
    decision: DraftDecision
    text: str | None
    question_for_user: str | None
    rationale: str
    confidence: float = Field(ge=0, le=1)
    requires_approval: bool
    approval_reasons: tuple[ApprovalReason, ...]
    intent_mode: IntentMode
    grounding: Grounding
    created_at: datetime
    # Explanation (not part of the contract object).
    contact_id: uuid.UUID
    reason: DraftReason
    commitment: Commitment
    violations: tuple[GuardrailViolation, ...]
    # Error class of the model failure behind a ``model_unavailable`` no_send.
    model_error: str | None
    # From ``StyleProfile.response_pace`` within ``AutomationSettings.reply_delay_seconds``;
    # ``None`` when the user set neither (no pace is invented).
    suggested_delay_seconds: int | None

    @model_validator(mode="after")
    def _shape(self) -> "DraftResult":
        if (self.decision is DraftDecision.SEND_CANDIDATE) != (self.text is not None):
            raise ValueError("text is set exactly when decision is send_candidate")
        if self.decision is DraftDecision.ASK_USER and not self.question_for_user:
            raise ValueError("ask_user needs question_for_user")
        if self.requires_approval != bool(self.approval_reasons):
            raise ValueError("requires_approval must match approval_reasons")
        return self

    def to_contract(self) -> dict[str, Any]:
        """JSON-ready ``Draft`` exactly as in ``contracts/openapi.yaml``."""
        return {
            "id": str(self.id),
            "conversation_id": str(self.conversation_id),
            "decision": self.decision.value,
            "text": self.text,
            "question_for_user": self.question_for_user,
            "rationale": self.rationale,
            "confidence": self.confidence,
            "requires_approval": self.requires_approval,
            "approval_reasons": [r.value for r in self.approval_reasons],
            "intent_mode": self.intent_mode.value,
            "grounding": {
                "memory_ids": [str(m) for m in self.grounding.memory_ids],
                "message_ids": [str(m) for m in self.grounding.message_ids],
            },
            "created_at": _timestamp(self.created_at),
        }


def _timestamp(value: datetime) -> str:
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


@dataclass(frozen=True)
class DraftLimits:
    # Latest messages shown to the model (the bounded window).
    max_messages: int = 20
    max_message_chars: int = 600
    max_query_chars: int = 500
    max_guidance_chars: int = 500
    memory: ContextLimits = ContextLimits(max_facts=12, max_chars=1500, max_summary_chars=600)
    # Below this model confidence a send_candidate gets ``low_confidence``.
    low_confidence: float = 0.6

    def __post_init__(self) -> None:
        if not 1 <= self.max_messages <= 200:
            raise ValueError("max_messages must be between 1 and 200")
        if not 0 <= self.low_confidence <= 1:
            raise ValueError("low_confidence must be between 0 and 1")


# --------------------------------------------------------------------------- detectors
#
# Deterministic, conservative pattern checks run on the draft text after generation.
# They catch first-person statements the model did not declare; a false positive costs
# an ``ask_user`` or an approval, never a send.

_CLAIM_PATTERNS: dict[ClaimKind, tuple[re.Pattern[str], ...]] = {
    ClaimKind.LOCATION: (
        re.compile(
            r"\b(?:i live|i'm living|im living|i am living|i'm based|im based|i am based|"
            r"i'm from|im from|i am from|i grew up in|i moved to|i just moved|"
            r"my (?:place|apartment|flat|house|neighbou?rhood) (?:in|is in|is near|near))\b",
            re.IGNORECASE,
        ),
        re.compile(r"\b(?i:i'm|im|i am) (?i:in|near|around|over in|out in) [A-Z][a-z]+"),
    ),
    ClaimKind.PROFESSION: (
        re.compile(
            r"\b(?:i work(?:ed)? (?:as|at|in|for|on)|i'm working (?:as|at)|my job|my boss|"
            r"my (?:office|shift|coworkers?|colleagues?|clients?|patients?|students?)|"
            r"i'm at work|im at work|i am at work|i'm off work|my (?:career|company|startup))\b",
            re.IGNORECASE,
        ),
        re.compile(
            r"\b(?:i'm|im|i am) an? (?:\w+ )?(?:nurse|engineer|teacher|doctor|lawyer|"
            r"developer|designer|programmer|accountant|chef|cook|student|manager|consultant|"
            r"architect|artist|writer|pilot|firefighter|paramedic|scientist|researcher|"
            r"therapist|dentist|surgeon|banker|analyst|bartender|barista|musician|"
            r"photographer|carpenter|electrician|plumber|mechanic|founder|ceo|freelancer)\b",
            re.IGNORECASE,
        ),
    ),
    ClaimKind.RELATIONSHIP_STATUS: (
        re.compile(
            r"\b(?:i'm|im|i am|i've been|ive been) (?:\w+ )?(?:single|divorced|separated|"
            r"married|widowed|engaged|taken|seeing someone|in a relationship|"
            r"in an open relationship|poly|polyamorous)\b",
            re.IGNORECASE,
        ),
        re.compile(
            r"\bmy (?:ex|ex-wife|ex-husband|wife|husband|girlfriend|boyfriend|partner|"
            r"fianc[eé]e?|spouse)\b",
            re.IGNORECASE,
        ),
    ),
    ClaimKind.AVAILABILITY: (
        re.compile(
            r"\b(?:i'm|im|i am|i'll be|ill be|i will be) (?:totally |completely |pretty |)"
            r"(?:free|busy|available|around|off|out of town|away|swamped|booked)\b",
            re.IGNORECASE,
        ),
        re.compile(
            r"\b(?:i can (?:do|make|meet)|i could (?:do|make|meet)|i have (?:time|plans)|"
            r"i've got (?:time|plans)|ive got (?:time|plans)|i got plans|"
            r"my (?:schedule|calendar|weekend) is|i'm open|im open|i'll be there|ill be there|"
            r"works for me|that works|count me in|i'm down|im down)\b",
            re.IGNORECASE,
        ),
    ),
}

_TIME_WORDS = (
    r"(?:tonight|tomorrow|today|this (?:weekend|week|evening|morning|afternoon)|"
    r"next (?:week|weekend)|(?:on )?(?:mon|tues|wednes|thurs|fri|satur|sun)day|"
    r"\d{1,2}(?::\d{2})?\s?(?:am|pm)|noon|lunch|brunch|dinner|drinks|coffee)"
)
_PROPOSAL_PATTERNS = (
    re.compile(
        r"\b(?:let's|lets|wanna|want to|should we|how about|would you like to|we could|"
        r"down to|up for|shall we|why don't we|you free|are you free|free (?:to|for))\b"
        r"[^.?!]{0,80}?\b(?:meet|grab|get|go|hang|come|catch up|see each other|drinks?|"
        r"coffee|dinner|lunch|brunch|date|walk|hike|show|movie)\b",
        re.IGNORECASE,
    ),
    re.compile(
        r"\b(?:meet|grab|get|hang out|see you|catch up)\b[^.?!]{0,40}?\b" + _TIME_WORDS + r"\b",
        re.IGNORECASE,
    ),
)
_CONFIRMATION_PATTERNS = (
    re.compile(
        r"\b(?:it's a date|its a date|see you (?:then|there|tonight|tomorrow|on \w+|at \d)|"
        r"confirmed|it's set|its set|we're set|were set|booked|that works|works for me|"
        r"i'll be there|ill be there|count me in|it's on|done deal)\b",
        re.IGNORECASE,
    ),
)

# Explicit or coercive wording is refused outright, in every mode.
_UNSAFE_PATTERNS = (
    re.compile(
        r"\b(?:fuck(?:ing)?|cock|dick|pussy|cum|blowjob|horny|nudes?|naked pics?|"
        r"send (?:me )?(?:pics|a pic) of you|sext(?:ing)?|tits|boobs)\b",
        re.IGNORECASE,
    ),
    re.compile(
        r"\b(?:you owe me|don't be a tease|dont be a tease|stop being a tease|"
        r"you know you want(?: it| to)?|just come over already|why are you being so difficult|"
        r"i won't take no|i wont take no|don't say no|dont say no)\b",
        re.IGNORECASE,
    ),
)


def detect_personal_claims(text: str) -> set[ClaimKind]:
    """Kinds of first-person personal facts the text states (conservative regexes)."""
    return {kind for kind, pats in _CLAIM_PATTERNS.items() if any(p.search(text) for p in pats)}


def detect_commitment(text: str) -> Commitment:
    if any(p.search(text) for p in _CONFIRMATION_PATTERNS):
        return Commitment.CONFIRMATION
    if any(p.search(text) for p in _PROPOSAL_PATTERNS):
        return Commitment.PROPOSAL
    return Commitment.NONE


def detect_unsafe_content(text: str) -> bool:
    return any(p.search(text) for p in _UNSAFE_PATTERNS)


_COMMITMENT_RANK = {Commitment.NONE: 0, Commitment.PROPOSAL: 1, Commitment.CONFIRMATION: 2}

# --------------------------------------------------------------------------- style

_EMOJI_RE = re.compile(
    "[\U0001f1e6-\U0001f1ff\U0001f300-\U0001faff☀-➿⭐⭕❤]"
    "[️\U0001f3fb-\U0001f3ff]*"
    "(?:‍[\U0001f300-\U0001faff☀-➿❤][️\U0001f3fb-\U0001f3ff]*)*"
)
_SPACES_RE = re.compile(r"[ \t]{2,}")
_SPACE_BEFORE_PUNCT_RE = re.compile(r" +([,.!?])")

# Seconds to wait before an automated send, by pace, before clamping to the user's bounds.
PACE_DELAY_SECONDS: dict[ResponsePace, int] = {
    ResponsePace.IMMEDIATE: 0,
    ResponsePace.PROMPT: 120,
    ResponsePace.RELAXED: 900,
    ResponsePace.SLOW: 3600,
}


def count_emoji(text: str) -> int:
    return len(_EMOJI_RE.findall(text))


def _tidy(text: str) -> str:
    text = _SPACE_BEFORE_PUNCT_RE.sub(r"\1", _SPACES_RE.sub(" ", text))
    return "\n".join(line.strip() for line in text.splitlines()).strip()


def apply_style(text: str, prefs: EffectivePreferences) -> tuple[str, list[GuardrailViolation]]:
    """Deterministic, meaning-preserving style caps. Unset attributes change nothing.

    * ``emoji_frequency``: emoji beyond the cap (0 / 1 / 2 / no cap) are removed.
    * ``punctuation: minimal``: exclamation marks and a trailing period are removed.
    * ``brevity``: text over the length cap is not cut (that could change the meaning);
      it is flagged instead, which costs the draft a ``low_confidence`` approval.
    """
    style = prefs.style
    issues: list[GuardrailViolation] = []
    if style.emoji_frequency is not None:
        cap = EMOJI_MAX[style.emoji_frequency]
        found = count_emoji(text)
        if cap is not None and found > cap:
            kept = 0

            def keep_first(match: re.Match[str]) -> str:
                nonlocal kept
                kept += 1
                return match.group(0) if kept <= cap else ""

            text = _tidy(_EMOJI_RE.sub(keep_first, text))
            issues.append(
                GuardrailViolation(
                    code="style_emoji_trimmed", detail=f"{found - cap} emoji over the cap removed"
                )
            )
    if style.punctuation is Punctuation.MINIMAL:
        trimmed = _tidy(text.replace("!", ""))
        if trimmed.endswith(".") and not trimmed.endswith(".."):
            trimmed = trimmed[:-1]
        if trimmed != text:
            text = trimmed
            issues.append(GuardrailViolation(code="style_punctuation_trimmed", detail="minimal"))
    if style.brevity is not None:
        limit = BREVITY_MAX_CHARS[style.brevity]
        if limit is not None and len(text) > limit:
            issues.append(
                GuardrailViolation(
                    code="style_too_long",
                    detail=f"{len(text)} chars, cap {limit} for {style.brevity.value}",
                )
            )
    return text, issues


def suggested_delay(prefs: EffectivePreferences) -> int | None:
    pace = prefs.style.response_pace
    bounds = prefs.automation.reply_delay_seconds
    if pace is None:
        return None
    delay = PACE_DELAY_SECONDS[pace]
    if bounds is not None:
        delay = max(bounds.min, min(bounds.max, delay))
    return delay


# --------------------------------------------------------------------------- generator

_ASK_FOR: dict[ClaimKind, str] = {
    ClaimKind.LOCATION: "where you're based",
    ClaimKind.PROFESSION: "what you do for work",
    ClaimKind.RELATIONSHIP_STATUS: "your relationship situation",
    ClaimKind.AVAILABILITY: "when you're free",
}


@dataclass
class _Context:
    conversation_id: uuid.UUID
    contact_id: uuid.UUID
    stage: ConversationStage
    analysis: dict[str, Any] | None
    prefs: EffectivePreferences
    window: list[Message]
    memory: MemoryContext
    guidance: str | None
    has_outbound: bool
    message_refs: dict[str, Message] = field(default_factory=dict)
    fact_refs: dict[str, ContextFact] = field(default_factory=dict)

    @property
    def mode(self) -> IntentMode:
        return self.prefs.intent.mode

    @property
    def next_action(self) -> str | None:
        raw = (self.analysis or {}).get("next_action")
        return raw if isinstance(raw, str) else None


class ReplyGenerator:
    def __init__(
        self,
        gateway: LLMGateway,
        *,
        memory: MemoryService | None = None,
        limits: DraftLimits | None = None,
        now: Callable[[], datetime] = utc_now,
    ) -> None:
        self.gateway = gateway
        # Retrieval only (``get_context``): no gateway needed for that.
        self.memory = memory if memory is not None else MemoryService()
        self.limits = limits or DraftLimits()
        self._now = now

    async def generate_draft(
        self,
        session: AsyncSession,
        scope: UserScope,
        conversation_id: uuid.UUID,
        *,
        guidance: str | None = None,
    ) -> DraftResult:
        """Generate, check and audit one draft. Flushes but does not commit. Never sends.

        Raises ``NotFoundError`` if the conversation is not in ``scope``. Never raises
        because of the model: a model failure is a ``no_send`` with ``model_unavailable``.
        """
        ctx = await self._load(session, scope, conversation_id, guidance)
        draft = await self._decide(ctx, scope)
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.DRAFT_GENERATED,
            actor=Actor.AGENT,
            conversation_id=conversation_id,
            contact_id=ctx.contact_id,
            reason_code=draft.reason.value,
            occurred_at=draft.created_at,
            details=self._audit_details(draft, ctx),
        )
        logger.info(
            "draft user=%s conversation=%s decision=%s reason=%s approval=%s violations=%s "
            "model_error=%s messages=%d memories=%d",
            scope.user_id,
            conversation_id,
            draft.decision.value,
            draft.reason.value,
            ",".join(r.value for r in draft.approval_reasons),
            ",".join(v.code for v in draft.violations),
            draft.model_error,
            len(ctx.window),
            len(ctx.memory.memory_ids),
        )
        return draft

    # ------------------------------------------------------------------ context

    async def _load(
        self,
        session: AsyncSession,
        scope: UserScope,
        conversation_id: uuid.UUID,
        guidance: str | None,
    ) -> _Context:
        conversation = await ConversationRepository(session, scope).require(conversation_id)
        prefs = await get_effective_preferences(session, scope, conversation.contact_id)
        window = await self._window(session, scope, conversation_id)
        memory = await self.memory.get_context(
            session, scope, conversation_id, self._query(window, guidance), self.limits.memory
        )
        has_outbound = any(m.direction is MessageDirection.OUTBOUND for m in window) or bool(
            await session.scalar(
                select(Message.id)
                .where(
                    Message.user_id == scope.user_id,
                    Message.conversation_id == conversation_id,
                    Message.direction == MessageDirection.OUTBOUND,
                )
                .limit(1)
            )
        )
        cleaned = (guidance or "").strip()[: self.limits.max_guidance_chars] or None
        ctx = _Context(
            conversation_id=conversation_id,
            contact_id=conversation.contact_id,
            stage=conversation.stage,
            analysis=conversation.analysis,
            prefs=prefs,
            window=window,
            memory=memory,
            guidance=cleaned,
            has_outbound=has_outbound,
        )
        ctx.message_refs = {f"m{i}": m for i, m in enumerate(window, start=1)}
        ctx.fact_refs = {f"f{i}": f for i, f in enumerate(memory.facts, start=1)}
        return ctx

    async def _window(
        self, session: AsyncSession, scope: UserScope, conversation_id: uuid.UUID
    ) -> list[Message]:
        stmt = (
            select(Message)
            .where(Message.user_id == scope.user_id, Message.conversation_id == conversation_id)
            .order_by(Message.created_at.desc(), Message.id.desc())
            .limit(self.limits.max_messages)
        )
        return list(reversed((await session.scalars(stmt)).all()))

    def _query(self, window: Sequence[Message], guidance: str | None) -> str:
        inbound = [m.text for m in window if m.direction is MessageDirection.INBOUND]
        parts = [guidance] if guidance else []
        parts += list(reversed(inbound))
        return " ".join(parts)[: self.limits.max_query_chars] or "conversation"

    def _prompt_context(self, ctx: _Context) -> ReplyPromptContext:
        analysis = None
        if ctx.analysis is not None:
            analysis = AnalysisView(
                stage=str(ctx.analysis.get("stage", ctx.stage.value)),
                next_action=ctx.next_action or "reply",
                uncertain=bool(ctx.analysis.get("uncertain", False)),
            )
        summary = None
        if ctx.memory.summary is not None:
            summary = MemoryLine(ref="s1", rendered=ctx.memory.summary.content)
        return ReplyPromptContext(
            today=self._now().date().isoformat(),
            mode=ctx.mode,
            custom_description=ctx.prefs.intent.custom_description,
            style=ctx.prefs.style,
            messages=[
                PromptMessage(
                    ref=ref,
                    sender="contact" if m.direction is MessageDirection.INBOUND else "user",
                    sent_at=m.created_at.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
                    text=m.text[: self.limits.max_message_chars],
                )
                for ref, m in ctx.message_refs.items()
            ],
            memory=[MemoryLine(ref=ref, rendered=f.render()) for ref, f in ctx.fact_refs.items()],
            summary=summary,
            analysis=analysis,
            guidance=ctx.guidance,
        )

    # ------------------------------------------------------------------ decision

    async def _decide(self, ctx: _Context, scope: UserScope) -> DraftResult:
        if ctx.stage is ConversationStage.PAUSED or ctx.next_action == "stop":
            reason = (
                DraftReason.CONVERSATION_PAUSED
                if ctx.stage is ConversationStage.PAUSED
                else DraftReason.ANALYSIS_STOP
            )
            return self._no_send(
                ctx,
                reason,
                "The contact has pulled back or asked to stop; replying now is not advised.",
                message_ids=self._analysis_evidence(ctx),
            )
        has_inbound = any(m.direction is MessageDirection.INBOUND for m in ctx.window)
        if not has_inbound and ctx.guidance is None:
            return self._ask_user(
                ctx,
                DraftReason.NOTHING_TO_REPLY_TO,
                "There is nothing from the contact to reply to yet. What would you like to "
                "say, or what should the message be about?",
                "No message from the contact and no guidance: any opener would be invented.",
            )

        try:
            result = await self.gateway.generate(
                build_reply_messages(self._prompt_context(ctx)),
                ReplyOutput,
                operation=GENERATE_OPERATION,
                user_id=str(scope.user_id),
            )
        except LLMError as exc:
            error_class = str(exc.error_class)
            logger.warning(
                "draft model failure user=%s conversation=%s error_class=%s",
                scope.user_id,
                ctx.conversation_id,
                error_class,
            )
            return self._no_send(
                ctx,
                DraftReason.MODEL_UNAVAILABLE,
                "The model did not return a usable draft, so none is suggested.",
                model_error=error_class,
            )
        return self._check(ctx, result.output)

    def _check(self, ctx: _Context, out: ReplyOutput) -> DraftResult:
        # 1. Grounding: only refs the prompt handed out.
        known = set(ctx.message_refs) | set(ctx.fact_refs)
        if ctx.memory.summary is not None:
            known.add("s1")
        if ctx.guidance is not None:
            known.add(GUIDANCE_REF)
        if ctx.mode is IntentMode.CUSTOM and ctx.prefs.intent.custom_description:
            known.add(INTENT_REF)
        unknown = sorted({r for r in out.all_refs() if r not in known})
        misplaced = [r for r in out.message_refs if not r.startswith("m")] + [
            r for r in out.memory_refs if not (r.startswith("f") or r == "s1")
        ]
        if unknown or misplaced:
            return self._no_send(
                ctx,
                DraftReason.INVALID_GROUNDING,
                "The model cited context it was not given, so the draft is rejected.",
                violations=[
                    GuardrailViolation(
                        code="unknown_ref",
                        detail=f"{len(unknown)} unknown, {len(misplaced)} misplaced ref(s)",
                    )
                ],
            )
        memory_ids, message_ids = self._grounding(ctx, out.memory_refs, out.message_refs)

        if out.decision is DraftDecision.NO_SEND:
            return self._result(
                ctx,
                decision=DraftDecision.NO_SEND,
                reason=DraftReason.MODEL_DECISION,
                rationale=out.rationale,
                confidence=out.confidence,
                memory_ids=memory_ids,
                message_ids=message_ids,
            )
        if out.decision is DraftDecision.ASK_USER:
            reasons = []
            if out.commitment is not Commitment.NONE:
                reasons.append(ApprovalReason.DATE_OR_COMMITMENT)
            return self._result(
                ctx,
                decision=DraftDecision.ASK_USER,
                reason=DraftReason.MODEL_DECISION,
                question=(out.question_for_user or "").strip(),
                rationale=out.rationale,
                confidence=out.confidence,
                memory_ids=memory_ids,
                message_ids=message_ids,
                approval=reasons,
                commitment=out.commitment,
            )

        text = (out.text or "").strip()
        violations: list[GuardrailViolation] = []

        # 2. Unsafe content: explicit or coercive wording is refused in every mode.
        if detect_unsafe_content(text):
            return self._no_send(
                ctx,
                DraftReason.UNSAFE_CONTENT,
                "The draft contained explicit or pressuring language and was refused.",
                violations=[GuardrailViolation(code="unsafe_content", detail="pattern match")],
                memory_ids=memory_ids,
                message_ids=message_ids,
            )

        # 3. Escalation must be backed by the contact's own messages.
        if out.escalation:
            evidence = [ctx.message_refs.get(r) for r in out.interest_evidence_refs]
            if not evidence or any(
                m is None or m.direction is not MessageDirection.INBOUND for m in evidence
            ):
                return self._no_send(
                    ctx,
                    DraftReason.UNSUPPORTED_ESCALATION,
                    "The draft escalated without clear matching interest from the contact.",
                    violations=[
                        GuardrailViolation(
                            code="unsupported_escalation",
                            detail="no contact message shows matching interest",
                        )
                    ],
                    memory_ids=memory_ids,
                    message_ids=message_ids,
                )
            message_ids = _merge(message_ids, [m.id for m in evidence if m is not None])

        # 4. Commitments: the stronger of what the model declared and what the text says.
        detected = detect_commitment(text)
        commitment = max(out.commitment, detected, key=_COMMITMENT_RANK.__getitem__)

        # 5. Personal facts about the user must be grounded in the user's own words.
        grounded: set[ClaimKind] = set()
        for claim in out.user_claims:
            if claim.refs and all(self._user_source(ctx, r) for r in claim.refs):
                grounded.add(claim.kind)
                message_ids = _merge(
                    message_ids, [ctx.message_refs[r].id for r in claim.refs if r.startswith("m")]
                )
            else:
                violations.append(
                    GuardrailViolation(
                        code="ungrounded_claim",
                        detail=f"{claim.kind.value} not supported by the user's own words",
                    )
                )
        stated = detect_personal_claims(text) | {c.kind for c in out.user_claims}
        if commitment is Commitment.CONFIRMATION:
            # Agreeing to a plan states the user's availability.
            stated.add(ClaimKind.AVAILABILITY)
        missing = sorted(stated - grounded)
        if missing:
            violations.append(
                GuardrailViolation(
                    code="fabricated_personal_fact",
                    detail="ungrounded: " + ",".join(k.value for k in missing),
                )
            )
            asks = " and ".join(_ASK_FOR[k] for k in missing)
            reasons = (
                [ApprovalReason.DATE_OR_COMMITMENT]
                if ClaimKind.AVAILABILITY in missing or commitment is not Commitment.NONE
                else []
            )
            return self._result(
                ctx,
                decision=DraftDecision.ASK_USER,
                reason=DraftReason.FABRICATED_PERSONAL_FACT,
                question=f"Before I suggest a reply: can you tell me {asks}?",
                rationale=(
                    "The draft stated personal details Wingman does not know about you "
                    f"({', '.join(k.value for k in missing)}), so it was not used."
                ),
                confidence=out.confidence,
                memory_ids=memory_ids,
                message_ids=message_ids,
                approval=reasons,
                commitment=commitment,
                violations=violations,
            )

        # 6. Style caps.
        text, style_issues = apply_style(text, ctx.prefs)
        violations += style_issues
        if not text:
            return self._no_send(
                ctx,
                DraftReason.MODEL_DECISION,
                "Nothing was left of the draft after applying the style settings.",
                violations=violations,
            )

        # 7. Content-based approval flags (policy reasons are WING-011's).
        approval: list[ApprovalReason] = []
        if commitment is not Commitment.NONE:
            approval.append(ApprovalReason.DATE_OR_COMMITMENT)
        cited_facts = [ctx.fact_refs[r] for r in out.memory_refs if r in ctx.fact_refs]
        if out.escalation or any(f.sensitive for f in cited_facts):
            approval.append(ApprovalReason.SENSITIVE_CONTENT)
        if out.confidence < self.limits.low_confidence or any(
            v.code == "style_too_long" for v in style_issues
        ):
            approval.append(ApprovalReason.LOW_CONFIDENCE)
        if not ctx.has_outbound:
            approval.append(ApprovalReason.FIRST_MESSAGE)

        return self._result(
            ctx,
            decision=DraftDecision.SEND_CANDIDATE,
            reason=DraftReason.MODEL_DECISION,
            text=text,
            rationale=out.rationale,
            confidence=out.confidence,
            memory_ids=memory_ids,
            message_ids=message_ids,
            approval=approval,
            commitment=commitment,
            violations=violations,
        )

    # ------------------------------------------------------------------ helpers

    def _user_source(self, ctx: _Context, ref: str) -> bool:
        """Refs that can ground a fact about the USER: their own messages and settings."""
        if ref == GUIDANCE_REF:
            return ctx.guidance is not None
        if ref == INTENT_REF:
            return ctx.mode is IntentMode.CUSTOM and bool(ctx.prefs.intent.custom_description)
        msg = ctx.message_refs.get(ref)
        return msg is not None and msg.direction is MessageDirection.OUTBOUND

    def _grounding(
        self, ctx: _Context, memory_refs: Sequence[str], message_refs: Sequence[str]
    ) -> tuple[list[uuid.UUID], list[uuid.UUID]]:
        memory_ids: list[uuid.UUID] = []
        message_ids: list[uuid.UUID] = [ctx.message_refs[r].id for r in message_refs]
        for ref in memory_refs:
            if ref == "s1" and ctx.memory.summary is not None:
                memory_ids.append(ctx.memory.summary.id)
            elif ref in ctx.fact_refs:
                fact = ctx.fact_refs[ref]
                memory_ids.append(fact.id)
                message_ids += list(fact.source_message_ids)
        return _merge([], memory_ids), _merge([], message_ids)

    @staticmethod
    def _analysis_evidence(ctx: _Context) -> list[uuid.UUID]:
        window_ids = {m.id for m in ctx.window}
        out: list[uuid.UUID] = []
        for raw in (ctx.analysis or {}).get("source_message_ids", []):
            try:
                mid = uuid.UUID(str(raw))
            except ValueError:
                continue
            if mid in window_ids:
                out.append(mid)
        return _merge([], out)

    def _no_send(
        self,
        ctx: _Context,
        reason: DraftReason,
        rationale: str,
        *,
        violations: Sequence[GuardrailViolation] = (),
        model_error: str | None = None,
        memory_ids: Sequence[uuid.UUID] = (),
        message_ids: Sequence[uuid.UUID] = (),
    ) -> DraftResult:
        return self._result(
            ctx,
            decision=DraftDecision.NO_SEND,
            reason=reason,
            rationale=rationale,
            confidence=0.0 if model_error or reason is DraftReason.INVALID_GROUNDING else 0.9,
            memory_ids=memory_ids,
            message_ids=message_ids,
            violations=violations,
            model_error=model_error,
        )

    def _ask_user(
        self, ctx: _Context, reason: DraftReason, question: str, rationale: str
    ) -> DraftResult:
        return self._result(
            ctx,
            decision=DraftDecision.ASK_USER,
            reason=reason,
            question=question,
            rationale=rationale,
            confidence=0.9,
        )

    def _result(
        self,
        ctx: _Context,
        *,
        decision: DraftDecision,
        reason: DraftReason,
        rationale: str,
        confidence: float,
        text: str | None = None,
        question: str | None = None,
        memory_ids: Sequence[uuid.UUID] = (),
        message_ids: Sequence[uuid.UUID] = (),
        approval: Sequence[ApprovalReason] = (),
        commitment: Commitment = Commitment.NONE,
        violations: Sequence[GuardrailViolation] = (),
        model_error: str | None = None,
    ) -> DraftResult:
        self._assert_grounded(ctx, memory_ids, message_ids)
        reasons = tuple(dict.fromkeys(approval))
        return DraftResult(
            id=uuid7(),
            conversation_id=ctx.conversation_id,
            decision=decision,
            text=text,
            question_for_user=question,
            rationale=rationale,
            confidence=confidence,
            requires_approval=bool(reasons),
            approval_reasons=reasons,
            intent_mode=ctx.mode,
            grounding=Grounding(memory_ids=tuple(memory_ids), message_ids=tuple(message_ids)),
            created_at=self._now(),
            contact_id=ctx.contact_id,
            reason=reason,
            commitment=commitment,
            violations=tuple(violations),
            model_error=model_error,
            suggested_delay_seconds=(
                suggested_delay(ctx.prefs) if decision is DraftDecision.SEND_CANDIDATE else None
            ),
        )

    @staticmethod
    def _assert_grounded(
        ctx: _Context, memory_ids: Iterable[uuid.UUID], message_ids: Iterable[uuid.UUID]
    ) -> None:
        """Invariant: grounding only ever names IDs from the context this draft was given."""
        allowed_memory = set(ctx.memory.memory_ids)
        allowed_messages = {m.id for m in ctx.window} | set(ctx.memory.source_message_ids)
        if ctx.memory.summary is not None:
            allowed_messages |= set(ctx.memory.summary.source_message_ids)
        if not set(memory_ids) <= allowed_memory or not set(message_ids) <= allowed_messages:
            raise RuntimeError("draft grounding names an ID outside its context")

    @staticmethod
    def _audit_details(draft: DraftResult, ctx: _Context) -> dict[str, Any]:
        """IDs, codes and counts only: never message text, draft text or questions."""
        return {
            "draft_id": str(draft.id),
            "decision": draft.decision.value,
            "reason": draft.reason.value,
            "confidence": draft.confidence,
            "requires_approval": draft.requires_approval,
            "approval_reasons": [r.value for r in draft.approval_reasons],
            "intent_mode": draft.intent_mode.value,
            "commitment": draft.commitment.value,
            "violations": [v.code for v in draft.violations],
            "model_error": draft.model_error,
            "messages_considered": len(ctx.window),
            "memory_ids": [str(m) for m in draft.grounding.memory_ids],
            "message_ids": [str(m) for m in draft.grounding.message_ids],
        }


def _merge(base: Sequence[uuid.UUID], extra: Iterable[uuid.UUID]) -> list[uuid.UUID]:
    return list(dict.fromkeys([*base, *extra]))


async def generate_draft(
    session: AsyncSession,
    scope: UserScope,
    conversation_id: uuid.UUID,
    *,
    guidance: str | None = None,
    generator: ReplyGenerator | None = None,
) -> DraftResult:
    """Convenience entry point. Builds a generator from settings when none is given."""
    gen = generator if generator is not None else ReplyGenerator(build_gateway())
    return await gen.generate_draft(session, scope, conversation_id, guidance=guidance)
