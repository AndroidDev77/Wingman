# Wingman AI

Android-first AI dating assistant. It drafts personalized replies and, only on verified connectors the user has enabled, can send them automatically under an approval policy the user controls.

> **Status:** early scaffold. See [`docs/STATUS.md`](docs/STATUS.md) for live task status and [`TASK_INDEX.md`](TASK_INDEX.md) for the ticket plan. No dating-app integration is functional; any connector is labeled **VERIFIED**, **UNVERIFIED**, or **UNSUPPORTED**.

## Repository layout

| Path | Contents | Owner |
|---|---|---|
| `backend/` | FastAPI service (Python 3.12+, uv) | backend tickets |
| `android/` | Kotlin + Jetpack Compose app | Android tickets |
| `contracts/` | OpenAPI and JSON schemas, the source of truth | WING-002 |
| `docs/` | Architecture, ADRs, status dashboard, decision log | WING-002 / integrator |
| `infra/` | Deployment configuration | WING-023 |
| `scripts/` | Developer and CI entry points | WING-001 / WING-023 |
| `tasks/` | One ticket per agent assignment | integrator |

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Python | 3.12+ | backend |
| [uv](https://docs.astral.sh/uv/) | 0.4+ | backend dependencies |
| Docker + Compose | recent | local Postgres and Redis (optional for now) |
| JDK | 17+ | Android build |
| Android SDK | platform 35, build-tools 35 | Android build |

The Android build needs the SDK located via `ANDROID_HOME` or `android/local.properties` (`sdk.dir=/path/to/sdk`). Android Studio sets this up for you.

## Setup

```bash
cp .env.example .env           # safe local defaults; .env is git-ignored
docker compose up -d           # Postgres 16 + Redis 7
```

## Common commands

| Command | What it does |
|---|---|
| `scripts/backend-check.sh` | Lint (ruff), format check, typecheck (mypy strict), unit tests |
| `scripts/backend-run.sh` | Run the API on http://127.0.0.1:8000 with reload |
| `curl localhost:8000/health` | Liveness check; returns `{"status":"ok",...}` |
| `scripts/migration-check.sh` | Alembic up/down/up on an empty database (throwaway cluster unless `WINGMAN_DATABASE_URL` is set) |
| `contracts/validate.sh` | Validate the OpenAPI document, JSON Schemas and examples |
| `scripts/docker-smoke.sh` | Build the backend image and smoke-test it (non-root, `/health`, `HEALTHCHECK`) |
| `scripts/android-build.sh` | Debug APK + JVM unit tests (exits 2 with `UNVERIFIED` if no SDK) |
| `docker compose --profile backend up --build` | Run the backend in a container next to Postgres and Redis. Run migrations with `docker compose run --rm backend alembic upgrade head` |

The backend runs entirely with no API keys: `WINGMAN_LLM_PROVIDER=mock` is the default.

CI: [`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs the same scripts on every PR and on `main`. Deployment and the environment variable reference: [`docs/deployment.md`](docs/deployment.md) (the Azure path is an UNVERIFIED sample; nothing has been deployed).

## Working on a ticket

Read [`AGENTS.md`](AGENTS.md) first. In short: one ticket, one `wing-###-short-name` branch, one PR, with the handoff report from `AGENT_RULES.md` in the PR description.
