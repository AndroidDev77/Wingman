"""Fixtures for WING-011: real PostgreSQL (the WING-003 throwaway cluster), a mock LLM
routed by output schema, the in-process ``MockConnector`` and the frozen contract.
No network, no model keys, no real messaging platform.

Two DB modes:

* ``session``: one rolled-back transaction per test (fast, isolated), like ``tests/db``.
* ``committed``: a real committing ``async_sessionmaker`` on a per-test scratch
  database, for concurrency tests that need separate sessions and real row locks.
"""

import itertools
import uuid
from collections import deque
from collections.abc import AsyncIterator, Callable, Iterator
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from functools import cache
from pathlib import Path
from typing import Any

import httpx
import pytest
import yaml
from fastapi import FastAPI, Header, HTTPException
from jsonschema import Draft202012Validator
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from alembic import command
from app.ai.analyzer import ConversationAnalyzer
from app.ai.llm_gateway import LLMGateway, RetryPolicy
from app.ai.memory import MemoryService
from app.ai.providers import InMemoryUsageSink, MockLLMProvider
from app.ai.providers.base import ProviderRequest
from app.ai.providers.mock import MockStep
from app.ai.reply_generator import ReplyGenerator
from app.api._automation_common import AutomationServices, build_services, get_services
from app.api.agent_actions import router as agent_actions_router
from app.api.device_connector import get_pipeline_runner
from app.api.device_connector import router as device_router
from app.api.errors import register_error_handlers
from app.api.messages import router as messages_router
from app.connectors import MockConnector
from app.connectors.capabilities import Capability, CapabilitySet, ConnectorCapabilities
from app.connectors.mock import MOCK_EVIDENCE, MOCK_VERIFIED_AT
from app.db.enums import AutomationMode
from app.db.repositories import ConnectionRepository
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope
from app.services.automation import AutomationController
from app.services.dispatcher import ConnectorRegistry, Dispatcher
from app.services.dispatcher import RetryPolicy as SendRetry
from app.services.preferences import PreferencesService, PreferencesUpdate

# Reuse the WING-003 real-Postgres fixtures.
from tests.db.conftest import (  # noqa: F401
    alembic_config,
    capabilities_doc,
    create_scratch_database,
    drop_scratch_database,
    make_user,
    migrated_db_url,
    pg_server_url,
)
from tests.db.conftest import session as session  # noqa: F401

NOW = datetime(2026, 11, 2, 15, 0, tzinfo=UTC)  # a Monday, 15:00 UTC
OPENAPI = Path(__file__).resolve().parents[3] / "contracts" / "openapi.yaml"
EVENT_SCHEMA = (
    Path(__file__).resolve().parents[3] / "contracts" / "schemas" / "connector_event.v1.json"
)


# --------------------------------------------------------------------------- contract


@cache
def _openapi() -> dict[str, Any]:
    doc: dict[str, Any] = yaml.safe_load(OPENAPI.read_text())
    return doc


@cache
def _validator(schema_name: str) -> Draft202012Validator:
    import json

    from referencing import Registry, Resource

    caps = json.loads((OPENAPI.parent / "schemas" / "connector_capabilities.v1.json").read_text())
    event = json.loads(EVENT_SCHEMA.read_text())
    root = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://wingman.invalid/contracts/openapi.json",
        "components": _openapi()["components"],
        "$ref": f"#/components/schemas/{schema_name}",
    }
    registry = Registry().with_resources(
        [
            (
                "https://wingman.invalid/contracts/schemas/connector_capabilities.v1.json",
                Resource.from_contents(caps),
            ),
            (
                "https://wingman.invalid/contracts/connector_capabilities.v1.json",
                Resource.from_contents(caps),
            ),
            (
                "https://wingman.invalid/contracts/schemas/connector_event.v1.json",
                Resource.from_contents(event),
            ),
        ]
    )
    return Draft202012Validator(
        root, registry=registry, format_checker=Draft202012Validator.FORMAT_CHECKER
    )


def assert_contract(schema_name: str, body: Any) -> None:
    _validator(schema_name).validate(body)


def assert_event(body: Any) -> None:
    import json

    schema = json.loads(EVENT_SCHEMA.read_text())
    Draft202012Validator(schema).validate(body)


# --------------------------------------------------------------------------- mock LLM


def reply(
    text: str | None = "Sounds great, how was the trip?",
    *,
    decision: str = "send_candidate",
    question: str | None = None,
    confidence: float = 0.9,
    commitment: str = "none",
    message_refs: list[str] | None = None,
) -> dict[str, Any]:
    """A ``ReplyOutput`` as the model would return it."""
    if decision != "send_candidate":
        text = None
    if decision == "ask_user" and question is None:
        question = "What would you like to say?"
    return {
        "decision": decision,
        "text": text,
        "question_for_user": question,
        "rationale": "Follows up on what the contact shared.",
        "confidence": confidence,
        "message_refs": message_refs if message_refs is not None else ["m1"],
        "memory_refs": [],
        "user_claims": [],
        "commitment": commitment,
        "escalation": False,
        "interest_evidence_refs": [],
    }


ANALYSIS = {
    "stage": "rapport",
    "confidence": 0.8,
    "interest_signals": [],
    "disengagement": "none",
    "disengagement_ref": None,
    "agreement": None,
    "ambiguities": [],
    "next_action": "reply",
}


@dataclass
class ScriptedLLM:
    """Answers by requested schema: memory extraction and analysis get fixed outputs,
    replies come from ``replies`` (default: a plain send_candidate)."""

    replies: deque[MockStep] = field(default_factory=deque)
    default_reply: MockStep = field(default_factory=reply)
    analysis: MockStep = field(default_factory=lambda: dict(ANALYSIS))
    calls: list[str] = field(default_factory=list)

    def __call__(self, request: ProviderRequest) -> MockStep:
        name = request.schema_name
        self.calls.append(name)
        if name == "ExtractionOutput":
            return {"facts": []}
        if name == "SummaryOutput":
            return {"summary": "summary"}
        if name == "AnalysisOutput":
            return self.analysis
        if name == "ReplyOutput":
            return self.replies.popleft() if self.replies else self.default_reply
        raise AssertionError(f"unexpected schema {name}")


def build_gateway(llm: ScriptedLLM) -> LLMGateway:
    async def no_sleep(_: float) -> None:
        return None

    return LLMGateway(
        MockLLMProvider(handler=llm),
        usage_sink=InMemoryUsageSink(),
        retry=RetryPolicy(max_attempts=1, max_output_retries=0),
        sleep=no_sleep,
    )


# --------------------------------------------------------------------------- world


def mock_caps(send: str = "VERIFIED", *, receipts: bool = True) -> ConnectorCapabilities:
    def cap(status: str, **kw: Any) -> Capability:
        if status == "VERIFIED":
            return Capability.verified(evidence=MOCK_EVIDENCE, verified_at=MOCK_VERIFIED_AT, **kw)
        if status == "UNVERIFIED":
            return Capability.unverified(**kw)
        return Capability.unsupported()

    return ConnectorCapabilities(
        platform="mock",
        connector_version="test",
        capabilities=CapabilitySet(
            read=cap("VERIFIED"),
            draft=cap("UNSUPPORTED"),
            send=cap(send, reports_delivery_receipts=receipts)
            if send != "UNSUPPORTED"
            else cap(send),
            swipe=cap("UNSUPPORTED"),
            calendar_write=cap("UNSUPPORTED"),
        ),
        assessed_at=MOCK_VERIFIED_AT,
    )


_seq = itertools.count(1)


class Clock:
    def __init__(self, start: datetime = NOW) -> None:
        self.now = start

    def __call__(self) -> datetime:
        return self.now

    def advance(self, **kw: float) -> None:
        self.now += timedelta(**kw)


@dataclass
class World:
    """One user with one connection, the services under test and the mock transport."""

    scope: UserScope
    connection_id: uuid.UUID
    platform: str
    clock: Clock
    llm: ScriptedLLM
    registry: ConnectorRegistry
    services: AutomationServices
    connector: MockConnector | None

    @property
    def controller(self) -> AutomationController:
        return self.services.controller

    @property
    def dispatcher(self) -> Dispatcher:
        return self.services.dispatcher

    def event(
        self,
        text: str = "Just got back from Lisbon!",
        *,
        thread: str = "thread-1",
        sender: str = "contact-sam",
        external_message_id: str | None = None,
        event_id: uuid.UUID | None = None,
        occurred_at: datetime | None = None,
    ) -> dict[str, Any]:
        """A ``connector_event.v1`` inbound message for this world's connection."""
        n = next(_seq)
        is_mock = self.platform == "mock"
        payload: dict[str, Any] = {
            "sender": {"external_contact_id": sender, "display_name": "Sam"},
            "text": text,
            "attachment_count": 0,
        }
        if is_mock:
            payload["mock"] = {"scenario_id": "wing011", "sequence": n}
        when = (occurred_at or self.clock.now - timedelta(seconds=30)).isoformat()
        return {
            "schema_version": "connector_event.v1",
            "event_id": str(event_id or uuid.uuid4()),
            "type": "mock.inbound_message_injected" if is_mock else "message.received",
            "platform": self.platform,
            "connection_id": str(self.connection_id),
            "user_id": str(self.scope.user_id),
            "external_message_id": external_message_id or f"ext-{uuid.uuid4().hex}",
            "external_thread_id": thread,
            "occurred_at": when.replace("+00:00", "Z"),
            "payload": payload,
        }

    def outcome(
        self,
        kind: str,
        *,
        message_id: uuid.UUID,
        key: str | None = None,
        retryable: bool = True,
        code: str = "transport_error",
        external_message_id: str | None = None,
    ) -> dict[str, Any]:
        """A device send outcome event (``message.*`` or ``mock.*`` per platform)."""
        is_mock = self.platform == "mock"
        names = {
            "ack": ("message.send_acknowledged", "mock.outbound_send_acknowledged"),
            "failed": ("message.send_failed", "mock.outbound_send_failed"),
            "receipt": ("message.delivery_receipt", "mock.delivery_receipt_simulated"),
        }
        payload: dict[str, Any] = {"outbound_message_id": str(message_id)}
        ext: str | None = None
        if kind in ("ack", "failed"):
            payload["idempotency_key"] = key
        if kind == "failed":
            payload["failure"] = {"code": code, "detail": "radio off", "retryable": retryable}
        else:
            ext = external_message_id or f"out-{message_id}"
        if is_mock:
            payload["mock"] = {"scenario_id": "wing011", "sequence": next(_seq)}
        return {
            "schema_version": "connector_event.v1",
            "event_id": str(uuid.uuid4()),
            "type": names[kind][1 if is_mock else 0],
            "platform": self.platform,
            "connection_id": str(self.connection_id),
            "user_id": str(self.scope.user_id),
            "external_message_id": ext,
            "external_thread_id": "thread-1",
            "occurred_at": self.clock.now.isoformat().replace("+00:00", "Z"),
            "payload": payload,
        }


async def set_mode(
    db: AsyncSession,
    scope: UserScope,
    mode: AutomationMode | str,
    *,
    active_hours: dict[str, Any] | None = None,
    reply_delay: dict[str, int] | None = None,
    daily_send_limit: int | None = None,
    approval_required_for: list[str] | None = None,
) -> None:
    update = PreferencesUpdate.model_validate(
        {
            "intent": {"mode": "open"},
            "dealbreakers": [],
            "style": {},
            "automation": {
                "mode": str(mode),
                "active_hours": active_hours,
                "reply_delay_seconds": reply_delay,
                "daily_send_limit": daily_send_limit,
                "approval_required_for": approval_required_for or [],
            },
        }
    )
    await PreferencesService(db, scope).put_user_preferences(update)


async def make_world(
    db: AsyncSession,
    *,
    platform: str = "mock",
    send: str = "VERIFIED",
    device_side: bool = False,
    mode: AutomationMode | str | None = None,
    scope: UserScope | None = None,
    llm: ScriptedLLM | None = None,
    clock: Clock | None = None,
    retry: SendRetry | None = None,
) -> World:
    scope = scope or await make_user(db)
    clock = clock or Clock()
    llm = llm or ScriptedLLM()
    caps = (
        mock_caps(send).to_contract_dict()
        if platform == "mock"
        else capabilities_doc(platform, send=send)
    )
    conn = await ConnectionRepository(db, scope).create(
        platform=platform, display_name=f"{platform} test", capabilities=caps
    )
    registry = ConnectorRegistry(auto_mock=False)
    connector: MockConnector | None = None
    if platform == "mock" and not device_side:
        connector = MockConnector(
            user_id=scope.user_id, connection_id=conn.id, capabilities=mock_caps(send)
        )
        registry.register(conn.id, connector)
    if device_side:
        registry.mark_device_side(conn.id)
    gateway = build_gateway(llm)
    memory = MemoryService(gateway, now=clock)
    dispatcher = Dispatcher(registry, retry=retry or SendRetry(max_attempts=3), now=clock)
    controller = AutomationController(
        dispatcher,
        generator=ReplyGenerator(gateway, memory=memory, now=clock),
        analyzer=ConversationAnalyzer(gateway, memory=memory, now=clock),
        memory=memory,
        now=clock,
    )
    services = build_services(controller=controller)
    services.outbox._now = clock  # noqa: SLF001 - share the test clock
    services.ingest._now = clock  # noqa: SLF001
    if mode is not None:
        await set_mode(db, scope, mode)
    return World(scope, conn.id, platform, clock, llm, registry, services, connector)


# --------------------------------------------------------------------------- HTTP


def build_app(session: AsyncSession, world: World) -> FastAPI:
    """The WING-011 routers on the test's session, with header auth (D-007)."""
    app = FastAPI()
    register_error_handlers(app)
    app.include_router(agent_actions_router)
    app.include_router(messages_router)
    app.include_router(device_router)

    async def test_session() -> AsyncIterator[AsyncSession]:
        yield session

    async def test_scope(x_test_user: str | None = Header(default=None)) -> UserScope:
        if x_test_user is None:
            raise HTTPException(status_code=401, detail="no test user")
        return UserScope(uuid.UUID(x_test_user))

    async def services() -> AutomationServices:
        return world.services

    def runner() -> Callable[[UserScope, uuid.UUID], Any]:
        async def run(scope: UserScope, message_id: uuid.UUID) -> None:
            async with session.begin_nested():
                await world.controller.handle_inbound_message(session, scope, message_id)

        return run

    app.dependency_overrides[get_session] = test_session
    app.dependency_overrides[get_current_user_scope] = test_scope
    app.dependency_overrides[get_services] = services
    app.dependency_overrides[get_pipeline_runner] = runner
    return app


@pytest.fixture
def make_client(session: AsyncSession) -> Iterator[Callable[[World], httpx.AsyncClient]]:
    """HTTP clients on the test's rolled-back session. The ingest route commits before
    running the pipeline; here that commit is a flush so the test transaction survives."""
    clients: list[httpx.AsyncClient] = []
    original_commit = session.commit

    async def flush_only() -> None:
        await session.flush()

    session.commit = flush_only  # type: ignore[method-assign]

    def factory(world: World) -> httpx.AsyncClient:
        transport = httpx.ASGITransport(app=build_app(session, world))
        client = httpx.AsyncClient(transport=transport, base_url="http://test")
        clients.append(client)
        return client

    yield factory
    session.commit = original_commit  # type: ignore[method-assign]


def headers(scope: UserScope, key: str | None = None) -> dict[str, str]:
    out = {"X-Test-User": str(scope.user_id)}
    out["Idempotency-Key"] = key or f"key-{uuid.uuid4().hex}"
    return out


def problem(resp: httpx.Response, status: int, code: str) -> dict[str, Any]:
    assert resp.status_code == status, resp.text
    assert resp.headers["content-type"].startswith("application/problem+json")
    body: dict[str, Any] = resp.json()
    assert_contract("Problem", body)
    assert body["code"] == code, body
    return body


# --------------------------------------------------------------------------- committed DB


@pytest.fixture
def committed_db_url(pg_server_url: str) -> Iterator[str]:  # noqa: F811
    url = create_scratch_database(pg_server_url)
    try:
        command.upgrade(alembic_config(url), "head")
        yield url
    finally:
        drop_scratch_database(pg_server_url, url)


@pytest.fixture
async def committed(committed_db_url: str) -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    engine = create_async_engine(
        committed_db_url,
        poolclass=NullPool,
        connect_args={"server_settings": {"timezone": "UTC"}},
    )
    try:
        yield async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
    finally:
        await engine.dispose()
