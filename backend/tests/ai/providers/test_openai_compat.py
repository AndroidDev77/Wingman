"""OpenAI-compatible HTTP provider, tested with httpx.MockTransport only. No network."""

import json
from collections.abc import Callable
from decimal import Decimal
from typing import Any

import httpx
import pytest

from app.ai.llm_gateway import LLMGateway, RetryPolicy
from app.ai.providers import (
    InMemoryUsageSink,
    LLMAuthError,
    LLMConfigError,
    LLMErrorClass,
    LLMInvalidRequestError,
    LLMRateLimitedError,
    LLMServerError,
    LLMTimeoutError,
    MalformedOutputError,
    PricingTable,
    SchemaValidationError,
    UnitRates,
)
from app.ai.providers.openai_compat import OpenAICompatibleProvider
from tests.ai.providers.conftest import MESSAGES, FakeTime, Verdict

Handler = Callable[[httpx.Request], httpx.Response]


def completion(content: str | None, *, refusal: str | None = None) -> dict[str, Any]:
    return {
        "id": "chatcmpl-1",
        "model": "test-model-2025",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": content, "refusal": refusal},
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150},
    }


def make_provider(handler: Handler, **kwargs: Any) -> OpenAICompatibleProvider:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return OpenAICompatibleProvider(
        api_key="sk-test",
        model="test-model",
        base_url="https://llm.example.invalid/v1/",
        client=client,
        **kwargs,
    )


def make_gateway(provider: OpenAICompatibleProvider, sink: InMemoryUsageSink) -> LLMGateway:
    fake = FakeTime()
    return LLMGateway(
        provider,
        usage_sink=sink,
        pricing=PricingTable(
            {"openai:*": UnitRates(input_per_1m=Decimal(1), output_per_1m=Decimal(4))}
        ),
        retry=RetryPolicy(max_attempts=3, max_output_retries=0),
        clock=fake.clock,
        sleep=fake.sleep,
    )


async def test_request_shape_and_success() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=completion('{"decision": "ok", "confidence": 0.5}'))

    sink = InMemoryUsageSink()
    provider = make_provider(handler, strict_json_schema=True)
    result = await make_gateway(provider, sink).generate(
        MESSAGES, Verdict, operation="t", max_output_tokens=200, temperature=0.3
    )

    assert result.output == Verdict(decision="ok", confidence=0.5)
    assert result.model == "test-model-2025"
    req = seen[0]
    assert req.method == "POST"
    assert str(req.url) == "https://llm.example.invalid/v1/chat/completions"
    assert req.headers["authorization"] == "Bearer sk-test"
    body = json.loads(req.content)
    assert body["model"] == "test-model"
    assert body["messages"][1] == {"role": "user", "content": "Should we reply?"}
    assert body["max_tokens"] == 200
    assert body["temperature"] == 0.3
    fmt = body["response_format"]
    assert fmt["type"] == "json_schema"
    assert fmt["json_schema"]["name"] == "Verdict"
    assert fmt["json_schema"]["strict"] is True
    assert fmt["json_schema"]["schema"]["required"] == ["decision", "confidence"]

    record = sink.records[0]
    assert (record.provider, record.prompt_tokens, record.completion_tokens) == ("openai", 120, 30)
    # 120 * 1/1M + 30 * 4/1M
    assert record.cost_usd == Decimal("0.00024")


@pytest.mark.parametrize(
    ("status", "error_type", "error_class"),
    [
        (401, LLMAuthError, LLMErrorClass.AUTH_ERROR),
        (403, LLMAuthError, LLMErrorClass.AUTH_ERROR),
        (404, LLMConfigError, LLMErrorClass.CONFIG_ERROR),
        (400, LLMInvalidRequestError, LLMErrorClass.INVALID_REQUEST),
        (422, LLMInvalidRequestError, LLMErrorClass.INVALID_REQUEST),
    ],
)
async def test_non_retryable_status_codes(
    status: int, error_type: type[Exception], error_class: LLMErrorClass
) -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        # Error bodies can echo the prompt; they must not reach the exception.
        return httpx.Response(status, json={"error": {"message": "echo: Should we reply?"}})

    with pytest.raises(error_type) as exc_info:
        await make_gateway(make_provider(handler), InMemoryUsageSink()).generate(
            MESSAGES, Verdict, operation="t"
        )
    assert calls == 1
    assert exc_info.value.error_class is error_class  # type: ignore[attr-defined]
    assert exc_info.value.status_code == status  # type: ignore[attr-defined]
    assert "Should we reply?" not in str(exc_info.value)


@pytest.mark.parametrize(
    ("status", "error_class"),
    [
        (429, LLMErrorClass.RATE_LIMITED),
        (500, LLMErrorClass.SERVER_ERROR),
        (503, LLMErrorClass.SERVER_ERROR),
        (408, LLMErrorClass.TIMEOUT),
    ],
)
async def test_retryable_status_then_success(status: int, error_class: LLMErrorClass) -> None:
    responses = [
        httpx.Response(status, headers={"retry-after": "2"}),
        httpx.Response(200, json=completion('{"decision": "ok", "confidence": 1}')),
    ]

    def handler(request: httpx.Request) -> httpx.Response:
        return responses.pop(0)

    sink = InMemoryUsageSink()
    result = await make_gateway(make_provider(handler), sink).generate(
        MESSAGES, Verdict, operation="t"
    )
    assert result.output.decision == "ok"
    assert [r.outcome for r in sink.records] == [error_class, "success"]


async def test_retry_after_header_parsed() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, headers={"retry-after": "7"})

    with pytest.raises(LLMRateLimitedError) as exc_info:
        await make_provider(handler).complete(_request())
    assert exc_info.value.retry_after == 7.0


async def test_transport_timeout_maps_to_timeout_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timed out", request=request)

    with pytest.raises(LLMTimeoutError):
        await make_provider(handler).complete(_request())


async def test_connection_error_maps_to_server_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(LLMServerError):
        await make_provider(handler).complete(_request())


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(200, content=b"<html>gateway</html>"),
        httpx.Response(200, json={"choices": []}),
        httpx.Response(200, json={"choices": [{"message": {"content": "{}"}}]}),  # no usage
    ],
)
async def test_bad_envelope_is_server_error(response: httpx.Response) -> None:
    with pytest.raises(LLMServerError):
        await make_provider(lambda request: response).complete(_request())


@pytest.mark.parametrize(
    ("body", "error_type"),
    [
        (completion("I think you should reply!"), MalformedOutputError),
        (completion(None), MalformedOutputError),
        (completion(None, refusal="I can't help with that."), MalformedOutputError),
        (completion('{"decision": "ok", "confidence": 3}'), SchemaValidationError),
    ],
)
async def test_bad_model_output_rejected_and_still_billed(
    body: dict[str, Any], error_type: type[Exception]
) -> None:
    sink = InMemoryUsageSink()
    with pytest.raises(error_type):
        await make_gateway(make_provider(lambda r: httpx.Response(200, json=body)), sink).generate(
            MESSAGES, Verdict, operation="t"
        )
    # The tokens were consumed, so the failed attempt is still recorded with its cost.
    assert sink.records[0].prompt_tokens == 120
    assert sink.records[0].cost_usd == Decimal("0.00024")


def test_missing_api_key_rejected() -> None:
    with pytest.raises(LLMConfigError):
        OpenAICompatibleProvider(api_key="", model="m")


async def test_owned_client_closes() -> None:
    provider = OpenAICompatibleProvider(api_key="k", model="m")
    await provider.aclose()


def _request() -> Any:
    from app.ai.providers import ProviderRequest

    return ProviderRequest(
        messages=tuple(MESSAGES),
        output_schema=Verdict.model_json_schema(),
        schema_name="Verdict",
    )
