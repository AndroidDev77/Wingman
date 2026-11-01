"""Async Alembic environment (asyncpg).

URL precedence: ``sqlalchemy.url`` set on the Alembic config (tests) >
``Settings.database_url`` (``WINGMAN_DATABASE_URL``).

Migration ordering is integrator-owned (AGENTS.md); WING-003 owns the models and the
initial revision. Other tickets request schema changes rather than editing migrations.
"""

import asyncio
from logging.config import fileConfig

from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.pool import NullPool

import app.db.models  # noqa: F401  (registers every table on Base.metadata)
from alembic import context
from app.config import get_settings
from app.db.base import Base

config = context.config
if config.config_file_name is not None and config.attributes.get("configure_logger", True):
    fileConfig(config.config_file_name, disable_existing_loggers=False)

target_metadata = Base.metadata


def _url() -> str:
    return config.get_main_option("sqlalchemy.url") or get_settings().database_url


def run_migrations_offline() -> None:
    context.configure(
        url=_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def _do_run(connection: Connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata, compare_type=True)
    with context.begin_transaction():
        context.run_migrations()


async def run_migrations_online() -> None:
    engine = create_async_engine(
        _url(), poolclass=NullPool, connect_args={"server_settings": {"timezone": "UTC"}}
    )
    try:
        async with engine.connect() as connection:
            await connection.run_sync(_do_run)
    finally:
        await engine.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    asyncio.run(run_migrations_online())
