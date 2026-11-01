"""Vendor-neutral request/response types and the ``LLMProvider`` protocol.

Downstream code (memory, analyzer, reply generator) depends on these types and on
``app.ai.llm_gateway`` only, never on a vendor SDK or a concrete provider.
"""

from typing import Any, Literal, Protocol, runtime_checkable

from pydantic import BaseModel, ConfigDict, Field


class LLMMessage(BaseModel):
    """One chat message. ``content`` is excluded from ``repr`` so it cannot leak into logs."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    role: Literal["system", "user", "assistant"]
    content: str = Field(repr=False)


class ProviderRequest(BaseModel):
    """What the gateway hands a provider for one attempt."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    messages: tuple[LLMMessage, ...] = Field(min_length=1)
    # JSON Schema of the requested output (from ``output_type.model_json_schema()``).
    output_schema: dict[str, Any]
    # ``[A-Za-z0-9_-]{1,64}``, derived from the Pydantic model name.
    schema_name: str
    max_output_tokens: int | None = Field(default=None, gt=0)
    temperature: float | None = Field(default=None, ge=0, le=2)


class ProviderResponse(BaseModel):
    """Raw provider answer: untrusted text plus usage. The gateway validates it."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    content: str = Field(repr=False)
    model: str
    prompt_tokens: int = Field(ge=0)
    completion_tokens: int = Field(ge=0)
    # The model declined to answer. The gateway treats this as malformed output.
    refused: bool = False


@runtime_checkable
class LLMProvider(Protocol):
    """An async model backend. Implementations raise ``LLMError`` subclasses only."""

    @property
    def name(self) -> str: ...

    async def complete(self, request: ProviderRequest) -> ProviderResponse: ...
