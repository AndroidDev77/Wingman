"""Inbound event deduplication behind a small store protocol.

Contract rule (connector_event.v1, ADR 0004): an inbound message is identified by
`(user_id, platform, external_message_id)`. Replaying it must not create a second
message. Exact envelope replays are also recognised by `event_id`.

`InMemoryDedupStore` is for tests and the simulator. A durable implementation
(WING-003: unique `(user_id, platform, external_message_id)`) can implement
`DedupStore` without this package importing `app.db`.
"""

from __future__ import annotations

import asyncio
from enum import StrEnum
from typing import Protocol, runtime_checkable
from uuid import UUID

from app.connectors.events import ConnectorEvent, InboundDedupKey


@runtime_checkable
class DedupStore(Protocol):
    """Atomic first-writer-wins claims. Each method returns True only for the first claim."""

    async def claim_event_id(self, event_id: UUID) -> bool: ...

    async def claim_inbound_message(self, key: InboundDedupKey) -> bool: ...


class InMemoryDedupStore:
    """Process-local `DedupStore`. Not durable; not shared between processes."""

    def __init__(self) -> None:
        self._event_ids: set[UUID] = set()
        self._inbound: set[InboundDedupKey] = set()
        self._lock = asyncio.Lock()

    async def claim_event_id(self, event_id: UUID) -> bool:
        async with self._lock:
            if event_id in self._event_ids:
                return False
            self._event_ids.add(event_id)
            return True

    async def claim_inbound_message(self, key: InboundDedupKey) -> bool:
        async with self._lock:
            if key in self._inbound:
                return False
            self._inbound.add(key)
            return True

    def __len__(self) -> int:
        return len(self._inbound)


class DedupOutcome(StrEnum):
    ACCEPTED = "accepted"
    DUPLICATE_EVENT = "duplicate_event"
    """Same `event_id` seen before (exact envelope replay)."""
    DUPLICATE_MESSAGE = "duplicate_message"
    """New envelope, but the inbound (user, platform, external_message_id) was seen before."""

    @property
    def is_duplicate(self) -> bool:
        return self is not DedupOutcome.ACCEPTED


class InboundDeduplicator:
    """Decides whether an event is new. Duplicates must be acknowledged and dropped."""

    def __init__(self, store: DedupStore | None = None) -> None:
        self.store: DedupStore = store if store is not None else InMemoryDedupStore()

    async def check(self, event: ConnectorEvent) -> DedupOutcome:
        if not await self.store.claim_event_id(event.event_id):
            return DedupOutcome.DUPLICATE_EVENT
        key = event.dedup_key
        if key is not None and not await self.store.claim_inbound_message(key):
            return DedupOutcome.DUPLICATE_MESSAGE
        return DedupOutcome.ACCEPTED
