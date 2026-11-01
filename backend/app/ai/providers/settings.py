"""Provider settings (env prefix ``WINGMAN_LLM_``).

Kept beside the providers so WING-005 does not edit ``app/config.py``. Which provider
to use still comes from ``Settings.llm_provider`` (``WINGMAN_LLM_PROVIDER``). The
integrator may later fold these fields into ``app.config.Settings``.
"""

from decimal import Decimal

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class LLMSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="WINGMAN_LLM_", env_file=".env", extra="ignore")

    # Required for real providers; never needed in mock mode.
    api_key: SecretStr | None = None
    # Any OpenAI-compatible chat completions endpoint (OpenAI, Azure-compatible
    # proxies, vLLM, Ollama, LiteLLM, ...).
    base_url: str = "https://api.openai.com/v1"
    model: str = "gpt-4o-mini"
    timeout_s: float = Field(default=30.0, gt=0, le=600)
    # Attempts per provider for transient errors (rate limit, timeout, 5xx).
    max_attempts: int = Field(default=3, ge=1, le=10)
    # Extra attempts per provider after malformed or schema-invalid output.
    max_output_retries: int = Field(default=1, ge=0, le=3)
    backoff_base_s: float = Field(default=0.5, ge=0)
    backoff_max_s: float = Field(default=8.0, ge=0)
    # USD per 1M tokens for ``model``. Unset means cost is recorded as unknown.
    input_usd_per_1m: Decimal | None = Field(default=None, ge=0)
    output_usd_per_1m: Decimal | None = Field(default=None, ge=0)
    # Ask the endpoint to enforce the schema (OpenAI ``strict``). Needs schemas with
    # ``additionalProperties: false`` and every field required.
    strict_json_schema: bool = False
