"""Golden fixtures: style controls visibly change the draft (``golden/styles.json``).

Two layers are pinned:

* **Prompt directives.** Each configured style attribute writes exactly one directive
  into the prompt; unset attributes write nothing (a neutral default is used and no
  user style is invented). ``response_pace`` never reaches the prompt: it becomes
  ``suggested_delay_seconds``.
* **Post-generation caps.** The same raw model draft goes through different profiles
  and comes out differently (emoji and punctuation caps applied in code; too long for
  the brevity setting is flagged, not cut).

The "style-following" model below is a deterministic mock keyed on the directives it
finds in the prompt. It stands in for a model that follows them; real-model quality is
UNVERIFIED.
"""

import json
from collections.abc import Callable
from functools import cache
from typing import Any

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.prompts import NEUTRAL_STYLE, style_directives
from app.ai.providers import ProviderRequest
from app.ai.reply_generator import DraftDecision, count_emoji
from app.db.enums import ApprovalReason
from app.services.preferences import StyleProfile
from tests.ai.replies.conftest import (
    GOLDEN,
    Harness,
    assert_contract,
    contact_says,
    make_thread,
    make_user,
    preferences,
    reply,
    set_preferences,
    user_says,
)


@cache
def golden() -> dict[str, Any]:
    data: dict[str, Any] = json.loads((GOLDEN / "styles.json").read_text())
    return data


ALL_DIRECTIVES = {
    line for case in golden()["style_following_cases"] for line in case["expected_directives"]
}


def directives_in(prompt: str) -> tuple[str, ...]:
    return tuple(
        line[2:]
        for line in prompt.splitlines()
        if line.startswith("- ") and line[2:] in ALL_DIRECTIVES
    )


def style_following_model() -> Callable[[ProviderRequest], dict[str, Any]]:
    table = {
        tuple(c["expected_directives"]): c["expected_text"]
        for c in golden()["style_following_cases"]
    }

    def handler(request: ProviderRequest) -> dict[str, Any]:
        found = directives_in(request.messages[-1].content)
        return reply(table[found], message_refs=["m1"])

    return handler


async def _thread(session: AsyncSession, style: dict[str, Any]):  # type: ignore[no-untyped-def]
    scope = await make_user(session)
    await set_preferences(session, scope, preferences(style=style))
    thread = await make_thread(session, scope)
    await contact_says(session, thread, golden()["conversation"][0]["text"])
    return thread


@pytest.mark.parametrize("case", golden()["style_following_cases"], ids=lambda c: c["name"])
async def test_style_following_golden(
    session: AsyncSession, make_harness: Callable[..., Harness], case: dict[str, Any]
) -> None:
    h = make_harness(handler=style_following_model())
    thread = await _thread(session, case["style"])

    draft = await h.generator.generate_draft(session, thread.scope, thread.conversation_id)

    prompt = h.prompt()
    assert list(directives_in(prompt)) == case["expected_directives"]
    if not case["expected_directives"]:
        assert NEUTRAL_STYLE in prompt
    else:
        assert NEUTRAL_STYLE not in prompt
    assert draft.decision is DraftDecision.SEND_CANDIDATE
    assert draft.text == case["expected_text"]
    assert_contract("Draft", draft.to_contract())


async def test_style_following_outputs_all_differ(
    session: AsyncSession, make_harness: Callable[..., Harness]
) -> None:
    texts = set()
    for case in golden()["style_following_cases"]:
        h = make_harness(handler=style_following_model())
        thread = await _thread(session, case["style"])
        draft = await h.generator.generate_draft(session, thread.scope, thread.conversation_id)
        texts.add(draft.text)
    assert len(texts) == len(golden()["style_following_cases"])


@pytest.mark.parametrize("case", golden()["raw_cases"], ids=lambda c: c["name"])
async def test_same_model_draft_differs_by_style(
    session: AsyncSession, harness: Harness, case: dict[str, Any]
) -> None:
    thread = await _thread(session, case["style"])
    await user_says(session, thread, "hey!")  # not a first message
    harness.provider.push(reply(golden()["raw_model_text"], message_refs=["m1"]))

    draft = await harness.generator.generate_draft(session, thread.scope, thread.conversation_id)

    assert draft.decision is DraftDecision.SEND_CANDIDATE
    assert draft.text == case["expected_text"]
    assert [v.code for v in draft.violations] == case["expected_violations"]
    assert (ApprovalReason.LOW_CONFIDENCE in draft.approval_reasons) is case[
        "expected_low_confidence"
    ]
    style = StyleProfile.model_validate(case["style"])
    if style.emoji_frequency is not None and style.emoji_frequency.value == "none":
        assert count_emoji(draft.text or "") == 0
    if style.punctuation is not None and style.punctuation.value == "minimal":
        assert "!" not in (draft.text or "")
    assert_contract("Draft", draft.to_contract())


async def test_too_long_for_brevity_is_flagged_not_cut(
    session: AsyncSession, harness: Harness
) -> None:
    thread = await _thread(session, {"brevity": "very_short"})
    await user_says(session, thread, "hey!")
    long_text = "That sounds like an amazing hike, I hope the view at the top made up for it."
    harness.provider.push(reply(long_text, message_refs=["m1"]))

    draft = await harness.generator.generate_draft(session, thread.scope, thread.conversation_id)

    assert draft.text == long_text  # never truncated (could change the meaning)
    assert [v.code for v in draft.violations] == ["style_too_long"]
    assert ApprovalReason.LOW_CONFIDENCE in draft.approval_reasons


def test_unset_attributes_write_no_directive() -> None:
    assert style_directives(StyleProfile()) == []
    only_tone = style_directives(StyleProfile(tone="warm"))  # type: ignore[arg-type]
    assert only_tone == ["Tone: warm and friendly."]
    # response_pace is timing, not wording.
    assert style_directives(StyleProfile(response_pace="slow")) == []  # type: ignore[arg-type]


async def test_response_pace_becomes_a_bounded_delay(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    await set_preferences(
        session,
        scope,
        preferences(style={"response_pace": "slow"}, reply_delay={"min": 60, "max": 600}),
    )
    thread = await make_thread(session, scope)
    await contact_says(session, thread, "hey there")
    harness.provider.push(reply("hey! how's your day going?"))

    draft = await harness.generator.generate_draft(session, thread.scope, thread.conversation_id)

    assert draft.suggested_delay_seconds == 600  # 3600 clamped to the user's max
    assert "response_pace" not in harness.prompt() and "slow" not in harness.prompt()


async def test_no_pace_set_means_no_delay_invented(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    thread = await make_thread(session, scope)
    await contact_says(session, thread, "hey there")
    harness.provider.push(reply("hey! how's your day going?"))

    draft = await harness.generator.generate_draft(session, thread.scope, thread.conversation_id)

    assert draft.suggested_delay_seconds is None
    assert NEUTRAL_STYLE in harness.prompt()


async def test_style_samples_are_shown_as_voice_examples(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    await set_preferences(
        session, scope, preferences(style={"style_samples": ["lol no way, where was that"]})
    )
    thread = await make_thread(session, scope)
    await contact_says(session, thread, "hey there")
    harness.provider.push(reply("hey! how's your day going?"))

    await harness.generator.generate_draft(session, thread.scope, thread.conversation_id)

    prompt = harness.prompt()
    assert "> lol no way, where was that" in prompt
    assert "do not invent one" in prompt
