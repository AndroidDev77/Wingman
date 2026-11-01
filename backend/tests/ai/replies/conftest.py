"""Fixtures for reply-generator tests: real PostgreSQL (the WING-003 throwaway cluster),
a scripted ``MockLLMProvider`` and the frozen contract. No network, no model keys."""

import itertools
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from functools import cache
from pathlib import Path
from typing import Any

import pytest
import yaml
from jsonschema import Draft202012Validator
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.llm_gateway import LLMGateway, RetryPolicy
from app.ai.memory import MemoryService
from app.ai.providers import InMemoryUsageSink, MockLLMProvider
from app.ai.reply_generator import ReplyGenerator
from app.db.enums import ConversationStage, DeliveryState, MessageDirection, MessageOrigin
from app.db.models import Message
from app.db.repositories import (
    ContactRepository,
    ConversationRepository,
    MemoryRepository,
    MessageRepository,
)
from app.db.scope import UserScope
from app.services.preferences import (
    ContactPreferenceOverrideUpdate,
    PreferencesService,
    PreferencesUpdate,
)

# Reuse the WING-003 real-Postgres fixtures (throwaway cluster / scratch DB per session).
from tests.db.conftest import (  # noqa: F401
    make_connection,
    make_user,
    migrated_db_url,
    pg_server_url,
)
from tests.db.conftest import session as session  # noqa: F401

NOW = datetime(2026, 11, 1, 12, 0, tzinfo=UTC)
START = NOW - timedelta(days=1)
OPENAPI = Path(__file__).resolve().parents[4] / "contracts" / "openapi.yaml"
GOLDEN = Path(__file__).resolve().parent / "golden"


# --------------------------------------------------------------------------- contract


@cache
def _openapi() -> dict[str, Any]:
    doc: dict[str, Any] = yaml.safe_load(OPENAPI.read_text())
    return doc


@cache
def _validator(schema_name: str) -> Draft202012Validator:
    root = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "components": _openapi()["components"],
        "$ref": f"#/components/schemas/{schema_name}",
    }
    return Draft202012Validator(root, format_checker=Draft202012Validator.FORMAT_CHECKER)


def assert_contract(schema_name: str, body: Any) -> None:
    _validator(schema_name).validate(body)


# --------------------------------------------------------------------------- data


@dataclass
class Thread:
    scope: UserScope
    contact_id: uuid.UUID
    conversation_id: uuid.UUID
    platform: str
    messages: list[uuid.UUID] = field(default_factory=list)

    def ref(self, message_id: uuid.UUID) -> str:
        return f"m{self.messages.index(message_id) + 1}"


_seq = itertools.count(1)


async def make_thread(
    db: AsyncSession,
    scope: UserScope,
    *,
    stage: ConversationStage = ConversationStage.RAPPORT,
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


async def remember(
    db: AsyncSession,
    thread: Thread,
    key: str,
    content: str,
    sources: list[uuid.UUID],
    *,
    category: str | None = None,
) -> uuid.UUID:
    row = await MemoryRepository(db, thread.scope).add(
        contact_id=thread.contact_id,
        conversation_id=thread.conversation_id,
        content=content,
        confidence=0.9,
        source_message_ids=sources,
        category=category or key.split(".", 1)[0],
        fact_key=key,
        stated_at=NOW - timedelta(hours=2),
    )
    return row.id


async def set_analysis(
    db: AsyncSession,
    thread: Thread,
    *,
    stage: ConversationStage,
    next_action: str,
    sources: list[uuid.UUID] | None = None,
) -> None:
    await ConversationRepository(db, thread.scope).set_analysis(
        thread.conversation_id,
        {
            "stage": stage.value,
            "confidence": 0.9,
            "uncertain": False,
            "interest_signals": [],
            "next_action": next_action,
            "source_message_ids": [str(s) for s in sources or []],
            "analyzed_at": "2026-11-01T11:00:00.000000Z",
        },
        stage,
    )


def preferences(
    *,
    mode: str = "open",
    custom: str | None = None,
    style: dict[str, Any] | None = None,
    reply_delay: dict[str, int] | None = None,
) -> PreferencesUpdate:
    return PreferencesUpdate.model_validate(
        {
            "intent": {"mode": mode, "custom_description": custom},
            "dealbreakers": [],
            "style": style or {},
            "automation": {"mode": "manual", "reply_delay_seconds": reply_delay},
        }
    )


async def set_preferences(db: AsyncSession, scope: UserScope, update: PreferencesUpdate) -> None:
    await PreferencesService(db, scope).put_user_preferences(update)


async def set_contact_override(
    db: AsyncSession, thread: Thread, update: ContactPreferenceOverrideUpdate
) -> None:
    await PreferencesService(db, thread.scope).put_contact_override(thread.contact_id, update)


def reply(
    text: str | None = "Sounds fun, how was it?",
    *,
    decision: str = "send_candidate",
    question: str | None = None,
    rationale: str = "Follows up on what the contact shared.",
    confidence: float = 0.85,
    message_refs: list[str] | None = None,
    memory_refs: list[str] | None = None,
    user_claims: list[dict[str, Any]] | None = None,
    commitment: str = "none",
    escalation: bool = False,
    interest_evidence_refs: list[str] | None = None,
) -> dict[str, Any]:
    """A ``ReplyOutput`` as the model would return it."""
    if decision != "send_candidate":
        text = None
    return {
        "decision": decision,
        "text": text,
        "question_for_user": question,
        "rationale": rationale,
        "confidence": confidence,
        "message_refs": message_refs if message_refs is not None else ["m1"],
        "memory_refs": memory_refs or [],
        "user_claims": user_claims or [],
        "commitment": commitment,
        "escalation": escalation,
        "interest_evidence_refs": interest_evidence_refs or [],
    }


# --------------------------------------------------------------------------- harness


@dataclass
class Harness:
    provider: MockLLMProvider
    gateway: LLMGateway
    sink: InMemoryUsageSink
    generator: ReplyGenerator

    def prompt(self, index: int = -1) -> str:
        """The user-role prompt text of a recorded request."""
        return self.provider.requests[index].messages[-1].content

    def system(self, index: int = -1) -> str:
        return self.provider.requests[index].messages[0].content


def build_harness(*, timeout_s: float = 30.0, handler: Any = None) -> Harness:
    provider = MockLLMProvider(handler=handler)
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
    generator = ReplyGenerator(gateway, memory=MemoryService(now=lambda: NOW), now=lambda: NOW)
    return Harness(provider, gateway, sink, generator)


@pytest.fixture
def make_harness() -> Callable[..., Harness]:
    return build_harness


@pytest.fixture
def harness() -> Harness:
    return build_harness()
