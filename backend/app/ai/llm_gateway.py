"""Provider-agnostic LLM gateway: the one entry point AI services call.

``LLMGateway.generate`` sends chat messages to a provider chain and returns a
validated instance of the requested Pydantic model, plus usage metadata. It owns:

* structured-output validation: malformed or schema-invalid output raises a typed
  ``LLMOutputError`` and is never coerced into a result;
* timeouts, bounded retries with exponential backoff (clock and sleep injectable),
  and error classification (see ``app.ai.providers.errors``);
* an optional fallback chain of providers;
* usage and cost accounting through a ``UsageSink``;
* metadata-only logging: prompt and completion text are never logged.

Model output is untrusted, advisory data. A validated result is a suggestion that the
automation controller (WING-011) still has to authorize; it is never permission.
The gateway is prompt-agnostic: prompts belong to the calling service.
"""

import asyncio
import json
import logging
import re
import time
import uuid
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime

from pydantic import BaseModel, ValidationError

from app.ai.providers.base import (
    LLMMessage,
    LLMProvider,
    ProviderRequest,
    ProviderResponse,
)
from app.ai.providers.errors import (
    LLMConfigError,
    LLMError,
    LLMOutputError,
    LLMRateLimitedError,
    LLMServerError,
    LLMTimeoutError,
    MalformedOutputError,
    SchemaValidationError,
)
from app.ai.providers.mock import MockHandler, MockLLMProvider
from app.ai.providers.settings import LLMSettings
from app.ai.providers.usage import (
    NullUsageSink,
    PricingTable,
    UnitRates,
    UsageRecord,
    UsageSink,
)
from app.config import Settings, get_settings

__all__ = [
    "LLMGateway",
    "LLMMessage",
    "LLMResult",
    "RetryPolicy",
    "build_gateway",
    "build_provider",
    "parse_structured_output",
]

logger = logging.getLogger(__name__)

Clock = Callable[[], float]
Sleep = Callable[[float], Awaitable[None]]

_SCHEMA_NAME_RE = re.compile(r"[^A-Za-z0-9_-]")


@dataclass(frozen=True)
class RetryPolicy:
    """Per-provider retry budget.

    ``max_attempts`` bounds attempts after transient errors (rate limit, timeout,
    server error). ``max_output_retries`` bounds extra attempts after malformed or
    schema-invalid output. Auth, config and invalid-request errors are never retried.
    """

    max_attempts: int = 3
    max_output_retries: int = 1
    backoff_base_s: float = 0.5
    backoff_max_s: float = 8.0
    backoff_multiplier: float = 2.0
    # Upper bound on a provider-supplied Retry-After we are willing to honour.
    max_retry_after_s: float = 30.0

    def __post_init__(self) -> None:
        if self.max_attempts < 1:
            raise ValueError("max_attempts must be >= 1")
        if self.max_output_retries < 0:
            raise ValueError("max_output_retries must be >= 0")

    def backoff(self, failures: int, error: LLMError) -> float:
        """Delay before the next attempt, after ``failures`` transient failures (>= 1)."""
        delay = min(
            self.backoff_max_s,
            self.backoff_base_s * self.backoff_multiplier ** (failures - 1),
        )
        if isinstance(error, LLMRateLimitedError) and error.retry_after is not None:
            delay = max(delay, min(error.retry_after, self.max_retry_after_s))
        return delay


@dataclass(frozen=True)
class LLMResult[T: BaseModel]:
    """A validated structured output and the usage of the call that produced it."""

    output: T
    provider: str
    model: str
    # Every attempt of this call, in order, including failed ones and fallbacks.
    attempts: tuple[UsageRecord, ...] = field(repr=False)

    @property
    def usage(self) -> UsageRecord:
        """The successful attempt."""
        return self.attempts[-1]

    @property
    def prompt_tokens(self) -> int:
        return sum(a.prompt_tokens for a in self.attempts)

    @property
    def completion_tokens(self) -> int:
        return sum(a.completion_tokens for a in self.attempts)


def parse_structured_output[T: BaseModel](
    content: str, output_type: type[T], *, refused: bool = False
) -> T:
    """Validate raw model text against ``output_type`` or raise ``LLMOutputError``.

    The text must be one JSON object, validated in strict mode: no string-to-number
    coercion, no stripping of code fences, no repair. Error messages carry field
    locations and error types only, never values from the output.
    """
    if refused:
        raise MalformedOutputError("model refused to answer")
    text = content.strip()
    if not text:
        raise MalformedOutputError("model returned no content")
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise MalformedOutputError("model output is not valid JSON") from exc
    if not isinstance(data, dict):
        raise MalformedOutputError(f"model output is a JSON {type(data).__name__}, not an object")
    try:
        return output_type.model_validate_json(text, strict=True)
    except ValidationError as exc:
        issues = tuple(
            f"{'.'.join(str(p) for p in err['loc']) or '<root>'}: {err['type']}"
            for err in exc.errors(include_input=False, include_url=False)
        )
        raise SchemaValidationError(
            f"model output failed {output_type.__name__} validation ({len(issues)} issue(s))",
            issues=issues,
        ) from None


def _schema_name(output_type: type[BaseModel]) -> str:
    return _SCHEMA_NAME_RE.sub("_", output_type.__name__)[:64] or "output"


class LLMGateway:
    def __init__(
        self,
        providers: LLMProvider | Sequence[LLMProvider],
        *,
        usage_sink: UsageSink | None = None,
        pricing: PricingTable | None = None,
        retry: RetryPolicy | None = None,
        timeout_s: float = 30.0,
        clock: Clock = time.monotonic,
        sleep: Sleep = asyncio.sleep,
        now: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        chain = [providers] if isinstance(providers, LLMProvider) else list(providers)
        if not chain:
            raise LLMConfigError("LLMGateway needs at least one provider")
        if timeout_s <= 0:
            raise ValueError("timeout_s must be > 0")
        self.providers: tuple[LLMProvider, ...] = tuple(chain)
        self.usage_sink: UsageSink = usage_sink or NullUsageSink()
        self.pricing = pricing or PricingTable()
        self.retry = retry or RetryPolicy()
        self.timeout_s = timeout_s
        self._clock = clock
        self._sleep = sleep
        self._now = now

    async def generate[T: BaseModel](
        self,
        messages: Sequence[LLMMessage],
        output_type: type[T],
        *,
        operation: str,
        user_id: str | None = None,
        max_output_tokens: int | None = None,
        temperature: float | None = None,
    ) -> LLMResult[T]:
        """Return a validated ``output_type`` instance or raise an ``LLMError``.

        ``operation`` is a short, non-sensitive label for usage records and logs
        (e.g. ``"memory.extract"``). ``user_id`` attributes cost; pass the opaque ID,
        never a name or phone number.
        """
        request = ProviderRequest(
            messages=tuple(messages),
            output_schema=output_type.model_json_schema(),
            schema_name=_schema_name(output_type),
            max_output_tokens=max_output_tokens,
            temperature=temperature,
        )
        call_id = uuid.uuid4().hex
        attempt = 0
        records: list[UsageRecord] = []
        last_error: LLMError | None = None

        for index, provider in enumerate(self.providers):
            if index > 0:
                logger.warning(
                    "llm fallback call_id=%s operation=%s from=%s to=%s error_class=%s",
                    call_id,
                    operation,
                    self.providers[index - 1].name,
                    provider.name,
                    last_error.error_class if last_error else None,
                )
            transient_failures = 0
            output_failures = 0
            while True:
                attempt += 1
                response, error, latency_ms = await self._attempt(provider, request)
                output: T | None = None
                if response is not None:
                    try:
                        output = parse_structured_output(
                            response.content, output_type, refused=response.refused
                        )
                    except LLMOutputError as exc:
                        exc.provider = provider.name
                        error = exc
                record = self._record(
                    call_id=call_id,
                    attempt=attempt,
                    operation=operation,
                    user_id=user_id,
                    provider=provider,
                    response=response,
                    error=error,
                    latency_ms=latency_ms,
                )
                records.append(record)
                await self._emit(record)
                self._log(record, error)

                if output is not None:
                    return LLMResult(
                        output=output,
                        provider=provider.name,
                        model=record.model,
                        attempts=tuple(records),
                    )
                if error is None:  # pragma: no cover - no output always means an error
                    raise RuntimeError("LLM attempt produced neither output nor error")
                last_error = error

                if isinstance(error, LLMOutputError):
                    output_failures += 1
                    if output_failures > self.retry.max_output_retries:
                        break
                    continue
                if error.retryable:
                    transient_failures += 1
                    if transient_failures >= self.retry.max_attempts:
                        break
                    await self._sleep(self.retry.backoff(transient_failures, error))
                    continue
                break

            if last_error is not None and not last_error.fallback_eligible:
                raise last_error

        if last_error is None:  # pragma: no cover - the chain is never empty
            raise LLMConfigError("LLMGateway has no providers")
        raise last_error

    async def _attempt(
        self, provider: LLMProvider, request: ProviderRequest
    ) -> tuple[ProviderResponse | None, LLMError | None, int]:
        started = self._clock()
        response: ProviderResponse | None = None
        error: LLMError | None = None
        try:
            response = await asyncio.wait_for(provider.complete(request), self.timeout_s)
        except TimeoutError:
            error = LLMTimeoutError(
                f"no response within {self.timeout_s:g}s", provider=provider.name
            )
        except LLMError as exc:
            if exc.provider is None:
                exc.provider = provider.name
            error = exc
        except Exception as exc:  # noqa: BLE001 - a buggy provider must not escape untyped
            # Only the type name: arbitrary exception text could contain prompt data.
            error = LLMServerError(
                f"unexpected provider failure: {type(exc).__name__}", provider=provider.name
            )
            error.__cause__ = exc
        latency_ms = max(0, round((self._clock() - started) * 1000))
        return response, error, latency_ms

    def _record(
        self,
        *,
        call_id: str,
        attempt: int,
        operation: str,
        user_id: str | None,
        provider: LLMProvider,
        response: ProviderResponse | None,
        error: LLMError | None,
        latency_ms: int,
    ) -> UsageRecord:
        # Tokens come only from what the provider reported. A failed HTTP call
        # reports none, so it is recorded with zero tokens and zero/unknown cost.
        prompt_tokens = response.prompt_tokens if response else 0
        completion_tokens = response.completion_tokens if response else 0
        model = response.model if response else str(getattr(provider, "model", "unknown"))
        return UsageRecord(
            call_id=call_id,
            attempt=attempt,
            operation=operation,
            user_id=user_id,
            provider=provider.name,
            model=model,
            outcome=error.error_class if error else "success",
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            latency_ms=latency_ms,
            cost_usd=self.pricing.cost(provider.name, model, prompt_tokens, completion_tokens),
            created_at=self._now(),
        )

    async def _emit(self, record: UsageRecord) -> None:
        try:
            await self.usage_sink.record(record)
        except Exception as exc:  # noqa: BLE001 - accounting must not fail the call
            logger.warning(
                "llm usage sink failed call_id=%s error=%s", record.call_id, type(exc).__name__
            )

    @staticmethod
    def _log(record: UsageRecord, error: LLMError | None) -> None:
        # Metadata only. Never add message content, completions or exception text
        # from outside this package here.
        level = logging.INFO if error is None else logging.WARNING
        logger.log(
            level,
            "llm call call_id=%s attempt=%d operation=%s provider=%s model=%s outcome=%s "
            "prompt_tokens=%d completion_tokens=%d latency_ms=%d status=%s",
            record.call_id,
            record.attempt,
            record.operation,
            record.provider,
            record.model,
            record.outcome,
            record.prompt_tokens,
            record.completion_tokens,
            record.latency_ms,
            error.status_code if error else None,
            extra={
                "llm_call_id": record.call_id,
                "llm_provider": record.provider,
                "llm_model": record.model,
                "llm_outcome": str(record.outcome),
                "llm_latency_ms": record.latency_ms,
            },
        )


def build_provider(
    settings: Settings | None = None,
    llm_settings: LLMSettings | None = None,
    *,
    mock_handler: MockHandler | None = None,
) -> LLMProvider:
    """Build the provider selected by ``WINGMAN_LLM_PROVIDER``.

    ``mock`` needs no key or network. ``openai`` uses the OpenAI-compatible HTTP
    provider configured by ``WINGMAN_LLM_*``. ``anthropic`` is not implemented yet.
    """
    settings = settings or get_settings()
    llm_settings = llm_settings or LLMSettings()
    if settings.llm_provider == "mock":
        return MockLLMProvider(handler=mock_handler)
    if settings.llm_provider == "openai":
        # Imported lazily so mock mode never touches the HTTP stack.
        from app.ai.providers.openai_compat import OpenAICompatibleProvider

        if llm_settings.api_key is None:
            raise LLMConfigError("WINGMAN_LLM_API_KEY is required for provider 'openai'")
        return OpenAICompatibleProvider(
            api_key=llm_settings.api_key.get_secret_value(),
            model=llm_settings.model,
            base_url=llm_settings.base_url,
            timeout_s=llm_settings.timeout_s,
            strict_json_schema=llm_settings.strict_json_schema,
        )
    raise LLMConfigError(f"LLM provider '{settings.llm_provider}' is not implemented")


def build_gateway(
    settings: Settings | None = None,
    llm_settings: LLMSettings | None = None,
    *,
    usage_sink: UsageSink | None = None,
    fallbacks: Sequence[LLMProvider] = (),
    mock_handler: MockHandler | None = None,
) -> LLMGateway:
    """Build a gateway from environment settings. Raises ``LLMConfigError`` if invalid."""
    llm_settings = llm_settings or LLMSettings()
    primary = build_provider(settings, llm_settings, mock_handler=mock_handler)
    pricing = PricingTable()
    if llm_settings.input_usd_per_1m is not None and llm_settings.output_usd_per_1m is not None:
        # Provider-wide key so dated model names the endpoint reports are priced too.
        pricing = PricingTable(
            {
                f"{primary.name}:*": UnitRates(
                    input_per_1m=llm_settings.input_usd_per_1m,
                    output_per_1m=llm_settings.output_usd_per_1m,
                )
            }
        )
    return LLMGateway(
        [primary, *fallbacks],
        usage_sink=usage_sink,
        pricing=pricing,
        retry=RetryPolicy(
            max_attempts=llm_settings.max_attempts,
            max_output_retries=llm_settings.max_output_retries,
            backoff_base_s=llm_settings.backoff_base_s,
            backoff_max_s=llm_settings.backoff_max_s,
        ),
        timeout_s=llm_settings.timeout_s,
    )
