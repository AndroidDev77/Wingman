"""Real-PostgreSQL fixtures for the persistence tests.

Server selection (session scope):

1. ``WINGMAN_TEST_DATABASE_URL`` if set: an asyncpg URL to a server where the role may
   ``CREATE DATABASE`` (scratch databases named ``wingman_test_*`` are created and
   dropped; the database in the URL itself is never modified).
2. Otherwise a throwaway cluster started with ``initdb`` + ``pg_ctl`` in a temp dir on a
   free port (see ``pg_cluster.py``), stopped and deleted afterwards.
3. If neither works the run **fails** with the reason. These tests never silently skip.

Each test gets ``session``: an ``AsyncSession`` on a database migrated to ``head``,
inside an outer transaction that is rolled back afterwards, so tests are isolated.
"""

import asyncio
import os
import uuid
from collections.abc import AsyncIterator, Iterator
from typing import Any

import asyncpg
import pytest
from alembic.config import Config
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine
from sqlalchemy.pool import NullPool

from alembic import command
from app.db.repositories import ConnectionRepository, UserRepository
from app.db.scope import UserScope
from tests.db.pg_cluster import ClusterUnavailableError, temporary_cluster

BACKEND_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REPO_ROOT = os.path.dirname(BACKEND_DIR)


def _raw_dsn(url: str) -> str:
    """SQLAlchemy URL -> plain libpq DSN for asyncpg."""
    return make_url(url).set(drivername="postgresql").render_as_string(hide_password=False)


def _with_database(url: str, database: str) -> str:
    return make_url(url).set(database=database).render_as_string(hide_password=False)


async def _admin(server_url: str, sql: str) -> None:
    conn = await asyncpg.connect(_raw_dsn(server_url))
    try:
        await conn.execute(sql)
    finally:
        await conn.close()


def create_scratch_database(server_url: str) -> str:
    name = f"wingman_test_{uuid.uuid4().hex[:12]}"
    try:
        asyncio.run(_admin(server_url, f'CREATE DATABASE "{name}"'))
    except Exception as exc:
        pytest.fail(
            f"cannot create a scratch database on the test server ({exc!r}); "
            "the WINGMAN_TEST_DATABASE_URL role needs CREATEDB",
            pytrace=False,
        )
    return _with_database(server_url, name)


def drop_scratch_database(server_url: str, db_url: str) -> None:
    name = make_url(db_url).database
    asyncio.run(_admin(server_url, f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)'))


def alembic_config(db_url: str) -> Config:
    cfg = Config(os.path.join(BACKEND_DIR, "alembic.ini"))
    cfg.set_main_option("script_location", os.path.join(BACKEND_DIR, "alembic"))
    cfg.set_main_option("sqlalchemy.url", db_url)
    cfg.attributes["configure_logger"] = False
    return cfg


@pytest.fixture(scope="session")
def pg_server_url() -> Iterator[str]:
    explicit = os.environ.get("WINGMAN_TEST_DATABASE_URL")
    if explicit:
        yield explicit
        return
    try:
        with temporary_cluster() as url:
            yield url
    except ClusterUnavailableError as exc:
        pytest.fail(
            "PostgreSQL is required for tests/db and none is available: "
            f"{exc}. Set WINGMAN_TEST_DATABASE_URL or install PostgreSQL server binaries.",
            pytrace=False,
        )


@pytest.fixture
def scratch_db_url(pg_server_url: str) -> Iterator[str]:
    """A brand-new, empty database (for migration tests)."""
    url = create_scratch_database(pg_server_url)
    try:
        yield url
    finally:
        drop_scratch_database(pg_server_url, url)


@pytest.fixture(scope="session")
def migrated_db_url(pg_server_url: str) -> Iterator[str]:
    """One database migrated to ``head``, shared by all tests (each test rolls back)."""
    url = create_scratch_database(pg_server_url)
    try:
        command.upgrade(alembic_config(url), "head")
        yield url
    finally:
        drop_scratch_database(pg_server_url, url)


@pytest.fixture
async def session(migrated_db_url: str) -> AsyncIterator[AsyncSession]:
    engine = create_async_engine(
        migrated_db_url,
        poolclass=NullPool,
        connect_args={"server_settings": {"timezone": "UTC"}},
    )
    try:
        async with engine.connect() as conn:
            outer = await conn.begin()
            db = AsyncSession(
                bind=conn,
                expire_on_commit=False,
                autoflush=False,
                join_transaction_mode="create_savepoint",
            )
            try:
                yield db
            finally:
                await db.close()
                await outer.rollback()
    finally:
        await engine.dispose()


# --------------------------------------------------------------------------- data helpers


def capabilities_doc(platform: str, send: str = "VERIFIED") -> dict[str, Any]:
    def cap(status: str) -> dict[str, Any]:
        out: dict[str, Any] = {"status": status}
        if status == "VERIFIED":
            out |= {"evidence": "tests/db (simulated)", "verified_at": "2026-11-01T00:00:00Z"}
        return out

    return {
        "schema_version": "connector_capabilities.v1",
        "platform": platform,
        "connector_version": "test-1",
        "capabilities": {
            "read": cap("VERIFIED"),
            "draft": cap("UNSUPPORTED"),
            "send": cap(send),
            "swipe": cap("UNSUPPORTED"),
            "calendar_write": cap("UNSUPPORTED"),
        },
        "assessed_at": "2026-11-01T00:00:00Z",
    }


async def make_user(session: AsyncSession, subject: str | None = None) -> UserScope:
    user = await UserRepository(session).get_or_create_by_auth(
        "https://issuer.test", subject or f"sub-{uuid.uuid4().hex}"
    )
    return UserScope(user.id)


async def make_connection(
    session: AsyncSession, scope: UserScope, platform: str = "mock"
) -> uuid.UUID:
    conn = await ConnectionRepository(session, scope).create(
        platform=platform, display_name=f"{platform} test", capabilities=capabilities_doc(platform)
    )
    return conn.id
