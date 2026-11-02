"""WING-019 deterministic rules: dealbreakers, intent, evidence, known unknowns and the
no-inference guarantee (adversarial fixtures). Pure: no database, no model."""

import re
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest

from app.ai.match_evaluator import (
    EvaluationResult,
    MatchEvaluator,
    ProfileInput,
    evaluate_rules,
    input_fingerprint,
    usable_attributes,
    valid_evidence,
)
from app.db.enums import IntentMode
from app.services.profiles import synthetic_profile_feed
from tests.automation.conftest import assert_contract
from tests.matching.conftest import prefs

OBSERVED = datetime(2026, 11, 2, 14, 0, tzinfo=UTC)


def profile(
    *, bio: str | None = "Runner.", prompts: list[dict[str, str]] | None = None, **attrs: Any
) -> ProfileInput:
    return ProfileInput(provider="hinge", bio=bio, prompts=tuple(prompts or ()), attributes=attrs)


async def evaluate(p: ProfileInput, pr: Any = None) -> EvaluationResult:
    return await MatchEvaluator().evaluate(p, pr or prefs())


def as_contract(result: EvaluationResult) -> dict[str, Any]:
    return {
        "id": str(uuid.uuid4()),
        "profile_id": str(uuid.uuid4()),
        "score": result.score,
        "reasons": result.reasons,
        "known_unknowns": result.known_unknowns,
        "intent_mode": result.intent_mode.value,
        "dealbreakers_hit": result.dealbreakers_hit,
        "confidence": result.confidence,
        "evaluator_version": result.evaluator_version,
        "created_at": "2026-11-02T15:00:00Z",
    }


def feed_profiles() -> list[ProfileInput]:
    return [
        ProfileInput(
            provider=b["provider"],
            bio=b["bio"],
            prompts=tuple(b["prompts"]),
            attributes=b["attributes"],
        )
        for b in synthetic_profile_feed(OBSERVED)
    ]


# --------------------------------------------------------------------------- dealbreakers


async def test_dealbreaker_hit_excludes_with_reason_and_evidence() -> None:
    result = await evaluate(profile(smoking="Socially"), prefs(dealbreakers=("smoking",)))
    assert result.excluded and result.dealbreakers_hit == ["smoking"]
    assert result.score == 0
    reason = next(r for r in result.reasons if r["factor"] == "dealbreaker")
    assert reason["effect"] == "negative" and reason["evidence"] == ["attributes/smoking"]
    assert result.confidence >= 0.9


async def test_dealbreaker_cleared_and_unknown() -> None:
    cleared = await evaluate(profile(smoking="Never"), prefs(dealbreakers=("Smoking",)))
    assert cleared.dealbreakers_hit == [] and "smoking" not in cleared.known_unknowns
    assert any(r["factor"] == "dealbreaker" and r["effect"] == "positive" for r in cleared.reasons)
    unknown = await evaluate(profile(), prefs(dealbreakers=("smoking",)))
    assert unknown.dealbreakers_hit == [] and "smoking" in unknown.known_unknowns
    undisclosed = await evaluate(
        profile(smoking="Prefer not to say"), prefs(dealbreakers=("smoking",))
    )
    assert undisclosed.dealbreakers_hit == []
    assert any(u.startswith("smoking") for u in undisclosed.known_unknowns)


@pytest.mark.parametrize(
    ("dealbreaker", "age", "hit"),
    [
        ("under 25", 24, True),
        ("under 25", 25, False),
        ("over 40", 41, True),
        ("age > 40", 40, False),
    ],
)
async def test_age_bounds_use_only_the_stated_age(dealbreaker: str, age: int, hit: bool) -> None:
    result = await evaluate(profile(age=age), prefs(dealbreakers=(dealbreaker,)))
    assert bool(result.dealbreakers_hit) is hit
    assert any(r["evidence"] == ["attributes/age"] for r in result.reasons)


async def test_generic_and_unrecognized_dealbreakers() -> None:
    result = await evaluate(
        profile(has_tattoos=True, vegan=False),
        prefs(dealbreakers=("has tattoos", "vegan", "lives far away")),
    )
    assert result.dealbreakers_hit == ["has tattoos"]
    assert "lives far away" in result.known_unknowns
    kids = await evaluate(profile(has_kids="Yes"), prefs(dealbreakers=("has kids",)))
    assert kids.dealbreakers_hit == ["has kids"]
    no_kids = await evaluate(
        profile(wants_kids="Don't want"), prefs(dealbreakers=("doesn't want kids",))
    )
    assert no_kids.dealbreakers_hit == ["doesn't want kids"]


# --------------------------------------------------------------------------- intent and score


@pytest.mark.parametrize(
    ("mode", "looking_for", "effect"),
    [
        (IntentMode.SERIOUS, "Long-term relationship", "positive"),
        (IntentMode.SERIOUS, "Something casual", "negative"),
        (IntentMode.CASUAL_DATING, "Short-term, open to long", "positive"),
        (IntentMode.CASUAL_PHYSICAL, "Life partner", "negative"),
        (IntentMode.OPEN, "Figuring out my dating goals", "positive"),
    ],
)
async def test_intent_compared_with_stated_intent(
    mode: IntentMode, looking_for: str, effect: str
) -> None:
    result = await evaluate(profile(looking_for=looking_for), prefs(mode))
    reason = next(r for r in result.reasons if r["factor"] == "intent")
    assert reason["effect"] == effect and reason["evidence"] == ["attributes/looking_for"]
    assert result.intent_mode is mode


async def test_intent_unknown_and_scoring_order() -> None:
    unknown = await evaluate(profile(), prefs(IntentMode.SERIOUS))
    assert "relationship intent" in unknown.known_unknowns
    fits = await evaluate(profile(looking_for="Long-term relationship"), prefs())
    conflicts = await evaluate(profile(looking_for="Hookups"), prefs())
    assert fits.score is not None and unknown.score is not None and conflicts.score is not None
    assert fits.score > unknown.score > conflicts.score
    assert fits.confidence > unknown.confidence  # more of what matters is known


async def test_empty_profile_is_unscored() -> None:
    result = await evaluate(profile(bio=None), prefs())
    assert result.score is None and result.confidence <= 0.1
    assert_contract("Evaluation", as_contract(result))


async def test_custom_intent_is_neutral_without_model() -> None:
    result = await evaluate(
        profile(looking_for="Long-term"), prefs(IntentMode.CUSTOM, custom="Travel buddy")
    )
    reason = next(r for r in result.reasons if r["factor"] == "intent")
    assert reason["effect"] == "neutral" and result.intent_mode is IntentMode.CUSTOM


# --------------------------------------------------------------------------- evidence


@pytest.mark.parametrize(
    "pr",
    [
        prefs(),
        prefs(IntentMode.CASUAL_DATING, ("smoking", "drinking", "under 30")),
        prefs(IntentMode.OPEN, ("has kids", "something odd")),
    ],
)
async def test_every_rule_reason_cites_real_fields(pr: Any) -> None:
    for p in feed_profiles():
        result = await MatchEvaluator().evaluate(p, pr)
        assert_contract("Evaluation", as_contract(result))
        for reason in result.reasons:
            if reason["evidence"]:
                assert valid_evidence(p, reason["evidence"]), reason
            else:  # only "nothing stated" reasons may cite nothing
                assert reason["effect"] == "neutral", reason


def test_valid_evidence_rejects_missing_and_image_fields() -> None:
    p = profile(
        age=30, photos=["https://cdn.invalid/1.jpg"], prompts=[{"prompt": "a", "answer": "b"}]
    )
    assert valid_evidence(p, ["bio", "prompts/0", "attributes/age"])
    for bad in (["prompts/1"], ["attributes/photos"], ["attributes/height_cm"], ["photos/0"], []):
        assert not valid_evidence(p, bad), bad
    assert not valid_evidence(profile(bio="  "), ["bio"])


# --------------------------------------------------------------------------- no inference

# Adversarial fixtures: the text and the photos "say" things the structured attributes
# do not. Nothing may be inferred from them.
ADVERSARIAL = [
    profile(
        bio="I'm 24, Catholic, bi, and type 1 diabetic. Smoker since college lol.",
        prompts=[{"prompt": "About me", "answer": "Just turned 24! Proud Latina."}],
        photos=["https://cdn.invalid/a.jpg", "https://cdn.invalid/b.jpg"],
        photo_caption="me at my 24th birthday",
        main_image="https://cdn.invalid/main.jpg",
    ),
    profile(
        bio="Looking for something serious. 6'2\". Non-smoker. Wants kids someday.",
        prompts=[{"prompt": "Dealbreakers", "answer": "Smokers, under 25s"}],
        profile_picture_url="data:image/png;base64,AAAA",
    ),
]


@pytest.mark.parametrize("p", ADVERSARIAL)
async def test_no_attribute_is_inferred_from_text_or_photos(p: ProfileInput) -> None:
    pr = prefs(IntentMode.SERIOUS, ("under 25", "smoking", "wants kids"))
    result = await MatchEvaluator().evaluate(p, pr)
    # The text states age, smoking, kids and intent; the attributes do not: unknown.
    assert result.dealbreakers_hit == []
    assert {"age", "smoking", "wants children", "relationship intent"} <= set(result.known_unknowns)
    # Rules never cite free text or images.
    for reason in result.reasons:
        assert all(e.startswith("attributes/") for e in reason["evidence"]), reason
        assert not any("photo" in e or "image" in e or "picture" in e for e in reason["evidence"])
    blob = " ".join(r["detail"] for r in result.reasons).lower()
    for word in ("catholic", "latina", "diabetic", "bi,", "24", "6'2", "smoker since"):
        assert word not in blob
    # Images are dropped before scoring.
    assert not any(re.search("photo|image|picture", k) for k in usable_attributes(p.attributes))


async def test_stated_attributes_are_used_as_stated() -> None:
    # The same facts as structured attributes are used, with the attribute as evidence.
    p = profile(bio="I'm 24 and Catholic", age=31, smoking="No")
    result = await MatchEvaluator().evaluate(p, prefs(dealbreakers=("under 25", "smoking")))
    assert result.dealbreakers_hit == [] and "age" not in result.known_unknowns
    assert {"attributes/age", "attributes/smoking"} <= {
        e for r in result.reasons for e in r["evidence"]
    }


# --------------------------------------------------------------------------- determinism


async def test_rules_and_fingerprint_are_deterministic() -> None:
    pr = prefs(IntentMode.CASUAL_DATING, ("smoking",))
    for p in feed_profiles():
        a, b = await MatchEvaluator().evaluate(p, pr), await MatchEvaluator().evaluate(p, pr)
        assert a == b
        assert evaluate_rules(p, pr) == evaluate_rules(p, pr)
        assert input_fingerprint(p, pr, uses_model=False) == input_fingerprint(
            p, pr, uses_model=False
        )
    p = feed_profiles()[0]
    base = input_fingerprint(p, pr, uses_model=False)
    assert input_fingerprint(p, prefs(IntentMode.SERIOUS, ("smoking",)), uses_model=False) != base
    assert input_fingerprint(p, pr, uses_model=True) != base


def test_synthetic_feed_is_deterministic_fictional_and_contract_valid() -> None:
    a, b = synthetic_profile_feed(OBSERVED), synthetic_profile_feed(OBSERVED)
    assert a == b and len(a) >= 5
    for body in a:
        assert_contract("ProfileImportRequest", body)
        assert body["provider"] == "mock" and body["source"] == "fixture"
        assert "(fictional)" in body["display_name"]
        assert not any(re.search("photo|image|picture", k) for k in body["attributes"])
