#!/usr/bin/env bash
# WING-025 backend end-to-end acceptance suite: ONE command, locally and in CI.
#
#   scripts/e2e/run.sh [pytest args...]        e.g. scripts/e2e/run.sh -k x2
#
# What it does:
#   1. Database. WINGMAN_E2E_DATABASE_URL set (CI: the Postgres 16 service): it must point
#      at an EMPTY database you are happy to have migrated and written to. Unset: a
#      throwaway Postgres 16 cluster is started from local server binaries (same helper as
#      the DB tests, backend/tests/db/pg_cluster.py) and deleted afterwards.
#   2. `alembic upgrade head` on it (real migrations, no create_all).
#   3. pytest tests/e2e: the conftest starts the backend exactly as scripts/dev-run.sh does
#      (API + worker in one process, dev auth stub, labeled dev mock LLM, mock connector),
#      drives it over HTTP, kills/restarts it for the restart scenario, and checks the
#      run-wide zero-duplicate-sends invariant last.
#
# Artifacts (server log, junit XML, zero_duplicate_sends.json) go to
# WINGMAN_E2E_ARTIFACTS (default tests/e2e/.artifacts, git-ignored by the suite's own
# .gitignore). Never point this at a database with data you care about.
set -euo pipefail
self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
repo="$(cd "$(dirname "$self")/../.." && pwd)"
cd "$repo/backend"
uv sync --quiet

if [[ -z "${WINGMAN_E2E_DATABASE_URL:-}" ]]; then
  echo "WINGMAN_E2E_DATABASE_URL not set: starting a throwaway Postgres cluster." >&2
  exec uv run python - "$self" "$@" <<'PY'
import os
import subprocess
import sys

from tests.db.pg_cluster import ClusterUnavailableError, temporary_cluster

try:
    with temporary_cluster() as url:
        env = {**os.environ, "WINGMAN_E2E_DATABASE_URL": url}
        sys.exit(subprocess.call(["bash", *sys.argv[1:]], env=env))  # noqa: S603,S607
except ClusterUnavailableError as exc:
    print(f"e2e: no database available: {exc}", file=sys.stderr)
    sys.exit(2)
PY
fi

export WINGMAN_DATABASE_URL="$WINGMAN_E2E_DATABASE_URL"
export WINGMAN_ENV=dev
export WINGMAN_LLM_PROVIDER=mock
export WINGMAN_AUTH_DEV_STUB_ENABLED=true
# A fresh random dev-stub secret per run (never committed, never reused).
export WINGMAN_AUTH_DEV_SECRET="${WINGMAN_AUTH_DEV_SECRET:-$(uv run python -c 'import secrets; print(secrets.token_urlsafe(48))')}"
export WINGMAN_E2E_ARTIFACTS="${WINGMAN_E2E_ARTIFACTS:-$repo/tests/e2e/.artifacts}"
mkdir -p "$WINGMAN_E2E_ARTIFACTS"
: > "$WINGMAN_E2E_ARTIFACTS/server.log"

if curl -fsS -m 2 http://127.0.0.1:8000/health >/dev/null 2>&1; then
  echo "e2e: something already answers on 127.0.0.1:8000; stop it first" >&2
  exit 2
fi

echo "==> ruff (tests/e2e)"
uv run ruff check "$repo/tests/e2e" --config "$repo/tests/e2e/ruff.toml"
uv run ruff format --check "$repo/tests/e2e" --config "$repo/tests/e2e/ruff.toml"

echo "==> alembic upgrade head"
uv run alembic upgrade head

echo "==> pytest tests/e2e"
cd "$repo"
set +e
PYTHONPATH="$repo" "$repo/backend/.venv/bin/python" -m pytest tests/e2e \
  -p no:cacheprovider -o asyncio_mode=auto -rA --durations=0 \
  --junitxml="$WINGMAN_E2E_ARTIFACTS/junit.xml" "$@"
code=$?
set -e
echo "==> server log: $WINGMAN_E2E_ARTIFACTS/server.log"
if [[ -f "$WINGMAN_E2E_ARTIFACTS/zero_duplicate_sends.json" ]]; then
  echo "==> zero-duplicate-sends summary:"
  cat "$WINGMAN_E2E_ARTIFACTS/zero_duplicate_sends.json"
fi
if [[ $code -eq 0 ]]; then echo "E2E SUITE PASSED"; else echo "E2E SUITE FAILED (exit $code)"; fi
exit $code
