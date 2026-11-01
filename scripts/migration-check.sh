#!/usr/bin/env bash
# Prove the Alembic history works on an EMPTY database:
#   upgrade head -> (no model drift) -> downgrade base -> (no tables left) -> upgrade head
#
# Database selection:
#   - WINGMAN_DATABASE_URL set (CI, or your own scratch DB): used as-is. It must be an
#     empty database you are happy to have dropped back to nothing.
#   - otherwise: a throwaway Postgres cluster is started from local server binaries
#     (same helper as the DB tests, backend/tests/db/pg_cluster.py) and deleted after.
# Never point this at a database with data you care about: `downgrade base` drops
# every table.
set -euo pipefail
self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$self")/../backend"
uv sync --quiet

if [[ -z "${WINGMAN_DATABASE_URL:-}" ]]; then
  echo "WINGMAN_DATABASE_URL not set: starting a throwaway Postgres cluster." >&2
  exec uv run python - "$self" <<'PY'
import os
import subprocess
import sys

from tests.db.pg_cluster import ClusterUnavailableError, temporary_cluster

try:
    with temporary_cluster() as url:
        env = {**os.environ, "WINGMAN_DATABASE_URL": url}
        sys.exit(subprocess.call(["bash", sys.argv[1]], env=env))  # noqa: S603,S607
except ClusterUnavailableError as exc:
    print(f"migration-check: no database available: {exc}", file=sys.stderr)
    sys.exit(2)
PY
fi

# Table count in the public schema, excluding Alembic's own bookkeeping table.
count_tables() {
  uv run python - <<'PY'
import asyncio

import asyncpg
from sqlalchemy.engine import make_url

from app.config import get_settings


async def main() -> None:
    dsn = make_url(get_settings().database_url).set(drivername="postgresql")
    conn = await asyncpg.connect(dsn.render_as_string(hide_password=False))
    try:
        n = await conn.fetchval(
            "SELECT count(*) FROM information_schema.tables "
            "WHERE table_schema = 'public' AND table_name <> 'alembic_version'"
        )
    finally:
        await conn.close()
    print(n)


asyncio.run(main())
PY
}

step() { printf '\n==> %s\n' "$*"; }

step "alembic heads (must be exactly one)"
heads=$(uv run alembic heads)
echo "$heads"
if [[ $(grep -c . <<<"$heads") -ne 1 ]]; then
  echo "FAIL: expected exactly one Alembic head" >&2
  exit 1
fi

before=$(count_tables)
if [[ "$before" -ne 0 ]]; then
  echo "FAIL: target database is not empty ($before tables); use a fresh database" >&2
  exit 1
fi

step "alembic upgrade head"
uv run alembic upgrade head
step "alembic check (models match migrations)"
uv run alembic check
tables=$(count_tables)
echo "tables at head: $tables"

step "alembic downgrade base"
uv run alembic downgrade base
left=$(count_tables)
if [[ "$left" -ne 0 ]]; then
  echo "FAIL: downgrade base left $left tables behind" >&2
  exit 1
fi

step "alembic upgrade head (again)"
uv run alembic upgrade head
again=$(count_tables)
if [[ "$again" -ne "$tables" ]]; then
  echo "FAIL: second upgrade produced $again tables, first produced $tables" >&2
  exit 1
fi
uv run alembic current

printf '\nMIGRATION CHECK PASSED (%s tables at head)\n' "$tables"
