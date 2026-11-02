"""WING-030 companion tests run on the contract 1.3 fixtures (real PostgreSQL, rolled back
per test, HTTP through the real routers). Re-exported so pytest finds them here."""

from tests.integrations.conftest import (  # noqa: F401
    alembic_config,
    create_scratch_database,
    drop_scratch_database,
    make_v13_client,
    migrated_db_url,
    pg_server_url,
    session,
)
