"""Fixtures for memory tests: real PostgreSQL (the WING-003 throwaway cluster) and a
scripted ``MockLLMProvider``. No network, no model keys."""

import itertools
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from typing import Any

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.llm_gateway import LLMGateway, RetryPolicy
from app.ai.memory import MemoryService, Retriever
from app.ai.providers import InMemoryUsageSink, MockLLMProvider
from app.db.repositories import ContactRepository, ConversationRepository, MessageRepository
from app.db.scope import UserScope

# Reuse the WING-003 real-Postgres fixtures (throwaway cluster / scratch DB per session).
from tests.db.conftest import (  # noqa: F401
    make_connection,
    make_user,
    migrated_db_url,
    pg_server_url,
    scratch_db_url,
)
from tests.db.conftest import session as session  # noqa: F401

NOW = datetime(2026, 11, 1, 12, 0, tzinfo=UTC)
TODAY = NOW.date()


@dataclass
class Thread:
    scope: UserScope
    contact_id: uuid.UUID
    conversation_id: uuid.UUID
    platform: str
    messages: list[uuid.UUID] = field(default_factory=list)


_seq = itertools.count(1)


async def make_thread(
    db: AsyncSession,
    scope: UserScope,
    *,
    connection_id: uuid.UUID | None = None,
    name: str = "Sam",
) -> Thread:
    conn = connection_id or await make_connection(db, scope)
    n = next(_seq)
    contact = await ContactRepository(db, scope).get_or_create(
        connection_id=conn, external_contact_id=f"c-{n}", display_name=name
    )
    conv = await ConversationRepository(db, scope).create(
        contact_id=contact.id,
        connection_id=conn,
        platform="mock",
        external_thread_id=f"t-{n}",
        last_activity_at=NOW - timedelta(days=30),
    )
    return Thread(scope, contact.id, conv.id, "mock")


async def say(
    db: AsyncSession, thread: Thread, text: str, *, at: datetime | None = None
) -> uuid.UUID:
    """Inbound message from the contact. Returns the message ID."""
    when = at or (NOW - timedelta(hours=1) + timedelta(seconds=len(thread.messages)))
    res = await MessageRepository(db, thread.scope).ingest_inbound(
        conversation_id=thread.conversation_id,
        platform=thread.platform,
        external_message_id=f"x-{uuid.uuid4().hex}",
        text=text,
        sent_at_source=when,
    )
    thread.messages.append(res.message.id)
    return res.message.id


def fact(
    key: str,
    content: str,
    refs: list[str],
    *,
    category: str | None = None,
    basis: str = "stated",
    confidence: float = 0.9,
    valid_until: date | None = None,
) -> dict[str, Any]:
    return {
        "key": key,
        "category": category or key.split(".", 1)[0],
        "content": content,
        "basis": basis,
        "confidence": confidence,
        "source_refs": refs,
        "valid_until": valid_until.isoformat() if valid_until else None,
    }


def facts(*items: dict[str, Any]) -> dict[str, Any]:
    return {"facts": list(items)}


@dataclass
class Harness:
    provider: MockLLMProvider
    gateway: LLMGateway
    sink: InMemoryUsageSink
    service: MemoryService


@pytest.fixture
def make_harness() -> Callable[..., Harness]:
    def factory(retriever: Retriever | None = None, now: datetime = NOW) -> Harness:
        provider = MockLLMProvider()
        sink = InMemoryUsageSink()

        async def no_sleep(_: float) -> None:
            return None

        gateway = LLMGateway(
            provider,
            usage_sink=sink,
            retry=RetryPolicy(max_attempts=1, max_output_retries=0),
            sleep=no_sleep,
        )
        service = MemoryService(gateway, retriever=retriever, now=lambda: now)
        return Harness(provider, gateway, sink, service)

    return factory


@pytest.fixture
def harness(make_harness: Callable[..., Harness]) -> Harness:
    return make_harness()
