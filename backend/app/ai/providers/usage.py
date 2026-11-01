"""Token, latency and cost accounting.

The gateway emits one ``UsageRecord`` per provider attempt (successful or not) to a
``UsageSink``. Cost is computed only from the token counts the provider reported and
the configured unit rates. With no rate configured for a model, ``cost_usd`` is
``None`` (unknown), never an invented number. Storage belongs to WING-022, so this
module only defines the record, the sink protocol and an in-memory sink.
"""

from collections.abc import Mapping
from datetime import datetime
from decimal import Decimal
from typing import Protocol, runtime_checkable

from pydantic import BaseModel, ConfigDict, Field

from app.ai.providers.errors import LLMErrorClass

_PER_TOKENS = Decimal(1_000_000)


class UnitRates(BaseModel):
    """USD per one million tokens."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    input_per_1m: Decimal = Field(ge=0)
    output_per_1m: Decimal = Field(ge=0)

    def cost(self, prompt_tokens: int, completion_tokens: int) -> Decimal:
        return (
            Decimal(prompt_tokens) * self.input_per_1m
            + Decimal(completion_tokens) * self.output_per_1m
        ) / _PER_TOKENS


class PricingTable:
    """Looks up rates by ``"provider:model"``, ``"model"``, ``"provider:*"``, then ``default``.

    ``"provider:*"`` covers dated model variants the endpoint reports back
    (e.g. ``gpt-4o-mini-2024-07-18`` for a request to ``gpt-4o-mini``).
    """

    def __init__(
        self,
        rates: Mapping[str, UnitRates] | None = None,
        *,
        default: UnitRates | None = None,
    ) -> None:
        self._rates = dict(rates or {})
        self._default = default

    def rates_for(self, provider: str, model: str) -> UnitRates | None:
        for key in (f"{provider}:{model}", model, f"{provider}:*"):
            if key in self._rates:
                return self._rates[key]
        return self._default

    def cost(
        self, provider: str, model: str, prompt_tokens: int, completion_tokens: int
    ) -> Decimal | None:
        rates = self.rates_for(provider, model)
        return None if rates is None else rates.cost(prompt_tokens, completion_tokens)


class UsageRecord(BaseModel):
    """Metadata about one provider attempt. Contains no prompt or completion text."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    call_id: str  # one per ``LLMGateway.generate`` call; shared by its attempts
    attempt: int = Field(ge=1)  # 1-based across the whole call, including fallbacks
    operation: str  # caller label, e.g. "memory.extract", "reply.generate"
    user_id: str | None
    provider: str
    model: str
    # "success", or the ``LLMErrorClass`` that ended this attempt.
    outcome: LLMErrorClass | str
    prompt_tokens: int = Field(ge=0)
    completion_tokens: int = Field(ge=0)
    latency_ms: int = Field(ge=0)
    cost_usd: Decimal | None
    created_at: datetime

    @property
    def total_tokens(self) -> int:
        return self.prompt_tokens + self.completion_tokens


@runtime_checkable
class UsageSink(Protocol):
    async def record(self, record: UsageRecord) -> None: ...


class InMemoryUsageSink:
    """Collects records in a list. For tests and local development."""

    def __init__(self) -> None:
        self.records: list[UsageRecord] = []

    async def record(self, record: UsageRecord) -> None:
        self.records.append(record)

    def total_cost_usd(self) -> Decimal:
        """Sum of known costs. Records with unknown cost are excluded, not guessed."""
        return sum((r.cost_usd for r in self.records if r.cost_usd is not None), Decimal(0))

    def total_tokens(self) -> int:
        return sum(r.total_tokens for r in self.records)


class NullUsageSink:
    async def record(self, record: UsageRecord) -> None:
        return None
