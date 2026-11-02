# Wingman AI

Android-first AI dating assistant. It drafts personalized replies and, only on verified connectors the user has enabled, can send them automatically under an approval policy the user controls.

> **Status: private beta v0.1.0-beta.1, LIMITED.** Invited adult testers, their own conversations, unpaid.
> **Start here: [`docs/beta/`](docs/beta/README.md).**
>
> | Works (tested in CI) | Does not work yet |
> |---|---|
> | Onboarding (intent, style, autonomy; Manual by default; the emulator test drives one intent), inbox, approve / edit / reject drafts, Activity timeline, pause and takeover, unpair, zero duplicate sends under failure injection. The Android app drives the real backend on a CI emulator | **SMS on a real phone is UNVERIFIED** (gate G5 not met, I-003): emulator only, every SMS reply needs approval, no SMS autopilot. Draft quality with a real LLM UNVERIFIED (I-009). Sign-in: Auth0 + Google configured for the beta tenant but UNVERIFIED on a device (I-014); the debug-only dev token is the fallback. No background sync (I-024). Tinder, Bumble, Hinge, iMessage and notification reply are **UNSUPPORTED** (not built) |
>
> **No paid launch without one reliable real message connector** ([go/no-go](docs/beta/release_checklist.md#0-go--no-go-summary)).
> Every connector capability is labeled **VERIFIED**, **UNVERIFIED** or **UNSUPPORTED**
> ([`docs/beta/known_limitations.md`](docs/beta/known_limitations.md)). Live task status: [`docs/STATUS.md`](docs/STATUS.md); ticket plan: [`TASK_INDEX.md`](TASK_INDEX.md).

## Private beta

| Doc | |
|---|---|
| [`docs/beta/README.md`](docs/beta/README.md) | Beta hub and tester terms |
| [`docs/beta/install.md`](docs/beta/install.md) | Install the APK, backend options, dev-token sign-in, SMS pairing, device matrix |
| [`docs/beta/known_limitations.md`](docs/beta/known_limitations.md) | Supported-platform table and known gaps |
| [`docs/beta/walkthrough.md`](docs/beta/walkthrough.md) | Screens and sample conversations |
| [`docs/beta/release_checklist.md`](docs/beta/release_checklist.md) | Operator checklist and go/no-go |
| [`docs/beta/release_notes.md`](docs/beta/release_notes.md) | What this release contains, with evidence |
| [`docs/beta/feedback_form.md`](docs/beta/feedback_form.md), [`metrics.md`](docs/beta/metrics.md), [`roadmap.md`](docs/beta/roadmap.md) | Feedback and incidents, success metrics, follow-ups |

## Repository layout

| Path | Contents | Owner |
|---|---|---|
| `backend/` | FastAPI service (Python 3.12+, uv) | backend tickets |
| `android/` | Kotlin + Jetpack Compose app | Android tickets |
| `contracts/` | OpenAPI and JSON schemas, the source of truth | WING-002 |
| `docs/` | Architecture, ADRs, status dashboard, decision log; `docs/beta/` | WING-002 / integrator / WING-026 |
| `tests/` | Repository-level suites: `tests/e2e` (WING-025), `tests/release` (WING-026) | WING-025 / WING-026 |
| `infra/` | Deployment configuration | WING-023 |
| `scripts/` | Developer and CI entry points; `scripts/release/` for beta builds | WING-001 / WING-023 / WING-026 |
| `tasks/` | One ticket per agent assignment | integrator |

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Python | 3.12+ | backend |
| [uv](https://docs.astral.sh/uv/) | 0.4+ | backend dependencies |
| Docker + Compose | recent | local Postgres (Redis is started but unused) |
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
| `scripts/dev-run.sh` | **Dev only.** API + worker in one process with the dev mock LLM (labeled `[dev mock]` drafts) and the mock connector; needs a migrated database |
| `scripts/worker-run.sh` | Background worker (`python -m app.worker`) |
| `scripts/e2e/run.sh` | End-to-end suite (flows, failure injection, zero duplicate sends) on a throwaway Postgres |
| `cd backend && uv run python -m app.security.dev_stub --sub alice` | Mint a DEV-ONLY token (needs `WINGMAN_AUTH_DEV_STUB_ENABLED=true` and `WINGMAN_AUTH_DEV_SECRET`) |
| `scripts/release/check.sh` | Pre-release gate: backend checks, contracts, migrations, E2E; stops at the first failure. `--paid` refuses while G5 is blocked |
| `scripts/release/build.sh [--api-base-url URL]` | Versioned beta APK (versionName/versionCode via Gradle properties) and backend image tag, from `scripts/release/version.env` |
| `scripts/release/version.sh show\|bump beta\|patch\|minor` | Show or bump the beta version |
| `python3 -m unittest discover -s tests/release` | Tests for the release scripts |
| `docker compose --profile backend up --build` | Run `migrate` (one-shot `alembic upgrade head`), then the API and the worker in containers next to Postgres and Redis. With the `mock` LLM provider only `scripts/dev-run.sh` produces drafts |

The backend runs entirely with no API keys: `WINGMAN_LLM_PROVIDER=mock` is the default. A real OpenAI-compatible provider is configurable but UNVERIFIED (I-009).

CI: [`.github/workflows/ci.yml`](.github/workflows/ci.yml) (five jobs) and [`.github/workflows/e2e.yml`](.github/workflows/e2e.yml) (backend E2E, Android emulator E2E) run on every PR and on `main`; all seven must be green to merge. Deployment and the environment variable reference: [`docs/deployment.md`](docs/deployment.md) (the Azure path is an UNVERIFIED sample; nothing has been deployed).

## Working on a ticket

Read [`AGENTS.md`](AGENTS.md) first. In short: one ticket, one `wing-###-short-name` branch, one PR, with the handoff report from `AGENT_RULES.md` in the PR description.
