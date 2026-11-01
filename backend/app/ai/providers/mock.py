"""Deterministic, scriptable provider for tests and ``WINGMAN_LLM_PROVIDER=mock``.

Needs no API key and makes no network calls. Each call consumes the next scripted
step, or asks ``handler`` for one when the script is empty. A step is:

* a ``BaseModel`` or ``dict``: serialized to JSON and returned as the completion;
* a ``str``: returned verbatim (use this to simulate malformed or schema-invalid JSON);
* a ``MockReply``: full control over content, token counts, model and delay;
* an ``LLMError`` instance: raised (simulates rate limits, timeouts, 5xx, auth, ...).

With an empty script and no handler the call raises ``LLMConfigError``, so a test never
silently receives an invented answer.
"""

import asyncio
import json
from collections import deque
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel

from app.ai.providers.base import ProviderRequest, ProviderResponse
from app.ai.providers.errors import LLMConfigError, LLMError


@dataclass(frozen=True)
class MockReply:
    content: str
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    model: str | None = None
    # Seconds to await before answering; lets tests exercise the gateway timeout.
    delay_s: float = 0.0


MockStep = BaseModel | dict[str, Any] | str | MockReply | LLMError
MockHandler = Callable[[ProviderRequest], MockStep]


def _estimate_tokens(text: str) -> int:
    # Deterministic stand-in for a tokenizer: about four characters per token.
    return max(1, (len(text) + 3) // 4)


class MockLLMProvider:
    def __init__(
        self,
        script: Iterable[MockStep] = (),
        *,
        handler: MockHandler | None = None,
        name: str = "mock",
        model: str = "mock-model",
    ) -> None:
        self._script: deque[MockStep] = deque(script)
        self._handler = handler
        self._name = name
        self.model = model
        # Every request received, in order, for assertions.
        self.requests: list[ProviderRequest] = []

    @property
    def name(self) -> str:
        return self._name

    @property
    def calls(self) -> int:
        return len(self.requests)

    def push(self, *steps: MockStep) -> None:
        self._script.extend(steps)

    @property
    def remaining(self) -> int:
        return len(self._script)

    async def complete(self, request: ProviderRequest) -> ProviderResponse:
        self.requests.append(request)
        if self._script:
            step = self._script.popleft()
        elif self._handler is not None:
            step = self._handler(request)
        else:
            raise LLMConfigError("mock provider has no scripted response", provider=self._name)

        if isinstance(step, LLMError):
            if step.provider is None:
                step.provider = self._name
            raise step
        reply = self._to_reply(step)
        if reply.delay_s > 0:
            await asyncio.sleep(reply.delay_s)
        prompt_text = "".join(m.content for m in request.messages)
        return ProviderResponse(
            content=reply.content,
            model=reply.model or self.model,
            prompt_tokens=(
                reply.prompt_tokens
                if reply.prompt_tokens is not None
                else _estimate_tokens(prompt_text)
            ),
            completion_tokens=(
                reply.completion_tokens
                if reply.completion_tokens is not None
                else _estimate_tokens(reply.content)
            ),
        )

    @staticmethod
    def _to_reply(step: BaseModel | dict[str, Any] | str | MockReply) -> MockReply:
        if isinstance(step, MockReply):
            return step
        if isinstance(step, BaseModel):
            return MockReply(content=step.model_dump_json())
        if isinstance(step, dict):
            return MockReply(content=json.dumps(step))
        return MockReply(content=step)
