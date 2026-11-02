"""WING-031 fixtures: the contract 1.3 test world and HTTP app (real PostgreSQL, rolled back
per test), plus helpers for dating connections, imports, decisions and execution."""

import dataclasses
import uuid
from collections.abc import Callable
from typing import Any

import httpx
import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import AgentActionType, ConnectionTransport
from app.db.models import AgentAction
from app.db.repositories import ConnectionRepository
from app.discovery import DiscoveryLimits
from app.services.preferences import PreferencesService, PreferencesUpdate
from tests.automation.conftest import World, capabilities_doc, headers, make_world
from tests.integrations.helpers import EXPERIMENTAL_INSTALL
from tests.matching.conftest import (  # noqa: F401
    alembic_config,
    create_scratch_database,
    drop_scratch_database,
    import_body,
    make_v13_client,
    migrated_db_url,
    pg_server_url,
    session,
)

VERIFIED_SWIPE = {
    "status": "VERIFIED",
    "evidence": "operator run #1 (test fixture, not a real device)",
    "verified_at": "2026-11-01T00:00:00Z",
}


async def dating_world(
    session: AsyncSession,  # noqa: F811
    *,
    platform: str = "hinge",
    swipe: str = "UNSUPPORTED",
    transport: ConnectionTransport = ConnectionTransport.NOTIFICATION,
) -> World:
    """A user with one paired device-side dating connection. ``swipe="VERIFIED"`` plays an
    operator having recorded evidence (no real platform has one; see swiping.md)."""
    world = await make_world(session, mode="manual")
    doc = capabilities_doc(platform)
    doc["capabilities"]["swipe"] = VERIFIED_SWIPE if swipe == "VERIFIED" else {"status": swipe}
    conn = await ConnectionRepository(session, world.scope).create(
        platform=platform,
        display_name=platform,
        capabilities=doc,
        device_install_id=EXPERIMENTAL_INSTALL,
        paired_at=world.clock.now,
        transport=transport,
        consent_text_version="d021-v1" if transport is ConnectionTransport.UNOFFICIAL_API else None,
        consent_accepted_at=world.clock.now
        if transport is ConnectionTransport.UNOFFICIAL_API
        else None,
    )
    return dataclasses.replace(world, connection_id=conn.id, platform=platform)


@pytest.fixture
def make_client(make_v13_client):  # type: ignore[no-untyped-def]  # noqa: F811
    """``make_v13_client`` plus optional WING-031 limits on the app."""

    def factory(world: World, *, limits: DiscoveryLimits | None = None) -> httpx.AsyncClient:
        client: httpx.AsyncClient = make_v13_client(world)
        if limits is not None:
            client._transport.app.state.discovery_limits = limits  # type: ignore[attr-defined]
        return client

    return factory


MakeClient = Callable[..., httpx.AsyncClient]


async def post_profile(client: httpx.AsyncClient, world: World, **kw: Any) -> dict[str, Any]:
    resp = await client.post("/v1/profiles", json=import_body(**kw), headers=headers(world.scope))
    assert resp.status_code in (200, 201), resp.text
    body: dict[str, Any] = resp.json()
    return body


async def decide(
    client: httpx.AsyncClient,
    world: World,
    pid: str,
    decision: str = "like",
    *,
    key: str | None = None,
    with_connection: bool = True,
) -> httpx.Response:
    body: dict[str, Any] = {"decision": decision}
    if with_connection:
        body["connection_id"] = str(world.connection_id)
    return await client.post(
        f"/v1/profiles/{pid}/decision", json=body, headers=headers(world.scope, key)
    )


async def execute(
    client: httpx.AsyncClient, world: World, aid: str, *, key: str | None = None
) -> httpx.Response:
    return await client.post(
        f"/v1/profile-actions/{aid}/execute", json={}, headers=headers(world.scope, key)
    )


async def enable_auto_swipe(client: httpx.AsyncClient, world: World) -> None:
    resp = await client.put(
        f"/v1/connections/{world.connection_id}/settings",
        json={"auto_swipe_enabled": True},
        headers=headers(world.scope),
    )
    assert resp.status_code == 200, resp.text


async def set_prefs(
    session: AsyncSession,  # noqa: F811
    world: World,
    mode: str = "serious",
    dealbreakers: list[str] | None = None,
) -> int:
    update = PreferencesUpdate.model_validate(
        {
            "intent": {"mode": mode},
            "dealbreakers": dealbreakers or [],
            "style": {},
            "automation": {"mode": "manual"},
        }
    )
    saved = await PreferencesService(session, world.scope).put_user_preferences(update)
    return saved.version


async def denials(session: AsyncSession, world: World) -> list[AgentAction]:  # noqa: F811
    rows = await session.scalars(
        select(AgentAction)
        .where(
            AgentAction.user_id == world.scope.user_id,
            AgentAction.type == AgentActionType.POLICY_BLOCKED,
        )
        .order_by(AgentAction.occurred_at, AgentAction.id)
    )
    return list(rows)


def new_id() -> str:
    return str(uuid.uuid4())
