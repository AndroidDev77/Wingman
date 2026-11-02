"""WING-019 fixtures: the contract 1.3.0 test world and HTTP app (real PostgreSQL,
rolled back per test), plus helpers for preferences, imports and scripted model passes."""

import uuid
from typing import Any

from app.ai.llm_gateway import LLMGateway, RetryPolicy
from app.ai.match_evaluator import IntentInput, MatchEvaluator, PreferenceInput
from app.ai.providers import InMemoryUsageSink, MockLLMProvider
from app.ai.providers.mock import MockStep
from app.db.enums import IntentMode
from tests.integrations.conftest import (  # noqa: F401
    alembic_config,
    create_scratch_database,
    drop_scratch_database,
    make_v13_client,
    migrated_db_url,
    pg_server_url,
    session,
)


def prefs(
    mode: IntentMode = IntentMode.SERIOUS,
    dealbreakers: tuple[str, ...] = (),
    *,
    version: int = 1,
    custom: str | None = None,
) -> PreferenceInput:
    return PreferenceInput(
        version=version, intent=IntentInput(mode, custom), dealbreakers=dealbreakers
    )


def scripted_gateway(*steps: MockStep) -> tuple[LLMGateway, MockLLMProvider]:
    async def no_sleep(_: float) -> None:
        return None

    provider = MockLLMProvider(steps)
    gateway = LLMGateway(
        provider,
        usage_sink=InMemoryUsageSink(),
        retry=RetryPolicy(max_attempts=1, max_output_retries=0),
        sleep=no_sleep,
    )
    return gateway, provider


def scripted_evaluator(*steps: MockStep) -> tuple[MatchEvaluator, MockLLMProvider]:
    gateway, provider = scripted_gateway(*steps)
    return MatchEvaluator(gateway), provider


def import_body(**kw: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "provider": "hinge",
        "external_profile_id": f"hinge-{uuid.uuid4().hex[:12]}",
        "source": "manual",
        "display_name": "Fictional Tester",
        "bio": "Trail runner who loves jazz.",
        "prompts": [{"prompt": "Typical Sunday", "answer": "Long run, then brunch"}],
        "attributes": {"looking_for": "Long-term relationship", "smoking": "No"},
        "source_observed_at": "2026-11-02T14:00:00Z",
    }
    body.update(kw)
    return body
