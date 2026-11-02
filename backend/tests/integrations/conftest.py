"""Contract 1.3.0 (ADR 0008) fixtures: the WING-011 test world and HTTP app (real
PostgreSQL, rolled back per test) plus the connections, conversations and 1.3.0
routers. Helpers build device-side dating connections and 1.3.0 inbound events."""

import uuid
from collections.abc import Callable, Iterator
from typing import Any

import httpx
import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.connections import get_connection_service
from app.api.connections import router as connections_router
from app.api.conversations import router as conversations_router
from app.api.integrations import router as integrations_router
from app.services.connections import ConnectionService
from tests.automation.conftest import (  # noqa: F401
    World,
    alembic_config,
    build_app,
    capabilities_doc,
    create_scratch_database,
    drop_scratch_database,
    make_user,
    make_world,
    migrated_db_url,
    pg_server_url,
    session,
)

INSTALL = "00000000-0000-4000-8000-0000000000aa"


@pytest.fixture
def make_v13_client(
    session: AsyncSession,  # noqa: F811
) -> Iterator[Callable[..., httpx.AsyncClient]]:
    original_commit = session.commit

    async def flush_only() -> None:
        await session.flush()

    session.commit = flush_only  # type: ignore[method-assign]

    def factory(world: World, *, env: str = "test") -> httpx.AsyncClient:
        app = build_app(session, world)
        app.include_router(connections_router)
        app.include_router(conversations_router)
        app.include_router(integrations_router)
        app.dependency_overrides[get_connection_service] = lambda: ConnectionService(
            env=env, now=world.clock
        )
        return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")

    yield factory
    session.commit = original_commit  # type: ignore[method-assign]


def dating_caps(
    platform: str = "hinge",
    *,
    send: str = "UNSUPPORTED",
    swipe: str = "UNSUPPORTED",
    profile_read: str | None = None,
    version: str = "0.1.0",
) -> dict[str, Any]:
    def cap(status: str) -> dict[str, Any]:
        out: dict[str, Any] = {"status": status}
        if status == "VERIFIED":
            out |= {"evidence": "client says so", "verified_at": "2026-11-01T00:00:00Z"}
        return out

    caps: dict[str, Any] = {
        "read": cap("UNVERIFIED"),
        "draft": cap("UNSUPPORTED"),
        "send": cap(send),
        "swipe": cap(swipe),
        "calendar_write": cap("UNSUPPORTED"),
    }
    if profile_read is not None:
        caps["profile_read"] = cap(profile_read)
    return {
        "schema_version": "connector_capabilities.v1",
        "platform": platform,
        "connector_version": version,
        "capabilities": caps,
        "assessed_at": "2026-11-01T00:00:00Z",
    }


def consent(platform: str = "hinge") -> dict[str, Any]:
    return {
        "platform": platform,
        "consent_text_version": "d021-v1",
        "accepted_at": "2026-11-02T14:59:00Z",
        "ban_risk_acknowledged": True,
    }


def pair_body(
    platform: str = "hinge",
    *,
    transport: str | None = "unofficial_api",
    with_consent: bool = True,
    install: str = INSTALL,
    **caps_kw: Any,
) -> dict[str, Any]:
    body: dict[str, Any] = {
        "platform": platform,
        "display_name": f"{platform} test",
        "device_install_id": install,
        "capabilities": dating_caps(platform, **caps_kw),
    }
    if transport is not None:
        body["transport"] = transport
    if transport == "unofficial_api":
        body["experimental"] = True
        if with_consent:
            body["experimental_consent"] = consent(platform)
    return body


def inbound13(
    world: World,
    *,
    text: str = "Hey, coffee this week?",
    kind: str | None = None,
    identity: str | None = None,
    truncated: bool | None = None,
    content_hidden: bool | None = None,
    source: str | None = None,
    sender: str = "match-77",
    label: str | None = "Riley",
    thread: str | None = "match-thread-77",
    external_message_id: str | None = None,
) -> dict[str, Any]:
    """A ``message.received`` envelope with the 1.3.0 payload fields."""
    payload: dict[str, Any] = {
        "sender": {"external_contact_id": sender, "display_name": label},
        "text": "" if content_hidden else text,
    }
    for name, value in (
        ("kind", kind),
        ("identity", identity),
        ("truncated", truncated),
        ("content_hidden", content_hidden),
        ("source", source),
    ):
        if value is not None:
            payload[name] = value
    if label is not None:
        payload["sender_label"] = label
    return {
        "schema_version": "connector_event.v1",
        "event_id": str(uuid.uuid4()),
        "type": "message.received",
        "platform": world.platform,
        "connection_id": str(world.connection_id),
        "user_id": str(world.scope.user_id),
        "external_message_id": external_message_id or f"ext-{uuid.uuid4().hex}",
        "external_thread_id": thread,
        "occurred_at": (world.clock.now.isoformat()).replace("+00:00", "Z"),
        "payload": payload,
    }
