#!/usr/bin/env bash
# Run the backend locally with auto-reload on http://localhost:8000
set -euo pipefail
cd "$(dirname "$0")/../backend"
uv sync --quiet
exec uv run uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
