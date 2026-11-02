"""E2E ONLY (WING-032): ``app.worker.dev_server`` with ONE scripted extension of the dev mock LLM.

The cross-platform suite (``tests/e2e/platforms``) needs a model answer the deterministic dev
mock never gives: a reply that proposes or confirms a date, so the suite can show that date
commitments always need approval (WING-018, dates and calendar, is not built). Everything else
is answered by the unchanged dev handler (:func:`app.ai.providers.dev_mock.dev_mock_answer`).

Scripted answers are triggered only by a marker in the **latest contact message** and are
labeled ``[e2e scripted]``:

* ``[e2e:propose-date]``: a ``send_candidate`` that proposes meeting (``commitment: proposal``).
* ``[e2e:confirm-date]``: a ``send_candidate`` that agrees to a plan (``commitment:
  confirmation``); the WING-010 guardrails turn it into ``ask_user`` because the user's
  availability is not grounded.

The scripted text still goes through the gateway validation, the WING-010 guardrails and the
WING-011 policy, exactly like a real model's. Nothing in ``backend/`` is changed: this module
only replaces the handler factory that ``dev_server.dev_gateway`` looks up, before the app's
lifespan builds the pipeline. Refuses ``WINGMAN_ENV=prod`` (``dev_server`` itself refuses too).
"""

from __future__ import annotations

import json
from typing import Any

import app.worker.dev_server as dev_server
from app.ai.providers.base import ProviderRequest
from app.ai.providers.dev_mock import build_dev_mock_handler, dev_mock_answer
from app.ai.providers.mock import MockHandler, MockStep
from app.config import get_settings

SCRIPTED_LABEL = "[e2e scripted]"
PROPOSE_MARKER = "[e2e:propose-date]"
CONFIRM_MARKER = "[e2e:confirm-date]"

if get_settings().env == "prod":  # pragma: no cover - guard
    raise RuntimeError("the E2E scripted server is for tests only (WINGMAN_ENV=prod)")


def _latest_contact(request: ProviderRequest) -> tuple[str, str] | None:
    """``(ref, text)`` of the latest contact message in the prompt's JSON block, if any."""
    users = [m.content for m in request.messages if m.role == "user"]
    if not users:
        return None
    at = users[-1].rfind("(JSON):\n")
    if at < 0:
        return None
    try:
        data = json.loads(users[-1][at + len("(JSON):\n") :])
    except ValueError:
        return None
    if not isinstance(data, list):
        return None
    for item in reversed(data):
        if isinstance(item, dict) and item.get("from") == "contact" and isinstance(item.get("ref"), str):
            return item["ref"], str(item.get("text") or "")
    return None


def _scripted_reply(ref: str, text: str, commitment: str) -> dict[str, Any]:
    return {
        "decision": "send_candidate",
        "text": text,
        "rationale": f"{SCRIPTED_LABEL} Deterministic E2E reply; not written by a model.",
        "confidence": 0.8,
        "message_refs": [ref],
        "memory_refs": [],
        "user_claims": [],
        "commitment": commitment,
        "escalation": False,
        "interest_evidence_refs": [],
        "question_for_user": None,
    }


def scripted_answer(request: ProviderRequest) -> MockStep:
    if request.schema_name == "ReplyOutput":
        latest = _latest_contact(request)
        if latest is not None:
            ref, text = latest
            if PROPOSE_MARKER in text:
                return _scripted_reply(ref, f"{SCRIPTED_LABEL} How about we grab coffee this weekend?", "proposal")
            if CONFIRM_MARKER in text:
                return _scripted_reply(ref, f"{SCRIPTED_LABEL} That works for me, see you then.", "confirmation")
    return dev_mock_answer(request)


def build_scripted_handler() -> MockHandler:
    build_dev_mock_handler()  # same prod refusal as the dev handler
    return scripted_answer


# dev_server.dev_gateway() resolves this name when the lifespan builds the pipeline.
dev_server.build_dev_mock_handler = build_scripted_handler  # type: ignore[assignment]
app = dev_server.app
