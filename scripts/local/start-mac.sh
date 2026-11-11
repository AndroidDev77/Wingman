#!/usr/bin/env bash
# Start Wingman's backend locally on macOS (DEV ONLY), then open its API page.
# Usage: bash scripts/local/start-mac.sh        (Ctrl+C to stop)
# - Postgres: Docker Desktop if running, otherwise Homebrew postgresql@16 (own data dir, port 5433).
# - Backend: scripts/dev-run.sh (API + worker, mock LLM, mock connector) on http://127.0.0.1:8000
# - Auth: the DEV-ONLY token stub, with a random secret kept in backend/.env (git-ignored).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

# 1. uv (Python package manager)
if ! command -v uv >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then say "Installing uv with Homebrew"; brew install uv
  else echo "uv is missing. Install it: https://docs.astral.sh/uv/getting-started/installation/ then re-run." >&2; exit 1; fi
fi

# 2. Postgres
DB_URL=""
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  say "Starting Postgres in Docker"
  docker compose up -d postgres
  for _ in $(seq 1 30); do docker compose exec -T postgres pg_isready -U wingman >/dev/null 2>&1 && break; sleep 1; done
  DB_URL="postgresql+asyncpg://wingman:wingman@localhost:5432/wingman"
elif command -v brew >/dev/null 2>&1; then
  PGBIN="$(brew --prefix)/opt/postgresql@16/bin"
  if [[ ! -x "$PGBIN/pg_ctl" ]]; then say "Installing postgresql@16 with Homebrew"; brew install postgresql@16; fi
  PGDATA="$ROOT/.local-pg"
  if [[ ! -d "$PGDATA" ]]; then
    say "Creating a local Postgres cluster in .local-pg"
    "$PGBIN/initdb" -D "$PGDATA" -U wingman --auth=trust >/dev/null
  fi
  if ! "$PGBIN/pg_ctl" -D "$PGDATA" status >/dev/null 2>&1; then
    say "Starting Postgres on port 5433"
    "$PGBIN/pg_ctl" -D "$PGDATA" -o "-p 5433 -k /tmp" -l "$PGDATA/server.log" start >/dev/null
    sleep 2
  fi
  "$PGBIN/psql" -h localhost -p 5433 -U wingman -d postgres -tc "select 1 from pg_database where datname='wingman'" | grep -q 1 \
    || "$PGBIN/createdb" -h localhost -p 5433 -U wingman wingman
  DB_URL="postgresql+asyncpg://wingman@localhost:5433/wingman"
else
  echo "Need Postgres: start Docker Desktop, or install Homebrew (https://brew.sh), then re-run." >&2; exit 1
fi

# 3. backend/.env (dev only; git-ignored)
ENVF="$ROOT/backend/.env"
if [[ ! -f "$ENVF" ]]; then
  say "Writing backend/.env (dev settings + random dev-token secret)"
  cat > "$ENVF" <<ENV
WINGMAN_ENV=dev
WINGMAN_DATABASE_URL=$DB_URL
WINGMAN_LLM_PROVIDER=mock
WINGMAN_AUTH_DEV_STUB_ENABLED=true
WINGMAN_AUTH_DEV_SECRET=$(openssl rand -hex 32)
ENV
else
  sed -i '' "s#^WINGMAN_DATABASE_URL=.*#WINGMAN_DATABASE_URL=$DB_URL#" "$ENVF"
fi

# 4. Dependencies + database schema
say "Installing backend dependencies"
(cd backend && uv sync --quiet)
say "Migrating the database"
(cd backend && uv run alembic upgrade head)

# 5. A dev token for the API page's Authorize button
say "Your DEV token (paste into Authorize on the API page; valid ~12h):"
(cd backend && uv run python -m app.security.dev_stub --sub chris) || true

# 6. Open the API page once it's up, then run the backend in the foreground
( for _ in $(seq 1 60); do curl -sf http://127.0.0.1:8000/health >/dev/null && open http://127.0.0.1:8000/docs && exit; sleep 1; done ) &
say "Starting the backend on http://127.0.0.1:8000  (Ctrl+C to stop)"
exec bash scripts/dev-run.sh
