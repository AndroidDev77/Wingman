"""Provider for any OpenAI-compatible ``/chat/completions`` endpoint, over plain httpx.

No vendor SDK. Response bodies are never copied into error messages or logs, because
error bodies may echo the prompt.
"""

import json
from typing import Any

import httpx

from app.ai.providers.base import ProviderRequest, ProviderResponse
from app.ai.providers.errors import (
    LLMAuthError,
    LLMConfigError,
    LLMError,
    LLMInvalidRequestError,
    LLMRateLimitedError,
    LLMServerError,
    LLMTimeoutError,
)


def _retry_after(response: httpx.Response) -> float | None:
    raw = response.headers.get("retry-after")
    if raw is None:
        return None
    try:
        value = float(raw)
    except ValueError:
        return None  # HTTP-date form is not worth parsing here; backoff applies.
    return value if value >= 0 else None


def classify_status(response: httpx.Response, provider: str) -> LLMError:
    status = response.status_code
    msg = f"provider returned HTTP {status}"
    if status in (401, 403):
        return LLMAuthError(msg, provider=provider, status_code=status)
    if status == 404:
        # Wrong base URL or unknown model: a deployment problem, not a request problem.
        return LLMConfigError(msg, provider=provider, status_code=status)
    if status == 408:
        return LLMTimeoutError(msg, provider=provider, status_code=status)
    if status == 429:
        return LLMRateLimitedError(
            msg, provider=provider, status_code=status, retry_after=_retry_after(response)
        )
    if status >= 500:
        return LLMServerError(msg, provider=provider, status_code=status)
    return LLMInvalidRequestError(msg, provider=provider, status_code=status)


class OpenAICompatibleProvider:
    def __init__(
        self,
        *,
        api_key: str,
        model: str,
        base_url: str = "https://api.openai.com/v1",
        timeout_s: float = 30.0,
        strict_json_schema: bool = False,
        name: str = "openai",
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if not api_key:
            raise LLMConfigError("api key is required", provider=name)
        if not model:
            raise LLMConfigError("model is required", provider=name)
        self._name = name
        self.model = model
        self._url = base_url.rstrip("/") + "/chat/completions"
        self._headers = {"Authorization": f"Bearer {api_key}"}
        self._timeout = httpx.Timeout(timeout_s)
        self._strict = strict_json_schema
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(timeout=self._timeout)

    @property
    def name(self) -> str:
        return self._name

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    def _payload(self, request: ProviderRequest) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": [{"role": m.role, "content": m.content} for m in request.messages],
            "response_format": {
                "type": "json_schema",
                "json_schema": {
                    "name": request.schema_name,
                    "schema": request.output_schema,
                    "strict": self._strict,
                },
            },
        }
        if request.max_output_tokens is not None:
            payload["max_tokens"] = request.max_output_tokens
        if request.temperature is not None:
            payload["temperature"] = request.temperature
        return payload

    async def complete(self, request: ProviderRequest) -> ProviderResponse:
        try:
            response = await self._client.post(
                self._url,
                json=self._payload(request),
                headers=self._headers,
                timeout=self._timeout,
            )
        except httpx.TimeoutException as exc:
            raise LLMTimeoutError("provider request timed out", provider=self._name) from exc
        except httpx.TransportError as exc:
            raise LLMServerError(
                f"transport error: {type(exc).__name__}", provider=self._name
            ) from exc

        if response.status_code != 200:
            raise classify_status(response, self._name)
        return self._parse(response)

    def _parse(self, response: httpx.Response) -> ProviderResponse:
        bad_envelope = LLMServerError(
            "unexpected response envelope", provider=self._name, status_code=200
        )
        try:
            body = response.json()
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise bad_envelope from exc
        try:
            choice = body["choices"][0]
            message = choice["message"]
            usage = body["usage"]
            prompt_tokens = int(usage["prompt_tokens"])
            completion_tokens = int(usage["completion_tokens"])
            model = str(body.get("model") or self.model)
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise bad_envelope from exc

        # Empty or refused content is returned as-is (with its usage, which was billed)
        # and rejected by the gateway's output validation.
        content = message.get("content")
        return ProviderResponse(
            content=content if isinstance(content, str) else "",
            refused=bool(message.get("refusal")),
            model=model,
            prompt_tokens=max(0, prompt_tokens),
            completion_tokens=max(0, completion_tokens),
        )
