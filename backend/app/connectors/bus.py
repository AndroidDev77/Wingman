"""In-process event fan-out used by connectors to implement `subscribe()`."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from types import TracebackType

from app.connectors.events import ConnectorEvent


class EventSubscription:
    """Async iterator over events published after it was opened.

    Use as `async with connector.subscribe() as events: async for e in events: ...`
    or call `close()` explicitly. Iteration ends after `close()`.
    """

    def __init__(self, bus: EventBus) -> None:
        self._bus = bus
        self._queue: asyncio.Queue[ConnectorEvent | None] = asyncio.Queue()
        self._closed = False

    def _deliver(self, event: ConnectorEvent) -> None:
        if not self._closed:
            self._queue.put_nowait(event)

    def close(self) -> None:
        if not self._closed:
            self._closed = True
            self._bus._unsubscribe(self)
            self._queue.put_nowait(None)

    @property
    def closed(self) -> bool:
        return self._closed

    def pending(self) -> int:
        """Number of events delivered but not yet consumed."""
        return self._queue.qsize() - (1 if self._closed else 0)

    def get_nowait(self) -> ConnectorEvent | None:
        """Next buffered event, or None if none is waiting."""
        try:
            item = self._queue.get_nowait()
        except asyncio.QueueEmpty:
            return None
        if item is None:
            self._queue.put_nowait(None)
        return item

    def __aiter__(self) -> AsyncIterator[ConnectorEvent]:
        return self

    async def __anext__(self) -> ConnectorEvent:
        item = await self._queue.get()
        if item is None:
            self._queue.put_nowait(None)
            raise StopAsyncIteration
        return item

    async def __aenter__(self) -> EventSubscription:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()


class EventBus:
    """Keeps an ordered history of published events and fans them out to subscribers."""

    def __init__(self) -> None:
        self._subscribers: list[EventSubscription] = []
        self._history: list[ConnectorEvent] = []

    def publish(self, event: ConnectorEvent) -> None:
        self._history.append(event)
        for sub in list(self._subscribers):
            sub._deliver(event)

    def subscribe(self) -> EventSubscription:
        sub = EventSubscription(self)
        self._subscribers.append(sub)
        return sub

    def _unsubscribe(self, sub: EventSubscription) -> None:
        if sub in self._subscribers:
            self._subscribers.remove(sub)

    @property
    def history(self) -> list[ConnectorEvent]:
        """All published events, oldest first (a copy)."""
        return list(self._history)
