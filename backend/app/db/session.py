"""Async engine and session factory.

Usage (FastAPI, wired by the integrator / WING-004)::

    from app.db.session import get_session

    @router.get(...)
    async def handler(session: AsyncSession = Depends(get_session, scope="function")) -> ...:
        repo = ConversationRepository(session)
        ...

``get_session`` yields one session per request and commits when the handler returns
normally, rolling back on any exception. Outside FastAPI use :func:`session_scope`.
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from functools import lru_cache

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.config import get_settings


def create_engine(database_url: str | None = None, *, echo: bool = False) -> AsyncEngine:
    """Create an asyncpg engine. Every connection runs with ``timezone = 'UTC'``."""
    url = database_url if database_url is not None else get_settings().database_url
    return create_async_engine(
        url,
        echo=echo,
        pool_pre_ping=True,
        connect_args={"server_settings": {"timezone": "UTC"}},
    )


def create_session_factory(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    # expire_on_commit=False: returned ORM objects stay readable after commit, which
    # API handlers need when serialising a response.
    return async_sessionmaker(engine, expire_on_commit=False, autoflush=False)


@lru_cache
def get_engine() -> AsyncEngine:
    """Process-wide engine built from ``Settings.database_url``."""
    return create_engine()


@lru_cache
def get_session_factory() -> async_sessionmaker[AsyncSession]:
    return create_session_factory(get_engine())


@asynccontextmanager
async def session_scope(
    factory: async_sessionmaker[AsyncSession] | None = None,
) -> AsyncIterator[AsyncSession]:
    """Transactional scope: commit on success, roll back on error."""
    maker = factory if factory is not None else get_session_factory()
    async with maker() as session:
        try:
            yield session
            await session.commit()
        except BaseException:
            await session.rollback()
            raise


async def get_session() -> AsyncIterator[AsyncSession]:
    """FastAPI dependency: one transactional session per request."""
    async with session_scope() as session:
        yield session


async def dispose_engine() -> None:
    """Close pooled connections (call from the app lifespan shutdown)."""
    if get_engine.cache_info().currsize:
        await get_engine().dispose()
    get_session_factory.cache_clear()
    get_engine.cache_clear()
