from collections.abc import Callable
from dataclasses import dataclass, field

import pytest
from pydantic import BaseModel, ConfigDict, Field

from app.ai.llm_gateway import LLMGateway, RetryPolicy
from app.ai.providers import InMemoryUsageSink, LLMMessage, LLMProvider, PricingTable


class Verdict(BaseModel):
    """A stand-in for a downstream structured output (e.g. a draft decision)."""

    model_config = ConfigDict(extra="forbid")

    decision: str
    confidence: float = Field(ge=0, le=1)


@dataclass
class FakeTime:
    """Instant, deterministic clock and sleep. Records every requested sleep."""

    now: float = 1000.0
    sleeps: list[float] = field(default_factory=list)

    def clock(self) -> float:
        return self.now

    async def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds


@pytest.fixture
def fake_time() -> FakeTime:
    return FakeTime()


@pytest.fixture
def sink() -> InMemoryUsageSink:
    return InMemoryUsageSink()


GatewayFactory = Callable[..., LLMGateway]


@pytest.fixture
def make_gateway(fake_time: FakeTime, sink: InMemoryUsageSink) -> GatewayFactory:
    def factory(
        *providers: LLMProvider,
        retry: RetryPolicy | None = None,
        pricing: PricingTable | None = None,
        timeout_s: float = 5.0,
    ) -> LLMGateway:
        return LLMGateway(
            list(providers),
            usage_sink=sink,
            pricing=pricing,
            retry=retry or RetryPolicy(max_attempts=3, max_output_retries=1),
            timeout_s=timeout_s,
            clock=fake_time.clock,
            sleep=fake_time.sleep,
        )

    return factory


MESSAGES = [
    LLMMessage(role="system", content="Return a verdict as JSON."),
    LLMMessage(role="user", content="Should we reply?"),
]
