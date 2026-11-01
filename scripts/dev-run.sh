#!/usr/bin/env bash
# DEV ONLY: API and background worker in ONE process on http://127.0.0.1:8000, so the
# worker shares the API's in-memory MockConnector (I-016) and delayed/retried mock sends
# go out. Refuses WINGMAN_ENV=prod. Needs a migrated database (WINGMAN_DATABASE_URL).
set -euo pipefail
cd "$(dirname "$0")/../backend"
uv sync --quiet
exec uv run uvicorn app.worker.dev_server:app --host 127.0.0.1 --port 8000
