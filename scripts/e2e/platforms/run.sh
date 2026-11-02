#!/usr/bin/env bash
# WING-032 cross-platform mock E2E suite: ONE command, locally and in CI.
#
#   scripts/e2e/platforms/run.sh [pytest args...]        e.g. scripts/e2e/platforms/run.sh -k imessage
#
# Same harness as WING-025 (scripts/e2e/run.sh): a real backend (API + worker in one process,
# dev auth stub with a random secret per run), real Postgres 16 migrated by Alembic, the
# labeled dev mock LLM (plus the "[e2e scripted]" date answers of e2e_scripted_server.py),
# driven over HTTP with synthetic, device-shaped connector_event.v1 envelopes for every
# platform: SMS, Tinder / Bumble / Hinge notifications, Hinge experimental, iMessage companion,
# profile discovery. NO real account, device, Mac or dating service is touched: a pass here is
# "PASSED WITH MOCK", never "PASSED ON DEVICE" (docs/integrations/cross_platform_test_matrix.md).
#
#   1. Database. WINGMAN_E2E_DATABASE_URL set (CI: a Postgres 16 service): an EMPTY database
#      that will be migrated and written to. Unset: a throwaway Postgres 16 cluster from local
#      server binaries (backend/tests/db/pg_cluster.py), deleted afterwards.
#   2. alembic upgrade head.
#   3. pytest tests/e2e/platforms (WINGMAN_E2E_PLATFORMS=1; the WING-025 run ignores this
#      directory), last the run-wide zero-duplicate-sends and no-false-send-claim checks.
#
# Artifacts (server.log, junit.xml, platform_invariants.json, scenario_results.json) go to
# WINGMAN_E2E_ARTIFACTS (default tests/e2e/platforms/.artifacts, git-ignored). Port 8000 must
# be free. Never point this at a database with data you care about.
set -euo pipefail
self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
repo="$(cd "$(dirname "$self")/../../.." && pwd)"
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
    print(f"e2e platforms: no database available: {exc}", file=sys.stderr)
    sys.exit(2)
PY
fi

export WINGMAN_DATABASE_URL="$WINGMAN_E2E_DATABASE_URL"
export WINGMAN_ENV=dev
export WINGMAN_LLM_PROVIDER=mock
export WINGMAN_AUTH_DEV_STUB_ENABLED=true
export WINGMAN_E2E_PLATFORMS=1
# A fresh random dev-stub secret per run (never committed, never reused).
export WINGMAN_AUTH_DEV_SECRET="${WINGMAN_AUTH_DEV_SECRET:-$(uv run python -c 'import secrets; print(secrets.token_urlsafe(48))')}"
export WINGMAN_E2E_ARTIFACTS="${WINGMAN_E2E_ARTIFACTS:-$repo/tests/e2e/platforms/.artifacts}"
mkdir -p "$WINGMAN_E2E_ARTIFACTS"
: > "$WINGMAN_E2E_ARTIFACTS/server.log"

if curl -fsS -m 2 http://127.0.0.1:8000/health >/dev/null 2>&1; then
  echo "e2e platforms: something already answers on 127.0.0.1:8000; stop it first" >&2
  exit 2
fi

echo "==> ruff (tests/e2e/platforms, scripts/e2e/platforms)"
uv run ruff check "$repo/tests/e2e/platforms" "$repo/scripts/e2e/platforms" --config "$repo/tests/e2e/ruff.toml"
uv run ruff format --check "$repo/tests/e2e/platforms" "$repo/scripts/e2e/platforms" --config "$repo/tests/e2e/ruff.toml"

echo "==> alembic upgrade head"
uv run alembic upgrade head

echo "==> pytest tests/e2e/platforms"
cd "$repo"
set +e
PYTHONPATH="$repo" "$repo/backend/.venv/bin/python" -m pytest tests/e2e/platforms \
  -p no:cacheprovider -o asyncio_mode=auto -rA --durations=0 \
  --junitxml="$WINGMAN_E2E_ARTIFACTS/junit.xml" "$@"
code=$?
set -e
echo "==> server log: $WINGMAN_E2E_ARTIFACTS/server.log"
for f in platform_invariants.json scenario_results.json; do
  if [[ -f "$WINGMAN_E2E_ARTIFACTS/$f" ]]; then
    echo "==> $f:"
    cat "$WINGMAN_E2E_ARTIFACTS/$f"
  fi
done
if [[ $code -eq 0 ]]; then
  echo "PLATFORM E2E SUITE PASSED (mock only: PASSED WITH MOCK, nothing ran on a device)"
else
  echo "PLATFORM E2E SUITE FAILED (exit $code)"
fi
exit $code
