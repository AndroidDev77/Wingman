import logging
from decimal import Decimal

import pytest

from app.ai.llm_gateway import RetryPolicy, parse_structured_output
from app.ai.providers import (
    LLMAuthError,
    LLMConfigError,
    LLMErrorClass,
    LLMInvalidRequestError,
    LLMMessage,
    LLMRateLimitedError,
    LLMServerError,
    LLMTimeoutError,
    MalformedOutputError,
    MockLLMProvider,
    MockReply,
    PricingTable,
    SchemaValidationError,
    UnitRates,
)
from app.ai.providers.usage import InMemoryUsageSink
from tests.ai.providers.conftest import MESSAGES, FakeTime, GatewayFactory, Verdict

GOOD = {"decision": "send_candidate", "confidence": 0.8}


async def test_valid_output_is_returned_with_usage(
    make_gateway: GatewayFactory, sink: InMemoryUsageSink
) -> None:
    provider = MockLLMProvider([MockReply('{"decision": "no_send", "confidence": 0.4}', 10, 5)])
    result = await make_gateway(provider).generate(
        MESSAGES, Verdict, operation="test.op", user_id="u-1"
    )
    assert result.output == Verdict(decision="no_send", confidence=0.4)
    assert result.provider == "mock"
    assert result.model == "mock-model"
    assert (result.prompt_tokens, result.completion_tokens) == (10, 5)
    assert len(sink.records) == 1
    record = sink.records[0]
    assert record.outcome == "success"
    assert record.operation == "test.op"
    assert record.user_id == "u-1"
    # The provider was told what schema to produce.
    request = provider.requests[0]
    assert request.schema_name == "Verdict"
    assert request.output_schema["properties"]["confidence"]["maximum"] == 1


@pytest.mark.parametrize(
    "content",
    [
        "not json at all",
        '{"decision": "send_candidate", "confidence": 0.8',  # truncated
        '```json\n{"decision": "send_candidate", "confidence": 0.8}\n```',  # fenced
        "[1, 2, 3]",
        "",
        "   ",
    ],
)
async def test_malformed_output_rejected(make_gateway: GatewayFactory, content: str) -> None:
    provider = MockLLMProvider([content, content])
    with pytest.raises(MalformedOutputError) as exc_info:
        await make_gateway(provider).generate(MESSAGES, Verdict, operation="t")
    assert exc_info.value.error_class is LLMErrorClass.MALFORMED_OUTPUT
    assert exc_info.value.provider == "mock"


@pytest.mark.parametrize(
    "payload",
    [
        {"decision": "send_candidate"},  # missing field
        {"decision": "send_candidate", "confidence": 7},  # out of range
        {"decision": "send_candidate", "confidence": "0.9"},  # string, not coerced
        {"decision": "send_candidate", "confidence": 0.9, "approved": True},  # extra field
    ],
)
async def test_schema_invalid_output_rejected(
    make_gateway: GatewayFactory, payload: dict[str, object]
) -> None:
    provider = MockLLMProvider([payload, payload])
    with pytest.raises(SchemaValidationError) as exc_info:
        await make_gateway(provider).generate(MESSAGES, Verdict, operation="t")
    assert exc_info.value.error_class is LLMErrorClass.SCHEMA_INVALID
    assert exc_info.value.issues


def test_schema_error_does_not_echo_output_values() -> None:
    with pytest.raises(SchemaValidationError) as exc_info:
        parse_structured_output('{"decision": 12345, "confidence": "SECRET-VALUE"}', Verdict)
    text = str(exc_info.value) + " ".join(exc_info.value.issues)
    assert "SECRET-VALUE" not in text
    assert "12345" not in text
    assert "confidence: float_type" in exc_info.value.issues


def test_refusal_is_malformed() -> None:
    with pytest.raises(MalformedOutputError):
        parse_structured_output('{"decision": "x", "confidence": 0.1}', Verdict, refused=True)


async def test_malformed_output_retried_within_budget_then_succeeds(
    make_gateway: GatewayFactory, sink: InMemoryUsageSink, fake_time: FakeTime
) -> None:
    provider = MockLLMProvider(["oops", GOOD])
    result = await make_gateway(provider).generate(MESSAGES, Verdict, operation="t")
    assert result.output.decision == "send_candidate"
    assert provider.calls == 2
    assert [r.outcome for r in sink.records] == [LLMErrorClass.MALFORMED_OUTPUT, "success"]
    assert fake_time.sleeps == []  # output retries do not back off


async def test_output_retry_budget_is_bounded(make_gateway: GatewayFactory) -> None:
    provider = MockLLMProvider(["bad"] * 10)
    gateway = make_gateway(provider, retry=RetryPolicy(max_attempts=5, max_output_retries=2))
    with pytest.raises(MalformedOutputError):
        await gateway.generate(MESSAGES, Verdict, operation="t")
    assert provider.calls == 3


async def test_output_retries_can_be_disabled(make_gateway: GatewayFactory) -> None:
    provider = MockLLMProvider(["bad", GOOD])
    gateway = make_gateway(provider, retry=RetryPolicy(max_output_retries=0))
    with pytest.raises(MalformedOutputError):
        await gateway.generate(MESSAGES, Verdict, operation="t")
    assert provider.calls == 1


async def test_rate_limit_then_retry_then_success(
    make_gateway: GatewayFactory, fake_time: FakeTime, sink: InMemoryUsageSink
) -> None:
    provider = MockLLMProvider([LLMRateLimitedError(), LLMRateLimitedError(retry_after=4.0), GOOD])
    gateway = make_gateway(
        provider, retry=RetryPolicy(max_attempts=3, backoff_base_s=0.5, backoff_max_s=8)
    )
    result = await gateway.generate(MESSAGES, Verdict, operation="t")
    assert result.output == Verdict(**GOOD)
    assert provider.calls == 3
    # Exponential backoff, raised to the provider's Retry-After when larger.
    assert fake_time.sleeps == [0.5, 4.0]
    assert [r.outcome for r in sink.records] == [
        LLMErrorClass.RATE_LIMITED,
        LLMErrorClass.RATE_LIMITED,
        "success",
    ]
    assert [r.attempt for r in sink.records] == [1, 2, 3]
    assert len({r.call_id for r in sink.records}) == 1
    assert len(result.attempts) == 3


async def test_retry_limit_exhausted_raises(
    make_gateway: GatewayFactory, fake_time: FakeTime
) -> None:
    provider = MockLLMProvider([LLMServerError("503", status_code=503)] * 10)
    gateway = make_gateway(
        provider, retry=RetryPolicy(max_attempts=4, backoff_base_s=1, backoff_max_s=3)
    )
    with pytest.raises(LLMServerError):
        await gateway.generate(MESSAGES, Verdict, operation="t")
    assert provider.calls == 4
    assert fake_time.sleeps == [1, 2, 3]  # capped at backoff_max_s, no sleep after the last


async def test_retry_after_is_capped() -> None:
    policy = RetryPolicy(backoff_base_s=0.1, max_retry_after_s=10)
    assert policy.backoff(1, LLMRateLimitedError(retry_after=3600)) == 10


async def test_timeout_is_classified_and_retried(
    make_gateway: GatewayFactory, sink: InMemoryUsageSink
) -> None:
    provider = MockLLMProvider([MockReply('{"decision": "x", "confidence": 0.1}', delay_s=5), GOOD])
    gateway = make_gateway(provider, timeout_s=0.01)
    result = await gateway.generate(MESSAGES, Verdict, operation="t")
    assert result.output == Verdict(**GOOD)
    assert sink.records[0].outcome == LLMErrorClass.TIMEOUT
    assert sink.records[0].prompt_tokens == 0


async def test_timeout_exhausted_raises_typed_error(make_gateway: GatewayFactory) -> None:
    provider = MockLLMProvider([MockReply("{}", delay_s=5)] * 2)
    gateway = make_gateway(provider, retry=RetryPolicy(max_attempts=2), timeout_s=0.01)
    with pytest.raises(LLMTimeoutError) as exc_info:
        await gateway.generate(MESSAGES, Verdict, operation="t")
    assert exc_info.value.error_class is LLMErrorClass.TIMEOUT
    assert provider.calls == 2


@pytest.mark.parametrize(
    "error",
    [
        LLMAuthError("bad key", status_code=401),
        LLMConfigError("unknown model", status_code=404),
        LLMInvalidRequestError("context too long", status_code=400),
    ],
)
async def test_non_retryable_errors_not_retried(
    make_gateway: GatewayFactory, fake_time: FakeTime, error: Exception
) -> None:
    provider = MockLLMProvider([error, GOOD])
    with pytest.raises(type(error)):
        await make_gateway(provider).generate(MESSAGES, Verdict, operation="t")
    assert provider.calls == 1
    assert fake_time.sleeps == []


async def test_fallback_provider_used_when_primary_fails(
    make_gateway: GatewayFactory, sink: InMemoryUsageSink
) -> None:
    primary = MockLLMProvider([LLMServerError("down")] * 3, name="primary")
    secondary = MockLLMProvider([GOOD], name="secondary", model="backup-model")
    result = await make_gateway(primary, secondary).generate(MESSAGES, Verdict, operation="t")
    assert result.provider == "secondary"
    assert result.model == "backup-model"
    assert primary.calls == 3
    assert secondary.calls == 1
    assert [(r.provider, r.attempt) for r in sink.records] == [
        ("primary", 1),
        ("primary", 2),
        ("primary", 3),
        ("secondary", 4),
    ]


async def test_fallback_after_auth_error_skips_retries(make_gateway: GatewayFactory) -> None:
    primary = MockLLMProvider([LLMAuthError("revoked")], name="primary")
    secondary = MockLLMProvider([GOOD], name="secondary")
    result = await make_gateway(primary, secondary).generate(MESSAGES, Verdict, operation="t")
    assert result.provider == "secondary"
    assert primary.calls == 1


async def test_fallback_after_malformed_output(make_gateway: GatewayFactory) -> None:
    primary = MockLLMProvider(["nope", "still nope"], name="primary")
    secondary = MockLLMProvider([GOOD], name="secondary")
    result = await make_gateway(primary, secondary).generate(MESSAGES, Verdict, operation="t")
    assert result.provider == "secondary"
    assert primary.calls == 2


async def test_invalid_request_does_not_fall_back(make_gateway: GatewayFactory) -> None:
    primary = MockLLMProvider([LLMInvalidRequestError("too long")], name="primary")
    secondary = MockLLMProvider([GOOD], name="secondary")
    with pytest.raises(LLMInvalidRequestError):
        await make_gateway(primary, secondary).generate(MESSAGES, Verdict, operation="t")
    assert secondary.calls == 0


async def test_all_providers_failing_raises_last_error(make_gateway: GatewayFactory) -> None:
    primary = MockLLMProvider([LLMServerError("down")] * 3, name="primary")
    secondary = MockLLMProvider([LLMAuthError("bad key")], name="secondary")
    with pytest.raises(LLMAuthError) as exc_info:
        await make_gateway(primary, secondary).generate(MESSAGES, Verdict, operation="t")
    assert exc_info.value.provider == "secondary"


async def test_unexpected_provider_exception_becomes_typed(make_gateway: GatewayFactory) -> None:
    class Broken:
        name = "broken"

        async def complete(self, request: object) -> object:
            raise ValueError("Should we reply?")

    gateway = make_gateway(Broken(), retry=RetryPolicy(max_attempts=1))  # type: ignore[arg-type]
    with pytest.raises(LLMServerError) as exc_info:
        await gateway.generate(MESSAGES, Verdict, operation="t")
    assert "Should we reply?" not in str(exc_info.value)


async def test_token_and_cost_records_use_configured_rates(
    make_gateway: GatewayFactory, sink: InMemoryUsageSink
) -> None:
    pricing = PricingTable(
        {
            "primary:model-a": UnitRates(input_per_1m=Decimal("3"), output_per_1m=Decimal("15")),
            "model-b": UnitRates(input_per_1m=Decimal("0.5"), output_per_1m=Decimal("1.5")),
        }
    )
    primary = MockLLMProvider(
        [MockReply("garbage", 1000, 200), MockReply("garbage", 1000, 200)],
        name="primary",
        model="model-a",
    )
    secondary = MockLLMProvider(
        [MockReply('{"decision": "ok", "confidence": 1}', 2_000_000, 100_000)],
        name="secondary",
        model="model-b",
    )
    result = await make_gateway(primary, secondary, pricing=pricing).generate(
        MESSAGES, Verdict, operation="t"
    )
    # 1000 * 3/1M + 200 * 15/1M = 0.003 + 0.003
    assert sink.records[0].cost_usd == Decimal("0.006")
    assert sink.records[1].cost_usd == Decimal("0.006")
    # 2M * 0.5/1M + 100k * 1.5/1M = 1.0 + 0.15
    assert sink.records[2].cost_usd == Decimal("1.15")
    assert sink.total_cost_usd() == Decimal("1.162")
    assert sink.total_tokens() == 2 * 1200 + 2_100_000
    assert result.prompt_tokens == 2_002_000
    assert result.completion_tokens == 100_400
    assert all(r.latency_ms >= 0 for r in sink.records)


async def test_unpriced_model_records_unknown_cost(
    make_gateway: GatewayFactory, sink: InMemoryUsageSink
) -> None:
    provider = MockLLMProvider([MockReply(Verdict(**GOOD).model_dump_json(), 10, 10)])
    await make_gateway(provider).generate(MESSAGES, Verdict, operation="t")
    assert sink.records[0].cost_usd is None
    assert sink.total_cost_usd() == Decimal(0)


async def test_failed_http_attempt_has_no_invented_tokens(
    make_gateway: GatewayFactory, sink: InMemoryUsageSink
) -> None:
    pricing = PricingTable(default=UnitRates(input_per_1m=Decimal(1), output_per_1m=Decimal(1)))
    provider = MockLLMProvider(
        [LLMRateLimitedError(), MockReply('{"decision":"a","confidence":0}', 4, 2)]
    )
    await make_gateway(provider, pricing=pricing).generate(MESSAGES, Verdict, operation="t")
    first = sink.records[0]
    assert (first.prompt_tokens, first.completion_tokens, first.cost_usd) == (0, 0, Decimal(0))


async def test_latency_measured_with_injected_clock(
    make_gateway: GatewayFactory, sink: InMemoryUsageSink, fake_time: FakeTime
) -> None:
    def handler(request: object) -> MockReply:
        fake_time.now += 0.25  # the "provider" takes 250 ms on the fake clock
        return MockReply(Verdict(**GOOD).model_dump_json())

    await make_gateway(MockLLMProvider(handler=handler)).generate(MESSAGES, Verdict, operation="t")
    assert sink.records[0].latency_ms == 250


async def test_failing_usage_sink_does_not_fail_the_call(fake_time: FakeTime) -> None:
    from app.ai.llm_gateway import LLMGateway

    class BrokenSink:
        async def record(self, record: object) -> None:
            raise RuntimeError("db down")

    gateway = LLMGateway(
        MockLLMProvider([GOOD]),
        usage_sink=BrokenSink(),
        clock=fake_time.clock,  # type: ignore[arg-type]
    )
    result = await gateway.generate(MESSAGES, Verdict, operation="t")
    assert result.output == Verdict(**GOOD)


async def test_logs_never_contain_prompt_or_completion_text(
    make_gateway: GatewayFactory, caplog: pytest.LogCaptureFixture
) -> None:
    prompt_sentinel = "SENTINEL-PROMPT-7f3a9c"
    completion_sentinel = "SENTINEL-COMPLETION-c41b2e"
    messages = [
        LLMMessage(role="system", content=f"secret system {prompt_sentinel}"),
        LLMMessage(role="user", content=f"She said: {prompt_sentinel}"),
    ]
    primary = MockLLMProvider(
        [
            f"not json {completion_sentinel}",
            f'{{"decision": "{completion_sentinel}", "confidence": 9}}',
        ],
        name="primary",
    )
    secondary = MockLLMProvider(
        [
            LLMRateLimitedError(),
            {"decision": completion_sentinel, "confidence": 0.5},
        ],
        name="secondary",
    )
    caplog.set_level(logging.DEBUG)
    result = await make_gateway(primary, secondary).generate(messages, Verdict, operation="t")
    assert result.output.decision == completion_sentinel

    # Failure paths too.
    failing = MockLLMProvider([f"bad {completion_sentinel}"] * 2, name="failing")
    with pytest.raises(MalformedOutputError) as exc_info:
        await make_gateway(failing).generate(messages, Verdict, operation="t")

    assert caplog.records, "expected metadata log lines"
    logged = caplog.text + "".join(str(getattr(r, k, "")) for r in caplog.records for k in vars(r))
    for sentinel in (prompt_sentinel, completion_sentinel):
        assert sentinel not in logged
        assert sentinel not in str(exc_info.value)
    assert prompt_sentinel not in repr(messages[1])
    assert "provider=secondary" in caplog.text
    assert "outcome=rate_limited" in caplog.text
