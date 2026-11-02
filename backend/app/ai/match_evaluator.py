"""Compatibility scoring for dating profiles (WING-019).

Public API::

    evaluator = MatchEvaluator(gateway)          # or MatchEvaluator() for rules only
    result = await evaluator.evaluate(profile, preferences, intent)
    result.score, result.reasons, result.known_unknowns, result.confidence

``evaluate`` returns an :class:`EvaluationResult` whose fields map 1:1 onto the
contract ``Evaluation`` (minus ``id``, ``profile_id`` and ``created_at``, which the
store adds). It is **advisory only**: nothing here decides, records or executes a swipe.

Design (hybrid)
    1. **Rules** (deterministic, always run). Only *structured attributes the source
       provided* are read. Dealbreakers are checked against them (a hit excludes the
       profile, score 0, with the reason and the attribute as evidence); the user's
       intent is compared with a stated intent attribute (``looking_for`` and similar).
       Anything the preferences care about that the profile does not state is listed
       in ``known_unknowns``; it is never guessed.
    2. **Model pass** (optional, through the WING-005 gateway). Only the bio and prompts
       (and non-sensitive attributes) go to the model, which must answer a strict
       :class:`MatchResonanceOutput`. Every model reason must cite a real field of
       *this* profile (``bio``, ``prompts/<i>``, ``attributes/<name>``) and quote text
       that appears in a cited field, else it is rejected. Reasons about protected or
       physical traits (age, ethnicity, religion, orientation, health, body, looks,
       photos) are rejected whatever they cite, and the model can never add a
       dealbreaker hit. Its score adjustment is bounded and scaled by the share of
       accepted reasons. A model failure (timeout, transport, malformed or invalid
       output) falls back to rules only with **lowered** confidence.

Never inferred
    The rules never read free text for attributes, and no attribute is derived from a
    display name, a photo, a URL or model output. Image-like attributes (``photos``,
    ``image_url``, any URL value) are dropped before scoring and are neither scored,
    cited nor sent to the model. ``display_name`` is never used.

Evidence pointers use the contract's forms: ``bio``, ``prompts/0``, ``attributes/age``.
"""

import hashlib
import json
import logging
import re
import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field, replace
from enum import StrEnum
from typing import Any, Literal, Protocol

from pydantic import BaseModel, ConfigDict, Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.ai.llm_gateway import LLMGateway, LLMMessage
from app.ai.providers.errors import LLMError
from app.db.enums import IntentMode

__all__ = [
    "EVALUATOR_VERSION",
    "RULES_VERSION",
    "EvaluationResult",
    "IntentInput",
    "LLMStatus",
    "MatchEvaluator",
    "MatchResonanceOutput",
    "MatchSettings",
    "PreferenceInput",
    "ProfileInput",
    "ResonanceReason",
    "evaluate_rules",
    "input_fingerprint",
    "usable_attributes",
    "valid_evidence",
]

logger = logging.getLogger(__name__)

# Bump RULES_VERSION when the deterministic rules change; it is part of the input
# fingerprint, so stored evaluations are lazily refreshed.
RULES_VERSION = "1.0"
EVALUATOR_VERSION = f"wing019-{RULES_VERSION}"
EVALUATOR_PREFIX = "wing019-"

BASE_SCORE = 50.0
MAX_LLM_ADJUSTMENT = 15
MAX_CLEARED_BONUS = 15.0
CLEARED_BONUS = 5.0
RULES_CONFIDENCE_FLOOR = 0.2
RULES_CONFIDENCE_SPAN = 0.4
LLM_CONFIDENCE_BONUS = 0.15
LLM_FAILURE_PENALTY = 0.1
DEALBREAKER_CONFIDENCE = 0.9
UNSCORED_CONFIDENCE = 0.1

MAX_DETAIL = 500
MAX_UNKNOWN = 200


class MatchSettings(BaseSettings):
    """``WINGMAN_MATCH_LLM_ENABLED``: run the optional model pass in the app (off by
    default, so the review queue never needs a model)."""

    model_config = SettingsConfigDict(env_prefix="WINGMAN_MATCH_", env_file=".env", extra="ignore")

    llm_enabled: bool = False


# --------------------------------------------------------------------------- inputs


class _ProfileLike(Protocol):
    @property
    def provider(self) -> Any: ...
    @property
    def bio(self) -> str | None: ...
    @property
    def prompts(self) -> list[dict[str, Any]]: ...
    @property
    def attributes(self) -> dict[str, Any]: ...


@dataclass(frozen=True)
class ProfileInput:
    """What the evaluator may look at. ``display_name`` is deliberately absent."""

    provider: str
    bio: str | None = None
    prompts: tuple[Mapping[str, str], ...] = ()
    attributes: Mapping[str, Any] = field(default_factory=dict)

    @classmethod
    def of(cls, profile: "ProfileInput | _ProfileLike") -> "ProfileInput":
        if isinstance(profile, ProfileInput):
            return profile
        provider = getattr(profile.provider, "value", profile.provider)
        return cls(
            provider=str(provider),
            bio=profile.bio,
            prompts=tuple(dict(p) for p in (profile.prompts or [])),
            attributes=dict(profile.attributes or {}),
        )


@dataclass(frozen=True)
class IntentInput:
    mode: IntentMode
    custom_description: str | None = None


@dataclass(frozen=True)
class PreferenceInput:
    """The parts of ``Preferences`` scoring uses (user-wide, versioned)."""

    version: int
    intent: IntentInput
    dealbreakers: tuple[str, ...] = ()

    @classmethod
    def of(cls, preferences: Any) -> "PreferenceInput":
        if isinstance(preferences, PreferenceInput):
            return preferences
        intent = preferences.intent
        return cls(
            version=int(preferences.version),
            intent=IntentInput(intent.mode, intent.custom_description),
            dealbreakers=tuple(preferences.dealbreakers),
        )


class LLMStatus(StrEnum):
    NOT_USED = "not_used"
    USED = "used"
    FAILED = "failed"


@dataclass(frozen=True)
class EvaluationResult:
    score: float | None
    reasons: list[dict[str, Any]]
    known_unknowns: list[str]
    intent_mode: IntentMode
    dealbreakers_hit: list[str]
    confidence: float
    evaluator_version: str
    llm_status: LLMStatus = LLMStatus.NOT_USED
    llm_reasons_rejected: int = 0

    @property
    def excluded(self) -> bool:
        return bool(self.dealbreakers_hit)


# --------------------------------------------------------------------------- normalizing

_IMAGE_KEY_RE = re.compile(
    r"(?:^|_)(?:photos?|images?|pictures?|pics?|avatars?|selfies?|videos?|media|thumbnails?)"
    r"(?:_|$)"
)
_URL_RE = re.compile(r"^\s*(?:https?://|data:|file:|content://|www\.)", re.IGNORECASE)

# Attributes that describe protected or sensitive traits. The rules use them only for an
# explicit user dealbreaker on that same stated attribute; they are never sent to the
# model, and model reasons about these topics are rejected.
_SENSITIVE_KEY_RE = re.compile(
    r"(?:^|_)(?:age|birth\w*|dob|ethnic\w*|race|religio\w*|faith|orientation|sexuality|"
    r"gender\w*|health|disabilit\w*|medical|vaccin\w*|weight|height\w*|body\w*|politic\w*)"
    r"(?:_|$)"
)


def _norm(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).casefold()
    return re.sub(r"\s+", " ", text).strip()


def _is_image_value(value: Any) -> bool:
    if isinstance(value, str):
        return bool(_URL_RE.match(value))
    if isinstance(value, list):
        return any(isinstance(v, str) and _URL_RE.match(v) for v in value)
    return False


def usable_attributes(attributes: Mapping[str, Any]) -> dict[str, Any]:
    """Provided attributes minus images, URLs and empty values (never scored)."""
    out: dict[str, Any] = {}
    for key, value in attributes.items():
        if _IMAGE_KEY_RE.search(key) or _is_image_value(value):
            continue
        if value is None or value == "" or value == []:
            continue
        out[key] = value
    return out


def _field_texts(profile: ProfileInput, attrs: Mapping[str, Any]) -> dict[str, str]:
    """Every citable field and its text (for evidence and quote checks)."""
    out: dict[str, str] = {}
    if profile.bio and profile.bio.strip():
        out["bio"] = profile.bio
    for i, p in enumerate(profile.prompts):
        out[f"prompts/{i}"] = f"{p.get('prompt', '')}\n{p.get('answer', '')}"
    for key, value in attrs.items():
        if isinstance(value, list):
            out[f"attributes/{key}"] = ", ".join(str(v) for v in value)
        else:
            out[f"attributes/{key}"] = str(value)
    return out


def valid_evidence(profile: ProfileInput, pointers: Sequence[str]) -> bool:
    """True only if every pointer names a field this profile actually provides (and is
    not an image or URL)."""
    fields = _field_texts(profile, usable_attributes(profile.attributes))
    return bool(pointers) and all(p in fields for p in pointers)


# --------------------------------------------------------------------------- truthiness

_YES = {
    "yes",
    "y",
    "true",
    "often",
    "regularly",
    "socially",
    "sometimes",
    "occasionally",
    "daily",
    "frequently",
    "yes please",
    "want",
    "wants",
    "want someday",
    "want children",
    "want kids",
    "have kids",
    "has kids",
    "have children",
    "has children",
    "smoker",
    "drinker",
    "open to it",
}
_NO = {
    "no",
    "n",
    "false",
    "never",
    "none",
    "non-smoker",
    "nonsmoker",
    "non smoker",
    "sober",
    "don't",
    "dont",
    "do not",
    "don't want",
    "dont want",
    "don't want children",
    "don't want kids",
    "do not want kids",
    "do not want children",
    "no kids",
    "no children",
    "doesn't have kids",
    "don't have kids",
    "not at all",
    "rarely",  # treated as "no" only for the yes/no topic checks below
}
_UNDISCLOSED = {"prefer not to say", "prefer not to share", "ask me", "unknown", "n/a", "na"}


def _yes_no(value: Any) -> bool | None:
    """``True``/``False`` for a clearly stated yes/no value, else ``None``."""
    if isinstance(value, bool):
        return value
    if isinstance(value, int | float):
        return value > 0
    if isinstance(value, str):
        v = _norm(value).rstrip(".!")
        if v in _UNDISCLOSED:
            return None
        if v in _YES:
            return True
        if v in _NO:
            return False
    return None


def _number(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int | float):
        return float(value)
    if isinstance(value, str) and re.fullmatch(r"\s*\d{1,3}(?:\.\d+)?\s*", value):
        return float(value)
    return None


# --------------------------------------------------------------------------- dealbreakers


@dataclass(frozen=True)
class _Topic:
    label: str
    keys: tuple[str, ...]
    phrases: tuple[str, ...]
    hit_when: bool  # a stated yes (True) or no (False) is the dealbreaker


_TOPICS: tuple[_Topic, ...] = (
    _Topic(
        "smoking",
        ("smoking", "smokes", "smoker", "tobacco", "cigarettes"),
        ("smoking", "smoker", "smokers", "smokes", "cigarettes", "tobacco"),
        True,
    ),
    _Topic(
        "drinking",
        ("drinking", "drinks", "alcohol"),
        ("drinking", "drinker", "drinkers", "drinks", "alcohol", "heavy drinking"),
        True,
    ),
    _Topic(
        "drugs",
        ("drugs", "recreational_drugs", "drug_use"),
        ("drugs", "drug use", "recreational drugs"),
        True,
    ),
    _Topic(
        "cannabis",
        ("cannabis", "marijuana", "weed"),
        ("cannabis", "marijuana", "weed", "420"),
        True,
    ),
    _Topic(
        "has children",
        ("has_kids", "has_children", "children", "kids"),
        ("has kids", "has children", "already has kids", "already has children", "kids"),
        True,
    ),
    _Topic(
        "wants children",
        ("wants_kids", "wants_children", "family_plans"),
        ("wants kids", "wants children", "wants a family"),
        True,
    ),
    _Topic(
        "does not want children",
        ("wants_kids", "wants_children", "family_plans"),
        (
            "doesn't want kids",
            "does not want kids",
            "doesn't want children",
            "does not want children",
            "no kids wanted",
            "not wanting kids",
        ),
        False,
    ),
)

_AGE_UNDER_RE = re.compile(r"^(?:age\s*)?(?:under|younger than|below|less than|<)\s*(\d{2})$")
_AGE_OVER_RE = re.compile(r"^(?:age\s*)?(?:over|older than|above|more than|>)\s*(\d{2})$")


def _snake(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", text).strip("_")


@dataclass
class _Rules:
    reasons: list[dict[str, Any]] = field(default_factory=list)
    unknowns: list[str] = field(default_factory=list)
    hits: list[str] = field(default_factory=list)
    relevant: int = 0
    known: int = 0
    cleared: int = 0
    intent_delta: float = 0.0

    def reason(self, factor: str, effect: str, detail: str, evidence: Sequence[str]) -> None:
        self.reasons.append(
            {
                "factor": factor,
                "effect": effect,
                "detail": detail[:MAX_DETAIL],
                "evidence": list(evidence),
            }
        )

    def unknown(self, label: str) -> None:
        label = label[:MAX_UNKNOWN]
        if label not in self.unknowns:
            self.unknowns.append(label)


def _check_dealbreaker(raw: str, attrs: Mapping[str, Any], out: _Rules) -> None:
    text = _norm(raw)
    out.relevant += 1
    # Age bounds on a stated numeric ``age`` only (never from text or photos).
    for pattern, under in ((_AGE_UNDER_RE, True), (_AGE_OVER_RE, False)):
        m = pattern.match(text)
        if not m:
            continue
        bound = float(m.group(1))
        age = _number(attrs.get("age"))
        if age is None:
            out.unknown("age")
            return
        out.known += 1
        if (age < bound) if under else (age > bound):
            out.hits.append(raw)
            out.reason(
                "dealbreaker",
                "negative",
                f"Stated age is {'under' if under else 'over'} {int(bound)} (your dealbreaker).",
                ["attributes/age"],
            )
        else:
            out.cleared += 1
            out.reason(
                "dealbreaker",
                "positive",
                f"Stated age clears your dealbreaker '{raw[:80]}'.",
                ["attributes/age"],
            )
        return
    for topic in _TOPICS:
        if text not in topic.phrases:
            continue
        present = [k for k in topic.keys if k in attrs]
        if not present:
            out.unknown(topic.label)
            return
        key = present[0]
        stated = _yes_no(attrs[key])
        if stated is None:
            out.unknown(f"{topic.label} (stated value is not a clear yes or no)")
            return
        out.known += 1
        if stated is topic.hit_when:
            out.hits.append(raw)
            out.reason(
                "dealbreaker",
                "negative",
                f"Profile states {key.replace('_', ' ')}: {attrs[key]!s:.60} "
                f"(your dealbreaker '{raw[:80]}').",
                [f"attributes/{key}"],
            )
        else:
            out.cleared += 1
            out.reason(
                "dealbreaker",
                "positive",
                f"Profile states {key.replace('_', ' ')}: {attrs[key]!s:.60}, "
                f"which clears your dealbreaker '{raw[:80]}'.",
                [f"attributes/{key}"],
            )
        return
    # Generic: the dealbreaker names a provided yes/no attribute exactly.
    key = _snake(text)
    if key and key in attrs:
        stated = _yes_no(attrs[key])
        if stated is None:
            out.unknown(f"{raw} (stated value is not a clear yes or no)")
            return
        out.known += 1
        if stated:
            out.hits.append(raw)
            out.reason(
                "dealbreaker",
                "negative",
                f"Profile states {key.replace('_', ' ')} (your dealbreaker).",
                [f"attributes/{key}"],
            )
        else:
            out.cleared += 1
            out.reason(
                "dealbreaker",
                "positive",
                f"Profile states no {key.replace('_', ' ')}, which clears your dealbreaker.",
                [f"attributes/{key}"],
            )
        return
    out.unknown(raw)


# --------------------------------------------------------------------------- intent

_INTENT_KEYS = (
    "looking_for",
    "relationship_goals",
    "relationship_goal",
    "relationship_type",
    "dating_intentions",
    "dating_intention",
    "dating_goals",
    "intent",
    "intentions",
)

_CASUAL = re.compile(r"\b(?:short[- ]term|casual|something fun|non[- ]committal)\b")
_PHYSICAL = re.compile(
    r"\b(?:hook ?ups?|fwb|friends with benefits|something physical|casual sex|nsa|intimacy)\b"
)
_OPEN = re.compile(
    r"\b(?:figuring|not sure|unsure|open to (?:options|anything|both|either)|open|anything|"
    r"either|see where it goes)\b"
)
_FRIENDS = re.compile(r"\b(?:new friends|friendship|just friends|friends)\b")

# Score delta for (user mode, profile category). Missing pairs are 0.
_INTENT_TABLE: dict[IntentMode, dict[str, float]] = {
    IntentMode.SERIOUS: {
        "serious": 25,
        "open": 5,
        "casual_dating": -15,
        "casual_physical": -30,
        "friendship": -30,
    },
    IntentMode.CASUAL_DATING: {
        "casual_dating": 25,
        "open": 10,
        "serious": -5,
        "casual_physical": 0,
        "friendship": -30,
    },
    IntentMode.CASUAL_PHYSICAL: {
        "casual_physical": 25,
        "casual_dating": 10,
        "open": 5,
        "serious": -25,
        "friendship": -30,
    },
    IntentMode.OPEN: {
        "open": 15,
        "serious": 10,
        "casual_dating": 10,
        "casual_physical": 10,
        "friendship": -10,
    },
}


def _intent_categories(value: Any) -> set[str]:
    texts = value if isinstance(value, list) else [value]
    cats: set[str] = set()
    for t in texts:
        if not isinstance(t, str):
            continue
        s = _norm(t)
        physical = bool(_PHYSICAL.search(s))
        if physical:
            cats.add("casual_physical")
        if re.search(r"\bshort[- ]term\b", s) or (_CASUAL.search(s) and not physical):
            cats.add("casual_dating")
        if re.search(r"\blong[- ]term\b|\blife partner\b|\bmarriage\b|\bserious\b", s) or (
            "relationship" in s and not re.search(r"\bshort[- ]term\b", s)
        ):
            cats.add("serious")
        if _OPEN.search(s):
            cats.add("open")
        if _FRIENDS.search(s) and not physical:
            cats.add("friendship")
    # "Short-term, open to long" mentions both terms: keep both categories.
    if re.search(r"\blong\b", " ".join(_norm(t) for t in texts if isinstance(t, str))):
        if "casual_dating" in cats:
            cats.add("serious")
    return cats


def _check_intent(intent: IntentInput, attrs: Mapping[str, Any], out: _Rules) -> None:
    out.relevant += 1
    key = next((k for k in _INTENT_KEYS if k in attrs), None)
    if key is None:
        out.unknown("relationship intent")
        out.reason(
            "intent",
            "neutral",
            "The profile does not state what they are looking for.",
            [],
        )
        return
    cats = _intent_categories(attrs[key])
    if not cats:
        out.unknown("relationship intent (stated value not recognized)")
        out.reason(
            "intent",
            "neutral",
            f"Stated intent '{attrs[key]!s:.80}' could not be compared with yours.",
            [f"attributes/{key}"],
        )
        return
    out.known += 1
    if intent.mode is IntentMode.CUSTOM:
        out.reason(
            "intent",
            "neutral",
            f"States '{attrs[key]!s:.80}'; your custom goal is compared only in the "
            "model pass, if enabled.",
            [f"attributes/{key}"],
        )
        return
    table = _INTENT_TABLE[intent.mode]
    delta = max(table.get(c, 0.0) for c in cats)
    out.intent_delta = delta
    effect = "positive" if delta > 0 else "negative" if delta < 0 else "neutral"
    verb = {"positive": "fits", "negative": "conflicts with", "neutral": "is neutral for"}[effect]
    out.reason(
        "intent",
        effect,
        f"States '{attrs[key]!s:.80}', which {verb} your intent ({intent.mode.value}).",
        [f"attributes/{key}"],
    )


# --------------------------------------------------------------------------- rules


@dataclass(frozen=True)
class RulesOutcome:
    score: float | None
    reasons: list[dict[str, Any]]
    known_unknowns: list[str]
    dealbreakers_hit: list[str]
    confidence: float
    has_content: bool


def evaluate_rules(
    profile: ProfileInput | _ProfileLike,
    preferences: PreferenceInput | Any,
    intent: IntentInput | Any | None = None,
) -> RulesOutcome:
    """The deterministic part (pure). Reads stated attributes only."""
    p = ProfileInput.of(profile)
    prefs = PreferenceInput.of(preferences)
    it = _intent_of(intent, prefs)
    attrs = usable_attributes(p.attributes)
    out = _Rules()
    for raw in prefs.dealbreakers:
        _check_dealbreaker(raw, attrs, out)
    _check_intent(it, attrs, out)
    has_content = bool(
        (p.bio and p.bio.strip()) or any(pr.get("answer", "").strip() for pr in p.prompts) or attrs
    )
    coverage = out.known / out.relevant if out.relevant else 0.0
    if out.hits:
        score: float | None = 0.0
        confidence = DEALBREAKER_CONFIDENCE
    elif not has_content:
        score = None
        confidence = UNSCORED_CONFIDENCE
        out.reason("content", "neutral", "The profile has nothing to evaluate.", [])
    else:
        bonus = min(MAX_CLEARED_BONUS, CLEARED_BONUS * out.cleared)
        score = _clamp(BASE_SCORE + out.intent_delta + bonus)
        confidence = RULES_CONFIDENCE_FLOOR + RULES_CONFIDENCE_SPAN * coverage
    return RulesOutcome(
        score=score,
        reasons=out.reasons,
        known_unknowns=out.unknowns,
        dealbreakers_hit=out.hits,
        confidence=round(confidence, 3),
        has_content=has_content,
    )


def _intent_of(intent: Any, prefs: PreferenceInput) -> IntentInput:
    if intent is None:
        return prefs.intent
    if isinstance(intent, IntentInput):
        return intent
    if isinstance(intent, IntentMode):
        return IntentInput(intent)
    return IntentInput(intent.mode, getattr(intent, "custom_description", None))


def _clamp(value: float) -> float:
    return float(max(0.0, min(100.0, round(value, 1))))


# --------------------------------------------------------------------------- model pass


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class ResonanceReason(_Strict):
    factor: str = Field(min_length=1, max_length=64, pattern=r"^[a-z][a-z_]{0,63}$")
    effect: Literal["positive", "negative", "neutral"]
    detail: str = Field(min_length=1, max_length=300)
    evidence: list[str] = Field(min_length=1, max_length=5)
    quote: str = Field(min_length=3, max_length=200)


class MatchResonanceOutput(_Strict):
    """What the model must return. Every field is required."""

    reasons: list[ResonanceReason] = Field(max_length=6)
    adjustment: int = Field(ge=-MAX_LLM_ADJUSTMENT, le=MAX_LLM_ADJUSTMENT)


# Model reasons must not touch these topics at all (the rules own stated attributes;
# nothing is ever inferred from text, names or photos).
_FORBIDDEN_TOPIC_RE = re.compile(
    r"\b(?:age|aged|years? old|yo|young|younger|older|ethnic\w*|race|racial|skin|"
    r"religio\w*|christian|muslim|jewish|catholic|hindu|buddhist|atheist|"
    r"gay|lesbian|bi(?:sexual)?|straight|queer|orientation|sexuality|trans|transgender|"
    r"health\w*|disab\w*|illness|diagnos\w*|pregnan\w*|weight|fat|skinny|"
    r"height|tall|body|attractive|pretty|handsome|good[- ]looking|appearance|photo\w*|"
    r"pictures?|"
    r"images?|selfies?|appears? to be|seems? to be)\b",
    re.IGNORECASE,
)
_RESERVED_FACTORS = {"dealbreaker", "content"}

_SYSTEM_PROMPT = (
    "You compare a dating profile with the user's dating intent and return JSON only. "
    "Rules: (1) Use only the text fields given. Every reason must cite at least one "
    "field ID from the list (bio, prompts/<i>, attributes/<name>) and quote an exact "
    "phrase from a cited field. (2) Never guess or mention age, ethnicity, race, "
    "religion, sexual orientation, gender identity, health, body or appearance, and "
    "never refer to photos; you are not given any. (3) Do not decide dealbreakers. "
    "(4) 'adjustment' is an integer from -15 to 15: how much the bio and prompts "
    "resonate with the user's intent. Unsupported reasons are discarded."
)


def _llm_payload(profile: ProfileInput, intent: IntentInput) -> dict[str, Any]:
    attrs = {
        k: v
        for k, v in usable_attributes(profile.attributes).items()
        if not _SENSITIVE_KEY_RE.search(k)
    }
    fields: list[dict[str, str]] = []
    if profile.bio and profile.bio.strip():
        fields.append({"id": "bio", "text": profile.bio})
    for i, p in enumerate(profile.prompts):
        fields.append(
            {"id": f"prompts/{i}", "text": f"{p.get('prompt', '')}\n{p.get('answer', '')}"}
        )
    for key, value in attrs.items():
        text = ", ".join(map(str, value)) if isinstance(value, list) else str(value)
        fields.append({"id": f"attributes/{key}", "text": text})
    return {
        "intent": intent.mode.value,
        "custom_goal": intent.custom_description,
        "fields": fields,
    }


def _quote_found(quote: str, texts: Sequence[str]) -> bool:
    q = _norm(quote)
    return bool(q) and any(q in _norm(t) for t in texts)


def _accept(reason: ResonanceReason, allowed: Mapping[str, str]) -> bool:
    if reason.factor in _RESERVED_FACTORS:
        return False
    if not all(e in allowed for e in reason.evidence):
        return False
    if not _quote_found(reason.quote, [allowed[e] for e in reason.evidence]):
        return False
    blob = f"{reason.factor} {reason.detail} {reason.quote}".replace("_", " ")
    return not _FORBIDDEN_TOPIC_RE.search(blob)


# --------------------------------------------------------------------------- evaluator


class MatchEvaluator:
    """Rules plus an optional model pass. ``gateway=None`` means rules only."""

    def __init__(self, gateway: LLMGateway | None = None) -> None:
        self.gateway = gateway

    @property
    def uses_model(self) -> bool:
        return self.gateway is not None

    async def evaluate(
        self,
        profile: ProfileInput | _ProfileLike,
        preferences: PreferenceInput | Any,
        intent: IntentInput | Any | None = None,
        *,
        user_id: str | None = None,
    ) -> EvaluationResult:
        p = ProfileInput.of(profile)
        prefs = PreferenceInput.of(preferences)
        it = _intent_of(intent, prefs)
        rules = evaluate_rules(p, prefs, it)
        base = EvaluationResult(
            score=rules.score,
            reasons=list(rules.reasons),
            known_unknowns=list(rules.known_unknowns),
            intent_mode=it.mode,
            dealbreakers_hit=list(rules.dealbreakers_hit),
            confidence=rules.confidence,
            evaluator_version=f"{EVALUATOR_VERSION}/rules",
        )
        # The model is not asked when a dealbreaker already excludes the profile or there
        # is nothing to read.
        if self.gateway is None or rules.dealbreakers_hit or not rules.has_content:
            return base
        payload = _llm_payload(p, it)
        allowed = {f["id"]: f["text"] for f in payload["fields"]}
        try:
            result = await self.gateway.generate(
                [
                    LLMMessage(role="system", content=_SYSTEM_PROMPT),
                    LLMMessage(
                        role="user",
                        content="Profile fields and user intent (JSON):\n"
                        + json.dumps(payload, ensure_ascii=False),
                    ),
                ],
                MatchResonanceOutput,
                operation="match.evaluate",
                user_id=user_id,
                temperature=0.0,
            )
        except LLMError as exc:
            logger.warning("match evaluation model pass failed error_class=%s", exc.error_class)
            return _replace(
                base,
                confidence=round(max(0.05, rules.confidence - LLM_FAILURE_PENALTY), 3),
                evaluator_version=f"{EVALUATOR_VERSION}/rules(model-failed)",
                llm_status=LLMStatus.FAILED,
            )
        output = result.output
        accepted = [r for r in output.reasons if _accept(r, allowed)]
        rejected = len(output.reasons) - len(accepted)
        if rejected:
            logger.info("match evaluation rejected %d model reason(s)", rejected)
        adjustment = 0.0
        if accepted and output.reasons:
            adjustment = output.adjustment * len(accepted) / len(output.reasons)
        reasons = list(base.reasons) + [
            {
                "factor": r.factor,
                "effect": r.effect,
                "detail": r.detail[:MAX_DETAIL],
                "evidence": list(r.evidence),
            }
            for r in accepted
        ]
        score = _clamp((rules.score or 0.0) + adjustment) if rules.score is not None else None
        bonus = LLM_CONFIDENCE_BONUS if accepted else LLM_CONFIDENCE_BONUS / 3
        return _replace(
            base,
            score=score,
            reasons=reasons,
            confidence=round(min(0.95, rules.confidence + bonus), 3),
            evaluator_version=f"{EVALUATOR_VERSION}/rules+model",
            llm_status=LLMStatus.USED,
            llm_reasons_rejected=rejected,
        )


def _replace(result: EvaluationResult, **changes: Any) -> EvaluationResult:
    return replace(result, **changes)


def input_fingerprint(
    profile: ProfileInput | _ProfileLike,
    preferences: PreferenceInput | Any,
    *,
    uses_model: bool,
) -> str:
    """Digest of everything an evaluation depends on. A stored evaluation with the same
    fingerprint is current; any change (profile content, preferences, rules version,
    model on/off) makes the review queue evaluate again, adding a row."""
    p = ProfileInput.of(profile)
    prefs = PreferenceInput.of(preferences)
    doc = {
        "rules": RULES_VERSION,
        "model": uses_model,
        "provider": p.provider,
        "bio": p.bio,
        "prompts": [dict(x) for x in p.prompts],
        "attributes": dict(p.attributes),
        "prefs_version": prefs.version,
        "intent": [prefs.intent.mode.value, prefs.intent.custom_description],
        "dealbreakers": list(prefs.dealbreakers),
    }
    raw = json.dumps(doc, sort_keys=True, ensure_ascii=False, default=str)
    return hashlib.sha256(raw.encode()).hexdigest()
