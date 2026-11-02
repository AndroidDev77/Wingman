#!/usr/bin/env bash
# E2E ONLY (WING-032): scripts/dev-run.sh plus the scripted date-proposal answers of
# e2e_scripted_server.py (labeled "[e2e scripted]"; everything else is the dev mock).
# API and worker in ONE process on http://127.0.0.1:8000. Refuses WINGMAN_ENV=prod.
# Needs a migrated database (WINGMAN_DATABASE_URL); started by tests/e2e/platforms/conftest.py.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
cd "$repo/backend"
if [[ "${WINGMAN_ENV:-dev}" == "prod" ]]; then
  echo "scripted-dev-run.sh is for tests only (WINGMAN_ENV=prod)" >&2
  exit 1
fi
export PYTHONPATH="$repo/backend${PYTHONPATH:+:$PYTHONPATH}"
exec uv run --quiet uvicorn e2e_scripted_server:app --app-dir "$here" --host 127.0.0.1 --port 8000
