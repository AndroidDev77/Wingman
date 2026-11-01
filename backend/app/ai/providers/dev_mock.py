"""DEV ONLY: a deterministic handler for :class:`MockLLMProvider` so a plain local run
(``scripts/dev-run.sh``) produces drafts, analyses and memory updates without a model
key and without scripting (I-021 dev papercut).

Every answer is **clearly labeled mock output** and deliberately bland:

* ``ReplyOutput``: a ``send_candidate`` whose text starts with :data:`MOCK_LABEL`, cites
  the latest contact message, states no fact about the user, proposes nothing and
  never escalates; ``confidence`` 0.75. With no contact message it returns ``no_send``.
* ``AnalysisOutput``: keeps the current stage, one ``neutral`` signal on the latest
  contact message, ``next_action: reply``.
* ``ExtractionOutput``: no facts (inventing facts about a real contact would be worse
  than none).
* ``SummaryOutput``: a labeled one-line summary.

It is only a stand-in for a model: everything it returns still goes through the same
gateway validation, the WING-010 guardrails and the WING-011 policy (an ``assisted``
draft still needs approval). It refuses to be built when ``WINGMAN_ENV=prod``, and the
test suite's :class:`MockLLMProvider` (no handler: every call is ``LLMConfigError``) is
unchanged; only ``app.worker.dev_server`` installs it.
"""

import json
import re
from typing import Any

from app.ai.providers.base import ProviderRequest
from app.ai.providers.mock import MockHandler, MockStep
from app.config import get_settings

__all__ = ["MOCK_LABEL", "DevMockRefusedError", "build_dev_mock_handler", "dev_mock_answer"]

MOCK_LABEL = "[dev mock]"

_STAGE_RE = re.compile(r"^Current stage: ([a-z_]+)\.$", re.MULTILINE)
_JSON_MARKERS = ("(JSON):\n",)


class DevMockRefusedError(RuntimeError):
    """The dev mock handler was requested with ``WINGMAN_ENV=prod``."""


def _messages_payload(request: ProviderRequest) -> list[dict[str, Any]]:
    """The labeled message list (``[{"ref", "from", "text", ...}]``) the prompt carries,
    or ``[]``. Parsed from the last ``... (JSON):`` block of the last user message."""
    users = [m.content for m in request.messages if m.role == "user"]
    if not users:
        return []
    content = users[-1]
    for marker in _JSON_MARKERS:
        at = content.rfind(marker)
        if at < 0:
            continue
        try:
            data = json.loads(content[at + len(marker) :])
        except ValueError:
            return []
        if isinstance(data, list):
            return [d for d in data if isinstance(d, dict)]
    return []


def _latest_contact_ref(payload: list[dict[str, Any]]) -> str | None:
    for item in reversed(payload):
        ref = item.get("ref")
        if item.get("from") == "contact" and isinstance(ref, str):
            return ref
    return None


def _reply(request: ProviderRequest) -> dict[str, Any]:
    ref = _latest_contact_ref(_messages_payload(request))
    base: dict[str, Any] = {
        "memory_refs": [],
        "user_claims": [],
        "commitment": "none",
        "escalation": False,
        "interest_evidence_refs": [],
        "question_for_user": None,
    }
    if ref is None:
        return base | {
            "decision": "no_send",
            "text": None,
            "rationale": f"{MOCK_LABEL} No message from the contact to reply to.",
            "confidence": 0.5,
            "message_refs": [],
        }
    return base | {
        "decision": "send_candidate",
        "text": f"{MOCK_LABEL} Thanks for your message! Tell me more?",
        "rationale": f"{MOCK_LABEL} Deterministic local reply; not written by a model.",
        "confidence": 0.75,
        "message_refs": [ref],
    }


def _analysis(request: ProviderRequest) -> dict[str, Any]:
    users = [m.content for m in request.messages if m.role == "user"]
    found = _STAGE_RE.search(users[-1]) if users else None
    stage = found.group(1) if found else "new"
    ref = _latest_contact_ref(_messages_payload(request))
    signals = (
        [{"signal": f"{MOCK_LABEL} contact replied", "polarity": "neutral", "source_ref": ref}]
        if ref is not None
        else []
    )
    return {
        "stage": stage,
        "confidence": 0.7,
        "interest_signals": signals,
        "disengagement": "none",
        "disengagement_ref": None,
        "agreement": None,
        "ambiguities": [],
        "next_action": "reply",
    }


def dev_mock_answer(request: ProviderRequest) -> MockStep:
    """Deterministic answer for one request, chosen by the requested output schema."""
    name = request.schema_name
    if name == "ReplyOutput":
        return _reply(request)
    if name == "AnalysisOutput":
        return _analysis(request)
    if name == "ExtractionOutput":
        return {"facts": []}
    if name == "SummaryOutput":
        count = len(_messages_payload(request))
        return {"summary": f"{MOCK_LABEL} Conversation summary placeholder ({count} new)."}
    # Unknown schema: a string the gateway rejects as schema-invalid (typed failure),
    # never an invented answer.
    return "{}"


def build_dev_mock_handler() -> MockHandler:
    """The dev handler, or :class:`DevMockRefusedError` when ``WINGMAN_ENV=prod``."""
    if get_settings().env == "prod":
        raise DevMockRefusedError("the dev mock LLM handler is never used with WINGMAN_ENV=prod")
    return dev_mock_answer
