#!/usr/bin/env bash
# Validate OpenAPI, JSON Schemas and examples. Exits non-zero on failure.
# Requires uv (dependencies are pinned inline in validate.py).
set -euo pipefail
exec uv run --quiet --no-project --script "$(dirname "$0")/validate.py" "$@"
