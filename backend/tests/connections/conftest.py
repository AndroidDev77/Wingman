"""Fixtures for contract 1.2.0 connection pairing (ADR 0007): the WING-011 test world
and HTTP app (real PostgreSQL, rolled back per test) plus the connections router, with a
``ConnectionService`` whose environment each test chooses."""

from collections.abc import Callable, Iterator

import httpx
import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.connections import get_connection_service
from app.api.connections import router as connections_router
from app.services.connections import ConnectionService
from tests.automation.conftest import (  # noqa: F401
    World,
    alembic_config,
    build_app,
    capabilities_doc,
    committed,
    committed_db_url,
    create_scratch_database,
    drop_scratch_database,
    make_user,
    migrated_db_url,
    pg_server_url,
    session,
)


@pytest.fixture
def make_conn_client(
    session: AsyncSession,  # noqa: F811
) -> Iterator[Callable[..., httpx.AsyncClient]]:
    """HTTP client with the automation routers and the connections router. ``env``
    selects the server environment (``mock`` pairing is refused in ``prod``)."""
    original_commit = session.commit

    async def flush_only() -> None:
        await session.flush()

    session.commit = flush_only  # type: ignore[method-assign]

    def factory(world: World, *, env: str = "test") -> httpx.AsyncClient:
        app = build_app(session, world)
        app.include_router(connections_router)
        app.dependency_overrides[get_connection_service] = lambda: ConnectionService(
            env=env, now=world.clock
        )
        transport = httpx.ASGITransport(app=app)
        return httpx.AsyncClient(transport=transport, base_url="http://test")

    yield factory
    session.commit = original_commit  # type: ignore[method-assign]
