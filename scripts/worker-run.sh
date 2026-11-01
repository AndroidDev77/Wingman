#!/usr/bin/env bash
# Run the background worker (I-015) locally against WINGMAN_DATABASE_URL (migrated).
#   scripts/worker-run.sh           # until Ctrl-C / SIGTERM
#   scripts/worker-run.sh --once    # one tick of every job, then exit
# It skips `mock` connections (I-016); use scripts/dev-run.sh to exercise the mock.
set -euo pipefail
cd "$(dirname "$0")/../backend"
uv sync --quiet
exec uv run python -m app.worker "$@"
