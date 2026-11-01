"""I-021 dev papercut: the deterministic dev mock-LLM handler used by
``app.worker.dev_server`` / ``scripts/dev-run.sh``.

* With it, the real pipeline (memory, analysis, reply generation, policy) produces a
  clearly labeled draft without any scripting.
* It refuses ``WINGMAN_ENV=prod``.
* The test suite's ``MockLLMProvider`` without a handler is unchanged (fails closed).
"""

import uuid
from typing import Any

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.analyzer import ConversationAnalyzer
from app.ai.llm_gateway import LLMGateway, RetryPolicy, build_gateway
from app.ai.memory import MemoryService
from app.ai.providers import LLMConfigError, MockLLMProvider
from app.ai.providers.base import LLMMessage, ProviderRequest
from app.ai.providers.dev_mock import (
    MOCK_LABEL,
    DevMockRefusedError,
    build_dev_mock_handler,
    dev_mock_answer,
)
from app.ai.reply_generator import ReplyGenerator
from app.api._automation_common import build_services
from app.config import get_settings
from app.db.enums import ApprovalStatus, DraftDecision
from app.db.models import Approval, Conversation, Draft
from app.services.automation import AutomationController
from tests.automation.conftest import (  # noqa: F401
    make_client,
    make_world,
    migrated_db_url,
    pg_server_url,
    session,
)
from tests.automation.test_device_routes import post_event


@pytest.fixture(autouse=True)
def _fresh_settings() -> Any:
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _request(schema: str, content: str) -> ProviderRequest:
    return ProviderRequest(
        messages=(
            LLMMessage(role="system", content="rules"),
            LLMMessage(role="user", content=content),
        ),
        output_schema={},
        schema_name=schema,
    )


def test_refuses_prod(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WINGMAN_ENV", "prod")
    get_settings.cache_clear()
    with pytest.raises(DevMockRefusedError):
        build_dev_mock_handler()


@pytest.mark.parametrize("env", ["dev", "test"])
def test_available_outside_prod(monkeypatch: pytest.MonkeyPatch, env: str) -> None:
    monkeypatch.setenv("WINGMAN_ENV", env)
    get_settings.cache_clear()
    assert build_dev_mock_handler() is dev_mock_answer


def test_dev_server_refuses_prod_before_building_anything() -> None:
    """The dev server module is the only place that installs the handler, and it refuses
    to import with ``WINGMAN_ENV=prod`` (covered as a subprocess in test_process.py);
    the production app factory never references the handler."""
    import ast
    from pathlib import Path

    app_dir = Path(__file__).resolve().parents[2] / "app"
    users = []
    for path in app_dir.rglob("*.py"):
        if path.name == "dev_mock.py":
            continue
        for node in ast.walk(ast.parse(path.read_text())):
            if isinstance(node, ast.ImportFrom) and node.module == "app.ai.providers.dev_mock":
                users.append(path.relative_to(app_dir).as_posix())
    assert users == ["worker/dev_server.py"]


async def test_suite_mock_provider_still_fails_closed() -> None:
    provider = MockLLMProvider()
    with pytest.raises(LLMConfigError):
        await provider.complete(_request("ReplyOutput", "x"))
    # build_gateway without a handler is unchanged too.
    gateway = build_gateway()
    assert isinstance(gateway.providers[0], MockLLMProvider)


def test_answers_are_deterministic_and_labeled() -> None:
    content = (
        "Today is 2026-11-02.\n\nLatest messages, oldest first (JSON):\n"
        '[{"ref": "m1", "from": "contact", "sent_at": "x", "text": "hi"},'
        ' {"ref": "m2", "from": "user", "sent_at": "x", "text": "hey"},'
        ' {"ref": "m3", "from": "contact", "sent_at": "x", "text": "how are you?"}]'
    )
    reply = dev_mock_answer(_request("ReplyOutput", content))
    assert reply == dev_mock_answer(_request("ReplyOutput", content))
    assert isinstance(reply, dict)
    assert reply["decision"] == "send_candidate"
    assert reply["text"].startswith(MOCK_LABEL)
    assert reply["message_refs"] == ["m3"]
    assert reply["commitment"] == "none" and reply["escalation"] is False
    assert reply["user_claims"] == []

    analysis = dev_mock_answer(
        _request("AnalysisOutput", "Current stage: rapport.\n\n" + content.split("\n\n", 1)[1])
    )
    assert isinstance(analysis, dict)
    assert analysis["stage"] == "rapport"
    assert analysis["interest_signals"][0]["source_ref"] == "m3"
    assert dev_mock_answer(_request("ExtractionOutput", content)) == {"facts": []}
    summary = dev_mock_answer(_request("SummaryOutput", content))
    assert isinstance(summary, dict) and summary["summary"].startswith(MOCK_LABEL)
    # Nothing to reply to: no_send, never an invented opener.
    empty = dev_mock_answer(_request("ReplyOutput", "Latest messages: none yet."))
    assert isinstance(empty, dict) and empty["decision"] == "no_send" and empty["text"] is None
    # Unknown schema: rejected by the gateway's validation, never invented.
    assert dev_mock_answer(_request("SomethingElse", content)) == "{}"


async def test_pipeline_produces_a_labeled_draft_without_scripting(
    session: AsyncSession,  # noqa: F811
    make_client,  # noqa: F811
) -> None:  # type: ignore[no-untyped-def]
    """The real memory/analysis/reply pipeline on the dev handler: an assisted user gets a
    pending approval holding the labeled mock draft (policy still applies)."""
    world = await make_world(session, mode="assisted")

    async def no_sleep(_: float) -> None:
        return None

    gateway = LLMGateway(
        MockLLMProvider(handler=build_dev_mock_handler()),
        retry=RetryPolicy(max_attempts=1, max_output_retries=0),
        sleep=no_sleep,
    )
    memory = MemoryService(gateway, now=world.clock)
    controller = AutomationController(
        world.dispatcher,
        generator=ReplyGenerator(gateway, memory=memory, now=world.clock),
        analyzer=ConversationAnalyzer(gateway, memory=memory, now=world.clock),
        memory=memory,
        now=world.clock,
    )
    world.services = build_services(controller=controller)
    client = make_client(world)
    resp = await post_event(client, world, world.event("Just got back from Lisbon!"))
    assert resp.status_code == 202, resp.text

    draft = await session.scalar(select(Draft).where(Draft.user_id == world.scope.user_id))
    assert draft is not None
    assert draft.decision is DraftDecision.SEND_CANDIDATE
    assert draft.text is not None and draft.text.startswith(MOCK_LABEL)
    approval = await session.scalar(select(Approval).where(Approval.user_id == world.scope.user_id))
    assert approval is not None and approval.status is ApprovalStatus.PENDING
    conv = await session.get(Conversation, approval.conversation_id)
    assert conv is not None and conv.analysis is not None
    assert uuid.UUID(conv.analysis["source_message_ids"][0])
    assert world.connector is not None and world.connector.transport_log == []  # nothing sent
