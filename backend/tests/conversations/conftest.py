"""Fixtures for the conversation read routes (I-021): the WING-011 test world and HTTP
app (real PostgreSQL, rolled back per test) plus the conversations router."""

from collections.abc import Callable, Iterator

import httpx
import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.conversations import router as conversations_router
from tests.automation.conftest import (  # noqa: F401
    World,
    alembic_config,
    build_app,
    capabilities_doc,
    create_scratch_database,
    drop_scratch_database,
    make_user,
    migrated_db_url,
    pg_server_url,
    session,
)


@pytest.fixture
def make_conv_client(
    session: AsyncSession,  # noqa: F811
) -> Iterator[Callable[[World], httpx.AsyncClient]]:
    """HTTP client with the WING-011 routers and the conversations router. The ingest
    route's commit is a flush here so the test transaction survives."""
    original_commit = session.commit

    async def flush_only() -> None:
        await session.flush()

    session.commit = flush_only  # type: ignore[method-assign]

    def factory(world: World) -> httpx.AsyncClient:
        app = build_app(session, world)
        app.include_router(conversations_router)
        return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")

    yield factory
    session.commit = original_commit  # type: ignore[method-assign]
