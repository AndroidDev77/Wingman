"""LLM provider protocol, implementations, errors and usage accounting.

Import the gateway from ``app.ai.llm_gateway``. Concrete vendor providers are
selected by configuration; downstream services should not import them directly.
"""

from app.ai.providers.base import LLMMessage, LLMProvider, ProviderRequest, ProviderResponse
from app.ai.providers.errors import (
    LLMAuthError,
    LLMConfigError,
    LLMError,
    LLMErrorClass,
    LLMInvalidRequestError,
    LLMOutputError,
    LLMRateLimitedError,
    LLMServerError,
    LLMTimeoutError,
    MalformedOutputError,
    SchemaValidationError,
)
from app.ai.providers.mock import MockLLMProvider, MockReply
from app.ai.providers.usage import (
    InMemoryUsageSink,
    NullUsageSink,
    PricingTable,
    UnitRates,
    UsageRecord,
    UsageSink,
)

__all__ = [
    "InMemoryUsageSink",
    "LLMAuthError",
    "LLMConfigError",
    "LLMError",
    "LLMErrorClass",
    "LLMInvalidRequestError",
    "LLMMessage",
    "LLMOutputError",
    "LLMProvider",
    "LLMRateLimitedError",
    "LLMServerError",
    "LLMTimeoutError",
    "MalformedOutputError",
    "MockLLMProvider",
    "MockReply",
    "NullUsageSink",
    "PricingTable",
    "ProviderRequest",
    "ProviderResponse",
    "SchemaValidationError",
    "UnitRates",
    "UsageRecord",
    "UsageSink",
]
