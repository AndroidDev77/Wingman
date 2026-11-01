#!/usr/bin/env bash
# DEV ONLY: API and background worker in ONE process on http://127.0.0.1:8000, so the
# worker shares the API's in-memory MockConnector (I-016) and delayed/retried mock sends
# go out. Refuses WINGMAN_ENV=prod. Needs a migrated database (WINGMAN_DATABASE_URL).
# With the default WINGMAN_LLM_PROVIDER=mock, model calls are answered by the
# deterministic dev handler (app/ai/providers/dev_mock.py): drafts, analysis and memory
# work out of the box, and every mock draft is labeled "[dev mock]".
set -euo pipefail
cd "$(dirname "$0")/../backend"
uv sync --quiet
if [[ "${WINGMAN_ENV:-dev}" == "prod" ]]; then
  echo "dev-run.sh is for development only (WINGMAN_ENV=prod)" >&2
  exit 1
fi
exec uv run uvicorn app.worker.dev_server:app --host 127.0.0.1 --port 8000
