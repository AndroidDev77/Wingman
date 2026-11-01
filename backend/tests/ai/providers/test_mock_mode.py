"""Mock mode and configuration wiring. Runs with every WINGMAN_* variable removed."""

import os
from decimal import Decimal

import pytest
from pydantic import SecretStr

from app.ai.llm_gateway import build_gateway, build_provider
from app.ai.providers import (
    InMemoryUsageSink,
    LLMConfigError,
    LLMMessage,
    MockLLMProvider,
    ProviderRequest,
)
from app.ai.providers.openai_compat import OpenAICompatibleProvider
from app.ai.providers.settings import LLMSettings
from app.config import Settings
from tests.ai.providers.conftest import Verdict


@pytest.fixture(autouse=True)
def clean_env(monkeypatch: pytest.MonkeyPatch, tmp_path: os.PathLike[str]) -> None:
    for key in list(os.environ):
        if key.startswith("WINGMAN_") or key.endswith("_API_KEY"):
            monkeypatch.delenv(key, raising=False)
    monkeypatch.chdir(tmp_path)  # no stray .env file


async def test_mock_mode_works_with_no_env_vars() -> None:
    def handler(request: ProviderRequest) -> Verdict:
        return Verdict(decision="ask_user", confidence=0.2)

    sink = InMemoryUsageSink()
    gateway = build_gateway(usage_sink=sink, mock_handler=handler)
    assert isinstance(gateway.providers[0], MockLLMProvider)
    result = await gateway.generate(
        [LLMMessage(role="user", content="hi")], Verdict, operation="test.mock"
    )
    assert result.output == Verdict(decision="ask_user", confidence=0.2)
    assert sink.records[0].cost_usd is None


async def test_unscripted_mock_refuses_to_invent_output() -> None:
    gateway = build_gateway()
    with pytest.raises(LLMConfigError):
        await gateway.generate([LLMMessage(role="user", content="hi")], Verdict, operation="t")


def test_mock_is_default_provider() -> None:
    assert isinstance(build_provider(), MockLLMProvider)


def test_openai_without_key_is_config_error() -> None:
    with pytest.raises(LLMConfigError):
        build_provider(Settings(llm_provider="openai"))


def test_anthropic_not_implemented_is_config_error() -> None:
    with pytest.raises(LLMConfigError):
        build_provider(Settings(llm_provider="anthropic"))


def test_llm_settings_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WINGMAN_LLM_API_KEY", "sk-test")
    monkeypatch.setenv("WINGMAN_LLM_MODEL", "my-model")
    monkeypatch.setenv("WINGMAN_LLM_MAX_ATTEMPTS", "5")
    monkeypatch.setenv("WINGMAN_LLM_INPUT_USD_PER_1M", "0.15")
    monkeypatch.setenv("WINGMAN_LLM_OUTPUT_USD_PER_1M", "0.60")
    s = LLMSettings()
    assert s.api_key is not None and s.api_key.get_secret_value() == "sk-test"
    assert "sk-test" not in repr(s)
    assert s.max_attempts == 5
    assert s.input_usd_per_1m == Decimal("0.15")


def test_build_openai_gateway_from_settings() -> None:
    llm = LLMSettings(
        api_key=SecretStr("sk-test"),
        model="m",
        max_attempts=4,
        input_usd_per_1m=Decimal(2),
        output_usd_per_1m=Decimal(8),
    )
    gateway = build_gateway(Settings(llm_provider="openai"), llm)
    provider = gateway.providers[0]
    assert isinstance(provider, OpenAICompatibleProvider)
    assert gateway.retry.max_attempts == 4
    # Dated variants the endpoint reports back are priced by the provider-wide rate.
    assert gateway.pricing.cost("openai", "m-2025-01-01", 1_000_000, 1_000_000) == Decimal(10)
