"""Typed, classified LLM errors.

Every failure the gateway can surface is an ``LLMError`` subclass with a stable
``error_class``. Error messages are written by this package and never contain prompt
or completion text, so they are safe to log.
"""

from enum import StrEnum
from typing import ClassVar


class LLMErrorClass(StrEnum):
    RATE_LIMITED = "rate_limited"
    TIMEOUT = "timeout"
    SERVER_ERROR = "server_error"
    AUTH_ERROR = "auth_error"
    CONFIG_ERROR = "config_error"
    INVALID_REQUEST = "invalid_request"
    MALFORMED_OUTPUT = "malformed_output"
    SCHEMA_INVALID = "schema_invalid"


class LLMError(Exception):
    """Base class. ``retryable`` says whether the same provider may be tried again."""

    error_class: ClassVar[LLMErrorClass]
    retryable: ClassVar[bool]
    # Whether the gateway may move on to the next provider in its fallback chain.
    fallback_eligible: ClassVar[bool] = True

    def __init__(
        self,
        message: str,
        *,
        provider: str | None = None,
        status_code: int | None = None,
    ) -> None:
        super().__init__(message)
        self.provider = provider
        self.status_code = status_code


class LLMRateLimitedError(LLMError):
    error_class = LLMErrorClass.RATE_LIMITED
    retryable = True

    def __init__(
        self,
        message: str = "provider rate limit",
        *,
        provider: str | None = None,
        status_code: int | None = None,
        retry_after: float | None = None,
    ) -> None:
        super().__init__(message, provider=provider, status_code=status_code)
        self.retry_after = retry_after


class LLMTimeoutError(LLMError):
    error_class = LLMErrorClass.TIMEOUT
    retryable = True


class LLMServerError(LLMError):
    """5xx, transport failure, or an unparseable response envelope."""

    error_class = LLMErrorClass.SERVER_ERROR
    retryable = True


class LLMAuthError(LLMError):
    error_class = LLMErrorClass.AUTH_ERROR
    retryable = False


class LLMConfigError(LLMError):
    error_class = LLMErrorClass.CONFIG_ERROR
    retryable = False


class LLMInvalidRequestError(LLMError):
    """The request itself is bad (e.g. context too long). Not retried, no fallback."""

    error_class = LLMErrorClass.INVALID_REQUEST
    retryable = False
    fallback_eligible = False


class LLMOutputError(LLMError):
    """The model answered, but the answer is unusable. Never coerced into a result.

    Retried only within the gateway's explicit ``max_output_retries`` budget.
    """

    error_class = LLMErrorClass.MALFORMED_OUTPUT
    retryable = True


class MalformedOutputError(LLMOutputError):
    """Output is empty, not JSON, or not a JSON object, or the model refused."""

    error_class = LLMErrorClass.MALFORMED_OUTPUT


class SchemaValidationError(LLMOutputError):
    """Output is JSON but does not validate against the requested Pydantic model.

    ``issues`` holds field locations and error types only (e.g. ``"confidence:
    less_than_equal"``), never input values.
    """

    error_class = LLMErrorClass.SCHEMA_INVALID

    def __init__(
        self,
        message: str,
        *,
        issues: tuple[str, ...] = (),
        provider: str | None = None,
    ) -> None:
        super().__init__(message, provider=provider)
        self.issues = issues
