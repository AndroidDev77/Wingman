import pytest
from pydantic import ValidationError

from app.config import Settings


def test_defaults_use_mock_llm() -> None:
    assert Settings().llm_provider == "mock"


def test_invalid_env_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WINGMAN_ENV", "staging-ish")
    with pytest.raises(ValidationError):
        Settings()


def test_invalid_llm_provider_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WINGMAN_LLM_PROVIDER", "made-up-vendor")
    with pytest.raises(ValidationError):
        Settings()
