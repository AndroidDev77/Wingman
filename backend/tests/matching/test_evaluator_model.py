"""WING-019 optional model pass: strict schema, evidence-cited reasons only, forbidden
topics rejected, bounded adjustment, and the rules-only fallback on model failure."""

import json
from typing import Any

import pytest

from app.ai.llm_gateway import build_gateway
from app.ai.match_evaluator import LLMStatus, MatchEvaluator, ProfileInput, evaluate_rules
from app.ai.providers.dev_mock import MOCK_LABEL, dev_mock_answer
from app.ai.providers.errors import LLMServerError, LLMTimeoutError
from app.db.enums import IntentMode
from tests.matching.conftest import prefs, scripted_evaluator

P = ProfileInput(
    provider="hinge",
    bio="Trail runner who loves jazz and slow Sunday mornings.",
    prompts=({"prompt": "Typical Sunday", "answer": "Long run, then brunch with friends"},),
    attributes={
        "looking_for": "Long-term relationship",
        "interests": ["running", "jazz"],
        "age": 31,
        "religion": "Buddhist",
        "photos": ["https://cdn.invalid/1.jpg"],
    },
)


def reason(**kw: Any) -> dict[str, Any]:
    out: dict[str, Any] = {
        "factor": "shared_interests",
        "effect": "positive",
        "detail": "Both enjoy running.",
        "evidence": ["attributes/interests"],
        "quote": "running",
    }
    out.update(kw)
    return out


async def test_valid_model_reasons_are_added_with_bounded_adjustment() -> None:
    evaluator, provider = scripted_evaluator(
        {
            "reasons": [
                reason(),
                reason(
                    factor="lifestyle",
                    detail="Values slow weekends, which suits a long-term pace.",
                    evidence=["bio"],
                    quote="slow Sunday mornings",
                ),
            ],
            "adjustment": 10,
        }
    )
    rules = evaluate_rules(P, prefs())
    result = await evaluator.evaluate(P, prefs())
    assert result.llm_status is LLMStatus.USED and result.llm_reasons_rejected == 0
    assert result.score == (rules.score or 0) + 10
    assert result.confidence > rules.confidence
    assert result.evaluator_version.endswith("/rules+model")
    model_reasons = result.reasons[len(rules.reasons) :]
    assert [r["evidence"] for r in model_reasons] == [["attributes/interests"], ["bio"]]
    assert all("quote" not in r for r in result.reasons)  # contract shape only
    assert provider.calls == 1


@pytest.mark.parametrize(
    "bad",
    [
        reason(evidence=["photos/0"], quote="running"),  # not a field
        reason(evidence=["prompts/3"], quote="running"),  # out of range
        reason(evidence=["attributes/height_cm"]),  # not provided
        reason(evidence=["attributes/photos"], quote="cdn"),  # image: never citable
        reason(evidence=["bio"], quote="loves marathons"),  # quote not in the field
        reason(evidence=["display_name"], quote="Riley"),  # names are not evidence
        reason(factor="dealbreaker", effect="negative"),  # model cannot hit dealbreakers
        reason(detail="Seems to be in their early 30s.", evidence=["bio"], quote="Trail runner"),
        reason(detail="Probably Buddhist, which fits.", evidence=["bio"], quote="Trail runner"),
        reason(detail="Looks attractive in photos.", evidence=["bio"], quote="Trail runner"),
        reason(factor="age_match", evidence=["bio"], quote="Trail runner"),
    ],
)
async def test_invalid_model_reasons_are_rejected(bad: dict[str, Any]) -> None:
    evaluator, _ = scripted_evaluator({"reasons": [bad], "adjustment": 15})
    rules = evaluate_rules(P, prefs())
    result = await evaluator.evaluate(P, prefs())
    assert result.llm_reasons_rejected == 1
    assert result.reasons == rules.reasons  # nothing added
    assert result.score == rules.score  # no unsupported adjustment
    assert result.dealbreakers_hit == []


async def test_partly_rejected_output_scales_adjustment() -> None:
    evaluator, _ = scripted_evaluator(
        {"reasons": [reason(), reason(evidence=["photos/0"])], "adjustment": -10}
    )
    rules = evaluate_rules(P, prefs())
    result = await evaluator.evaluate(P, prefs())
    assert result.llm_reasons_rejected == 1
    assert result.score == (rules.score or 0) - 5


@pytest.mark.parametrize(
    "failure",
    [
        LLMServerError("boom"),
        LLMTimeoutError("slow"),
        "not json",
        json.dumps({"reasons": [], "adjustment": 99}),  # schema-invalid (bound)
        json.dumps({"reasons": [reason(quote="x" * 1000)], "adjustment": 0}),
        json.dumps({"reasons": [], "adjustment": 0, "extra": True}),
    ],
)
async def test_model_failure_falls_back_to_rules_with_lower_confidence(failure: Any) -> None:
    evaluator, _ = scripted_evaluator(failure)
    rules = evaluate_rules(P, prefs())
    result = await evaluator.evaluate(P, prefs())
    assert result.llm_status is LLMStatus.FAILED
    assert result.score == rules.score and result.reasons == rules.reasons
    assert result.confidence < rules.confidence
    assert "model-failed" in result.evaluator_version


async def test_model_not_called_for_dealbreaker_hits_or_empty_profiles() -> None:
    evaluator, provider = scripted_evaluator()
    hit = await evaluator.evaluate(
        ProfileInput("hinge", "Bio", (), {"smoking": "Yes"}), prefs(dealbreakers=("smoking",))
    )
    empty = await evaluator.evaluate(ProfileInput("hinge", None, (), {}), prefs())
    assert hit.excluded and empty.score is None and provider.calls == 0


async def test_model_payload_has_no_images_names_or_sensitive_attributes() -> None:
    evaluator, provider = scripted_evaluator({"reasons": [], "adjustment": 0})
    await evaluator.evaluate(P, prefs(IntentMode.CUSTOM, custom="Hiking partner"))
    user = [m.content for m in provider.requests[0].messages if m.role == "user"][-1]
    payload = json.loads(user.split("(JSON):\n", 1)[1])
    ids = {f["id"] for f in payload["fields"]}
    assert ids == {"bio", "prompts/0", "attributes/looking_for", "attributes/interests"}
    assert payload["intent"] == "custom" and payload["custom_goal"] == "Hiking partner"
    assert "cdn.invalid" not in user and "Buddhist" not in user
    assert provider.requests[0].schema_name == "MatchResonanceOutput"


async def test_dev_mock_answer_is_deterministic_and_passes_the_checks() -> None:
    gateway = build_gateway(mock_handler=dev_mock_answer)
    a = await MatchEvaluator(gateway).evaluate(P, prefs())
    b = await MatchEvaluator(gateway).evaluate(P, prefs())
    assert a == b and a.llm_status is LLMStatus.USED and a.llm_reasons_rejected == 0
    added = a.reasons[len(evaluate_rules(P, prefs()).reasons) :]
    assert len(added) == 1 and added[0]["detail"].startswith(MOCK_LABEL)
    assert added[0]["evidence"] == ["bio"]
    no_bio = await MatchEvaluator(gateway).evaluate(
        ProfileInput("hinge", None, (), {"looking_for": "Long-term"}), prefs()
    )
    assert no_bio.llm_status is LLMStatus.USED and no_bio.llm_reasons_rejected == 0
