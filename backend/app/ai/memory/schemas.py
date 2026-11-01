"""Types for conversation memory: the strict LLM output schemas and the domain objects.

Two families live here:

* **Model output** (``ExtractionOutput``, ``SummaryOutput``): what the LLM must return.
  They are validated by the gateway in strict mode with ``extra="forbid"``. Anything
  that fails validation is rejected and nothing is stored. Messages are cited by the
  short refs (``m1``, ``m2``, ...) the prompt assigned; the service maps them back to
  message IDs and rejects any ref it did not hand out.
* **Domain** (``MemoryFact``, ``MemoryContext``, ``ContextLimits``, ...): what callers
  (WING-009, WING-010, the API) receive. Immutable, and never carries another user's or
  contact's data because every one is built from a scoped query.
"""

import re
import uuid
from collections.abc import Sequence
from datetime import date, datetime
from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

# --------------------------------------------------------------------------- vocabulary


class FactCategory(StrEnum):
    IDENTITY = "identity"
    WORK = "work"
    EDUCATION = "education"
    LOCATION = "location"
    FAMILY = "family"
    PETS = "pets"
    INTERESTS = "interests"
    PREFERENCES = "preferences"
    PLANS = "plans"
    AVAILABILITY = "availability"
    RELATIONSHIP = "relationship"
    COMMUNICATION = "communication"
    # Sensitive categories (see ``SENSITIVE_CATEGORIES``).
    HEALTH = "health"
    SEXUALITY = "sexuality"
    RELIGION = "religion"
    POLITICS = "politics"
    ETHNICITY = "ethnicity"
    FINANCES = "finances"
    OTHER = "other"


SENSITIVE_CATEGORIES: frozenset[FactCategory] = frozenset(
    {
        FactCategory.HEALTH,
        FactCategory.SEXUALITY,
        FactCategory.RELIGION,
        FactCategory.POLITICS,
        FactCategory.ETHNICITY,
        FactCategory.FINANCES,
    }
)
_SENSITIVE_NAMES = frozenset(c.value for c in SENSITIVE_CATEGORIES)


def is_sensitive(category: str | None, key: str | None) -> bool:
    """Sensitive if the category or the key's first segment names a sensitive category."""
    if category in _SENSITIVE_NAMES:
        return True
    return key is not None and key.split(".", 1)[0] in _SENSITIVE_NAMES


class FactBasis(StrEnum):
    STATED = "stated"  # said explicitly in a cited message
    INFERRED = "inferred"  # the model's reading of the cited messages


class FactOrigin(StrEnum):
    EXTRACTED = "extracted"
    USER_CORRECTION = "user_correction"


FACT_KEY_PATTERN = r"^[a-z][a-z0-9_]{0,30}(\.[a-z0-9_]{1,30}){0,3}$"
MESSAGE_REF_PATTERN = r"^m[1-9][0-9]{0,3}$"
MAX_FACT_CHARS = 500
MAX_SUMMARY_CHARS = 2000
_REF_RE = re.compile(MESSAGE_REF_PATTERN)


# --------------------------------------------------------------------------- model output


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class ExtractedFact(_Strict):
    """One fact as the model reports it. Every field is required (no silent defaults)."""

    key: str = Field(
        pattern=FACT_KEY_PATTERN,
        description=(
            "Stable slot for this fact, e.g. 'work.job_title' or 'interests.hiking'. A "
            "newer statement for the same key replaces the older one. Start the key with "
            "the category for health, sexuality, religion, politics, ethnicity, finances."
        ),
    )
    category: FactCategory
    content: str = Field(min_length=1, max_length=MAX_FACT_CHARS)
    basis: FactBasis
    confidence: float = Field(ge=0, le=1)
    source_refs: list[str] = Field(
        min_length=1,
        max_length=20,
        description="Refs (m1, m2, ...) of the messages this fact comes from.",
    )
    valid_until: date | None = Field(
        description="Last day the fact holds, for time-bound facts (plans, availability)."
    )

    @model_validator(mode="after")
    def _refs_well_formed(self) -> "ExtractedFact":
        for ref in self.source_refs:
            if not _REF_RE.fullmatch(ref):
                raise ValueError("source_refs entries must look like m1, m2, ...")
        if len(set(self.source_refs)) != len(self.source_refs):
            raise ValueError("source_refs must not repeat")
        return self


class ExtractionOutput(_Strict):
    facts: list[ExtractedFact] = Field(max_length=50)


class SummaryOutput(_Strict):
    summary: str = Field(min_length=1, max_length=MAX_SUMMARY_CHARS)


# --------------------------------------------------------------------------- domain


class MemoryFact(_Strict):
    """A stored fact or summary, as callers see it."""

    id: uuid.UUID
    contact_id: uuid.UUID
    conversation_id: uuid.UUID | None
    kind: Literal["fact", "summary"]
    key: str | None
    category: str | None
    content: str
    basis: FactBasis
    origin: FactOrigin
    confidence: float
    sensitive: bool
    source_message_ids: tuple[uuid.UUID, ...]
    stated_at: datetime | None
    expires_at: datetime | None
    superseded_by_id: uuid.UUID | None
    deleted_at: datetime | None
    created_at: datetime

    @property
    def established(self) -> bool:
        """Stated by the contact or corrected by the user, as opposed to inferred."""
        return self.basis is FactBasis.STATED


class SkipReason(StrEnum):
    LOW_CONFIDENCE = "low_confidence"
    SENSITIVE_INFERRED = "sensitive_inferred"
    ALREADY_EXPIRED = "already_expired"
    DUPLICATE = "duplicate"
    DELETED_BY_USER = "deleted_by_user"


class SkippedFact(_Strict):
    key: str
    reason: SkipReason


class ExtractionResult(_Strict):
    """What one ``extract_from_messages`` call changed. IDs only, safe to log."""

    created: tuple[MemoryFact, ...]
    # Previously active facts that a new fact replaced (now ``superseded_by_id`` set).
    superseded_ids: tuple[uuid.UUID, ...]
    # New facts stored as history only, because an existing fact outranks them.
    stored_as_history_ids: tuple[uuid.UUID, ...]
    skipped: tuple[SkippedFact, ...]


class ContextLimits(_Strict):
    """Bounds on what ``get_context`` returns. Defaults suit one reply prompt."""

    max_facts: int = Field(default=12, ge=0, le=100)
    max_chars: int = Field(default=1500, ge=0, le=20_000)
    max_summary_chars: int = Field(default=800, ge=0, le=MAX_SUMMARY_CHARS)
    include_inferred: bool = True
    # Stated sensitive facts (the contact said it) may be needed for a respectful reply;
    # set False to keep them out of a prompt entirely. Inferred sensitive facts are
    # never stored, so they can never appear here.
    include_sensitive: bool = True
    min_confidence: float = Field(default=0.0, ge=0, le=1)
    # Upper bound on active facts scored per call (newest first).
    candidate_pool: int = Field(default=500, ge=1, le=5000)


class ContextFact(_Strict):
    id: uuid.UUID
    key: str | None
    category: str | None
    content: str
    basis: FactBasis
    origin: FactOrigin
    confidence: float
    sensitive: bool
    source_message_ids: tuple[uuid.UUID, ...]
    stated_at: datetime | None
    score: float

    def render(self) -> str:
        tags: list[str] = []
        if self.basis is FactBasis.INFERRED:
            tags.append("inferred, unconfirmed")
        if self.origin is FactOrigin.USER_CORRECTION:
            tags.append("confirmed by user")
        suffix = f" ({'; '.join(tags)})" if tags else ""
        label = self.key or (self.category or "fact")
        return f"- [{label}] {self.content}{suffix}"


class ContextSummary(_Strict):
    id: uuid.UUID
    content: str
    truncated: bool
    source_message_ids: tuple[uuid.UUID, ...]


class MemoryContext(_Strict):
    """Bounded, relevance-ranked memory for one prompt. Never the full history."""

    contact_id: uuid.UUID
    conversation_id: uuid.UUID
    facts: tuple[ContextFact, ...]
    summary: ContextSummary | None
    strategy: Literal["vector", "lexical"]
    # Active facts that matched the filters but did not fit the limits.
    omitted_count: int = Field(ge=0)
    char_count: int = Field(ge=0)

    @property
    def memory_ids(self) -> list[uuid.UUID]:
        """For ``Draft.grounding.memory_ids``."""
        ids = [f.id for f in self.facts]
        if self.summary is not None:
            ids.append(self.summary.id)
        return ids

    @property
    def source_message_ids(self) -> list[uuid.UUID]:
        seen: dict[uuid.UUID, None] = {}
        for f in self.facts:
            for m in f.source_message_ids:
                seen.setdefault(m, None)
        return list(seen)

    def render(self) -> str:
        """Plain-text block for a prompt, at most ``ContextLimits.max_chars`` long.

        Inferred facts are labeled as such.
        """
        return render_context(self.summary.content if self.summary else None, self.facts)


SUMMARY_HEADER = "Conversation summary:\n"
FACTS_HEADER = "Known facts about this contact:"


def render_context(summary: str | None, facts: Sequence[ContextFact]) -> str:
    parts: list[str] = []
    if summary is not None:
        parts.append(SUMMARY_HEADER + summary)
    if facts:
        parts.append(FACTS_HEADER + "".join("\n" + f.render() for f in facts))
    return "\n\n".join(parts)
