"""The transport-agnostic `Connector` protocol and a capability-enforcing base class.

Every connector (mock, SMS, notification, ...) exposes the same async surface. The
dispatcher (WING-011) is the only caller of `send_message`.

Capability guard per operation (checked before any side effect):

| Operation            | Capability |
|----------------------|------------|
| `list_conversations` | `read`     |
| `read_messages`      | `read`     |
| `send_message`       | `send`     |
| `get_profiles`       | `swipe`    |
| `swipe`              | `swipe`    |

`subscribe()` is always available: a connector may report send acknowledgements
even when it cannot read. An UNSUPPORTED capability raises `UnsupportedCapability`;
nothing is silently skipped and no success is faked.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Protocol, runtime_checkable

from app.connectors.bus import EventBus, EventSubscription
from app.connectors.capabilities import CapabilityName, ConnectorCapabilities
from app.connectors.errors import UnsupportedCapability
from app.connectors.models import (
    DEFAULT_PAGE_LIMIT,
    ConnectorConversation,
    ConnectorMessage,
    ConnectorProfile,
    Page,
    SendRequest,
    SendResult,
    SwipeRequest,
    SwipeResult,
)


@runtime_checkable
class Connector(Protocol):
    """One connection (one user, one platform account) to a messaging transport."""

    @property
    def platform(self) -> str: ...

    def capabilities(self) -> ConnectorCapabilities: ...

    async def list_conversations(
        self, *, cursor: str | None = None, limit: int = DEFAULT_PAGE_LIMIT
    ) -> Page[ConnectorConversation]: ...

    async def read_messages(
        self,
        external_thread_id: str,
        *,
        cursor: str | None = None,
        limit: int = DEFAULT_PAGE_LIMIT,
    ) -> Page[ConnectorMessage]:
        """Messages in a thread, oldest first; `next_cursor` moves forward in time."""
        ...

    async def send_message(self, request: SendRequest) -> SendResult:
        """Transmit at most once per `request.idempotency_key`.

        Returns the observed outcome (acknowledged or failed); never claims delivery.
        A repeated key with the same request returns the stored result with
        `replayed=True` and no second send. A repeated key with a different request
        raises `IdempotencyKeyConflict`.
        """
        ...

    def subscribe(self) -> EventSubscription:
        """Stream of `ConnectorEvent`s emitted from now on (deduplicated inbound)."""
        ...

    async def get_profiles(
        self, *, cursor: str | None = None, limit: int = DEFAULT_PAGE_LIMIT
    ) -> Page[ConnectorProfile]: ...

    async def swipe(self, request: SwipeRequest) -> SwipeResult: ...


class BaseConnector(ABC):
    """Implements the capability guard once; subclasses fill in `_do_*` hooks.

    Optional operations (`get_profiles`, `swipe`) raise `UnsupportedCapability` by
    default, even if a subclass advertises them without implementing them.
    """

    def __init__(self) -> None:
        self._bus = EventBus()

    @property
    def platform(self) -> str:
        return self.capabilities().platform

    @abstractmethod
    def capabilities(self) -> ConnectorCapabilities: ...

    def _require(self, name: CapabilityName, operation: str) -> None:
        self.capabilities().require(name, operation=operation)

    # -------------------------------------------------------------- public API

    async def list_conversations(
        self, *, cursor: str | None = None, limit: int = DEFAULT_PAGE_LIMIT
    ) -> Page[ConnectorConversation]:
        self._require(CapabilityName.READ, "list_conversations")
        return await self._do_list_conversations(cursor=cursor, limit=limit)

    async def read_messages(
        self,
        external_thread_id: str,
        *,
        cursor: str | None = None,
        limit: int = DEFAULT_PAGE_LIMIT,
    ) -> Page[ConnectorMessage]:
        self._require(CapabilityName.READ, "read_messages")
        return await self._do_read_messages(external_thread_id, cursor=cursor, limit=limit)

    async def send_message(self, request: SendRequest) -> SendResult:
        self._require(CapabilityName.SEND, "send_message")
        return await self._do_send_message(request)

    def subscribe(self) -> EventSubscription:
        return self._bus.subscribe()

    async def get_profiles(
        self, *, cursor: str | None = None, limit: int = DEFAULT_PAGE_LIMIT
    ) -> Page[ConnectorProfile]:
        self._require(CapabilityName.SWIPE, "get_profiles")
        return await self._do_get_profiles(cursor=cursor, limit=limit)

    async def swipe(self, request: SwipeRequest) -> SwipeResult:
        self._require(CapabilityName.SWIPE, "swipe")
        return await self._do_swipe(request)

    # -------------------------------------------------------------- hooks

    @abstractmethod
    async def _do_list_conversations(
        self, *, cursor: str | None, limit: int
    ) -> Page[ConnectorConversation]: ...

    @abstractmethod
    async def _do_read_messages(
        self, external_thread_id: str, *, cursor: str | None, limit: int
    ) -> Page[ConnectorMessage]: ...

    @abstractmethod
    async def _do_send_message(self, request: SendRequest) -> SendResult: ...

    async def _do_get_profiles(self, *, cursor: str | None, limit: int) -> Page[ConnectorProfile]:
        raise UnsupportedCapability(
            CapabilityName.SWIPE, platform=self.platform, operation="get_profiles"
        )

    async def _do_swipe(self, request: SwipeRequest) -> SwipeResult:
        raise UnsupportedCapability(CapabilityName.SWIPE, platform=self.platform, operation="swipe")
