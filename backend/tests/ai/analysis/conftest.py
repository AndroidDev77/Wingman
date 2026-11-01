"""Fixtures for analyzer tests: real PostgreSQL (the WING-003 throwaway cluster) and a
scripted ``MockLLMProvider``. No network, no model keys."""

import itertools
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.analyzer import ConversationAnalyzer
from app.ai.llm_gateway import LLMGateway, RetryPolicy
from app.ai.memory import MemoryService
from app.ai.providers import InMemoryUsageSink, MockLLMProvider
from app.db.enums import ConversationStage, DeliveryState, MessageDirection, MessageOrigin
from app.db.models import Message
from app.db.repositories import ContactRepository, ConversationRepository, MessageRepository
from app.db.scope import UserScope

# Reuse the WING-003 real-Postgres fixtures (throwaway cluster / scratch DB per session).
from tests.db.conftest import (  # noqa: F401
    make_connection,
    make_user,
    migrated_db_url,
    pg_server_url,
)
from tests.db.conftest import session as session  # noqa: F401

NOW = datetime(2026, 11, 1, 12, 0, tzinfo=UTC)
START = NOW - timedelta(days=2)


@dataclass
class Thread:
    scope: UserScope
    contact_id: uuid.UUID
    conversation_id: uuid.UUID
    platform: str
    messages: list[uuid.UUID] = field(default_factory=list)

    def ref(self, message_id: uuid.UUID) -> str:
        """The ``m<n>`` ref the analyzer assigns (whole thread fits the window)."""
        return f"m{self.messages.index(message_id) + 1}"


_seq = itertools.count(1)


async def make_thread(
    db: AsyncSession,
    scope: UserScope,
    *,
    stage: ConversationStage = ConversationStage.NEW,
) -> Thread:
    conn = await make_connection(db, scope)
    n = next(_seq)
    contact = await ContactRepository(db, scope).get_or_create(
        connection_id=conn, external_contact_id=f"c-{n}", display_name="Sam"
    )
    conv = await ConversationRepository(db, scope).create(
        contact_id=contact.id,
        connection_id=conn,
        platform="mock",
        external_thread_id=f"t-{n}",
        stage=stage,
        last_activity_at=START,
    )
    return Thread(scope, contact.id, conv.id, "mock")


def _when(thread: Thread) -> datetime:
    return START + timedelta(minutes=5 * len(thread.messages))


async def contact_says(db: AsyncSession, thread: Thread, text: str) -> uuid.UUID:
    res = await MessageRepository(db, thread.scope).ingest_inbound(
        conversation_id=thread.conversation_id,
        platform=thread.platform,
        external_message_id=f"x-{uuid.uuid4().hex}",
        text=text,
        sent_at_source=_when(thread),
    )
    thread.messages.append(res.message.id)
    return res.message.id


async def user_says(db: AsyncSession, thread: Thread, text: str) -> uuid.UUID:
    when = _when(thread)
    row = Message(
        user_id=thread.scope.user_id,
        conversation_id=thread.conversation_id,
        platform=thread.platform,
        direction=MessageDirection.OUTBOUND,
        origin=MessageOrigin.USER_MANUAL,
        text=text,
        delivery_state=DeliveryState.SENT,
        sent_at=when,
        created_at=when,
    )
    db.add(row)
    await db.flush()
    thread.messages.append(row.id)
    return row.id


def signal(text: str, polarity: str, ref: str | None) -> dict[str, Any]:
    return {"signal": text, "polarity": polarity, "source_ref": ref}


def agreement(
    plan: str,
    *,
    user: str | None,
    contact: str | None,
    time: bool = True,
    place: bool = True,
) -> dict[str, Any]:
    return {
        "plan_ref": plan,
        "has_specific_time": time,
        "has_place_or_activity": place,
        "user_agreement_ref": user,
        "contact_agreement_ref": contact,
    }


def output(
    stage: str,
    confidence: float,
    *,
    signals: list[dict[str, Any]] | None = None,
    disengagement: str = "none",
    disengagement_ref: str | None = None,
    agreement: dict[str, Any] | None = None,
    ambiguities: list[str] | None = None,
    next_action: str = "reply",
) -> dict[str, Any]:
    return {
        "stage": stage,
        "confidence": confidence,
        "interest_signals": signals or [],
        "disengagement": disengagement,
        "disengagement_ref": disengagement_ref,
        "agreement": agreement,
        "ambiguities": ambiguities or [],
        "next_action": next_action,
    }


@dataclass
class Harness:
    provider: MockLLMProvider
    gateway: LLMGateway
    sink: InMemoryUsageSink
    analyzer: ConversationAnalyzer


def build_harness(*, timeout_s: float = 30.0) -> Harness:
    provider = MockLLMProvider()
    sink = InMemoryUsageSink()

    async def no_sleep(_: float) -> None:
        return None

    gateway = LLMGateway(
        provider,
        usage_sink=sink,
        retry=RetryPolicy(max_attempts=1, max_output_retries=0),
        sleep=no_sleep,
        timeout_s=timeout_s,
    )
    analyzer = ConversationAnalyzer(gateway, memory=MemoryService(now=lambda: NOW), now=lambda: NOW)
    return Harness(provider, gateway, sink, analyzer)


@pytest.fixture
def make_harness() -> Callable[..., Harness]:
    return build_harness


@pytest.fixture
def harness() -> Harness:
    return build_harness()
