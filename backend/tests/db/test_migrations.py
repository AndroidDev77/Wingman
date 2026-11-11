"""Alembic: clean up -> down -> up, single head, and model/migration parity."""

import asyncio
from typing import Any

import asyncpg
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.pool import NullPool

from alembic import command
from app.db.base import Base
from app.db.enums import CONTRACT_ENUMS
from tests.db.conftest import _raw_dsn, alembic_config

EXPECTED_TABLES = {
    "users",
    "user_preferences",
    "style_profiles",
    "platform_connections",
    "contacts",
    "conversations",
    "messages",
    "conversation_memories",
    "agent_actions",
    "automation_rules",
    "date_proposals",
    "subscriptions",
    "usage_events",
    "idempotency_keys",
    # WING-011 (D-010: amended into 0001).
    "drafts",
    "approvals",
    "outbox_instructions",
    "connector_event_ingests",
    # Contract 1.3.0 (ADR 0008; D-010: amended into 0001).
    "inbound_notices",
    "assisted_deliveries",
    "identity_links",
    "conversation_profile_links",
    "profiles",
    "profile_evaluations",
    "profile_actions",
}


async def _snapshot(url: str) -> dict[str, set[str]]:
    conn = await asyncpg.connect(_raw_dsn(url))
    try:
        tables = await conn.fetch(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
            " AND tablename <> 'alembic_version'"
        )
        enums = await conn.fetch(
            "SELECT t.typname FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace"
            " WHERE n.nspname = 'public' AND t.typtype = 'e'"
        )
        indexes = await conn.fetch("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
        version = await conn.fetch(
            "SELECT version_num FROM alembic_version"
            if await conn.fetchval("SELECT to_regclass('public.alembic_version') IS NOT NULL")
            else "SELECT NULL::text AS version_num WHERE false"
        )
        return {
            "tables": {r["tablename"] for r in tables},
            "enums": {r["typname"] for r in enums},
            "indexes": {r["indexname"] for r in indexes},
            "version": {r["version_num"] for r in version},
        }
    finally:
        await conn.close()


def test_single_linear_head() -> None:
    script = ScriptDirectory.from_config(alembic_config("postgresql+asyncpg://unused/unused"))
    heads = script.get_heads()
    assert len(heads) == 1
    revisions = list(script.walk_revisions())
    assert len(revisions) == 1, "WING-003 ships exactly one initial migration"


def test_upgrade_downgrade_upgrade_on_clean_db(scratch_db_url: str) -> None:
    cfg = alembic_config(scratch_db_url)
    empty = asyncio.run(_snapshot(scratch_db_url))
    assert empty["tables"] == set() and empty["enums"] == set()

    command.upgrade(cfg, "head")
    up = asyncio.run(_snapshot(scratch_db_url))
    assert up["tables"] == EXPECTED_TABLES
    assert up["enums"] == {ce.pg_name for ce in CONTRACT_ENUMS}
    assert up["version"] == {"0001"}

    command.downgrade(cfg, "base")
    down = asyncio.run(_snapshot(scratch_db_url))
    assert down["tables"] == set(), "downgrade must drop every table"
    assert down["enums"] == set(), "downgrade must drop every enum type"
    assert down["indexes"] <= {"alembic_version_pkc"}
    assert down["version"] == set()

    command.upgrade(cfg, "head")
    again = asyncio.run(_snapshot(scratch_db_url))
    assert again == up


def test_models_match_migration(migrated_db_url: str) -> None:
    """Autogenerate against the migrated DB must find nothing to change."""

    async def diff() -> list[Any]:
        engine = create_async_engine(migrated_db_url, poolclass=NullPool)
        try:
            async with engine.connect() as conn:
                return await conn.run_sync(
                    lambda sync_conn: compare_metadata(
                        MigrationContext.configure(sync_conn, opts={"compare_type": True}),
                        Base.metadata,
                    )
                )
        finally:
            await engine.dispose()

    assert asyncio.run(diff()) == []
