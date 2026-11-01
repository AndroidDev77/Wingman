#!/usr/bin/env bash
# Lint, typecheck and test the backend. Exits non-zero on the first failure.
set -euo pipefail
cd "$(dirname "$0")/../backend"
uv sync --quiet
uv run ruff check .
uv run ruff format --check .
uv run mypy app
uv run pytest -q
