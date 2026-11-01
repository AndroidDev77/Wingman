# Deployment, CI and environments

Owner: WING-023. This page covers the developer commands, what CI checks, the backend
container image, every environment variable, how secrets are loaded, and a reference
deployment to Azure Container Apps.

> **Deployment status: nothing has been deployed.** No Wingman environment exists on
> Azure or anywhere else. Everything under [Reference deployment](#reference-deployment-azure-container-apps)
> and `infra/azure/` is **sample configuration, UNVERIFIED**: it has never run against
> a real subscription. The [verification table](#what-is-verified) says exactly what
> has and has not been exercised. Update it when a real deployment happens.

## Developer commands

Each CI job runs the same script you run locally. Run them from the repo root.

| Command | What it does | Needs |
|---|---|---|
| `scripts/backend-check.sh` | **The one command for backend tests.** `uv sync`, ruff lint, ruff format check, `mypy` strict on `app`, full `pytest` (including the real-Postgres DB tests) | uv; Postgres (see below) |
| `scripts/migration-check.sh` | On an empty DB: `alembic heads` (exactly one), `upgrade head`, `alembic check` (models match migrations), `downgrade base` (no tables left), `upgrade head` again | uv; Postgres |
| `contracts/validate.sh` | OpenAPI 3.1, JSON Schemas, conventions, valid and invalid examples | uv |
| `scripts/docker-smoke.sh [tag]` | Build `backend/Dockerfile`, check it runs non-root without uv/pytest, `GET /health`, demo routes absent (404), wait for the Docker `HEALTHCHECK` to report healthy | Docker daemon |
| `scripts/android-build.sh` | Debug APK + JVM unit tests (exits 2 with `UNVERIFIED` if no SDK) | JDK 17, Android SDK 35 |
| `scripts/backend-run.sh` | API with auto-reload on http://127.0.0.1:8000 | uv; Postgres for DB routes |
| `scripts/worker-run.sh` | Background worker (`python -m app.worker`) against `WINGMAN_DATABASE_URL`; `--once` runs one tick of each job | uv; Postgres (migrated) |
| `scripts/dev-run.sh` | **Dev only.** API and worker in one process (`app.worker.dev_server`), so the worker sees the API's in-memory mock connector (I-016) | uv; Postgres (migrated) |

**Which Postgres the tests use.** The DB tests (`backend/tests/db/`) and
`migration-check.sh` pick a server in this order:

1. `WINGMAN_TEST_DATABASE_URL` (tests) / `WINGMAN_DATABASE_URL` (migration check), if set.
   For tests the role needs `CREATEDB`: they create and drop `wingman_test_*` scratch
   databases and never modify the database named in the URL.
2. Otherwise a throwaway cluster started from local server binaries (`initdb`/`pg_ctl`
   from `WINGMAN_PG_BIN`, `PATH`, or `/usr/lib/postgresql/<ver>/bin`), deleted afterwards.
   Under root it runs as the `postgres` OS user (`WINGMAN_PG_OS_USER`).
3. Neither: the run **fails** (DB tests never silently skip).

With Docker, the simplest local setup is the root compose file:

```bash
cp .env.example .env
docker compose up -d postgres redis
WINGMAN_TEST_DATABASE_URL=postgresql+asyncpg://wingman:wingman@localhost:5432/wingman \
  scripts/backend-check.sh
```

`migration-check.sh` runs `downgrade base`, which drops every table. Point it only at an
empty database; it refuses to start if the target already has tables.

## Continuous integration

Workflow: [`.github/workflows/ci.yml`](../.github/workflows/ci.yml). It runs on every pull
request, on pushes to `main`, and on manual dispatch.

| Job | Runs | Notes |
|---|---|---|
| `backend` | `scripts/backend-check.sh` | Postgres 16 service container; `WINGMAN_TEST_DATABASE_URL` points at it (superuser, so it has `CREATEDB`) |
| `migrations` | `scripts/migration-check.sh` | Separate fresh Postgres 16; proves migration startup from an empty DB, reversibility, and no model drift |
| `contracts` | `contracts/validate.sh` | Resolves I-005 |
| `docker` | `scripts/docker-smoke.sh` | Builds the production image and checks `/health` and the `HEALTHCHECK` |
| `android` | `scripts/android-build.sh` | Temurin JDK 17, Android SDK via `android-actions/setup-android` (platform 35, build-tools 35.0.0), Gradle cache via `gradle/actions/setup-gradle` (which also validates the wrapper JAR). Test reports are uploaded as an artifact. This is the route to resolving I-001 / G3 |

Conventions:

- `permissions: contents: read` at workflow level. No job needs a token with write access,
  and `actions/checkout` runs with `persist-credentials: false`.
- Every action is pinned to a full commit SHA, with the release tag in a trailing comment.
  To bump one, resolve the new tag with `git ls-remote --tags https://github.com/<owner>/<repo>.git`.
- `concurrency` cancels superseded runs of the same PR or branch.
- Caches: uv (keyed on `backend/uv.lock`, or `contracts/validate.py` for the contracts
  job) and Gradle (written on `main`, read-only on PRs).
- No job needs secrets. The backend runs entirely on the mock LLM provider.

To make these required checks, enable branch protection on `main` in the GitHub UI and
select the five job names above.

## Backend image

[`backend/Dockerfile`](../backend/Dockerfile), build context `backend/`:

- Two stages. `builder` installs the locked runtime dependencies with uv (`--locked
  --no-dev --no-editable`) into `/app/.venv`. `runtime` is `python:3.12-slim-bookworm`
  with only that venv plus `alembic.ini` and `alembic/`. There is no uv, no dev tools,
  and no tests in the final image.
- Runs as uid/gid `10001` (`wingman`). Application files are root-owned and read-only.
- `WINGMAN_ENV=prod` is the image default, so the WING-006 demo router is **not** mounted
  unless an environment overrides it. Every other setting comes from the environment at
  run time.
- `HEALTHCHECK` calls `GET /health` with the Python stdlib (the slim image has no curl).
  `/health` is liveness only and does not touch Postgres.
- [`backend/.dockerignore`](../backend/.dockerignore) is an allow-list
  (`pyproject.toml`, `uv.lock`, `app/`, `alembic/`, `alembic.ini`), so `.env`, `.venv`,
  caches and `tests/` never reach the build context.
- The same image is the migration job: `docker run ... wingman-backend alembic upgrade head`.

```bash
docker build -t wingman-backend backend
docker run --rm -e WINGMAN_DATABASE_URL=postgresql+asyncpg://... wingman-backend alembic upgrade head
docker run --rm -p 8000:8000 -e WINGMAN_DATABASE_URL=postgresql+asyncpg://... wingman-backend
```

**Local Compose.** `docker compose --profile backend up --build` builds the image and
runs, next to Postgres 16 and Redis 7:

- `migrate`: one-shot `alembic upgrade head`, then exits;
- `backend`: the API on port 8000, started after `migrate` completed successfully;
- `worker`: `python -m app.worker` (see [Background worker](#background-worker)), also
  after `migrate`, with its liveness endpoint on port 8081 inside the container (used by
  the compose `healthcheck`, not published).

Its `env_file: [.env]` sets `WINGMAN_ENV=dev` from `.env.example`, which overrides the
image's `prod` default locally.

## Background worker

Owner: I-015. `python -m app.worker` (package `backend/app/worker/`) runs the periodic
WING-011 jobs in one asyncio process, each on its own interval:

| Job | Default interval | What it calls | Effect |
|---|---|---|---|
| `dispatch_due` | 2 s | `Dispatcher.dispatch` for each due message, **one transaction per message** | Queued outbound messages whose `send_after` has passed (reply delays, retry backoff) are sent (backend connector) or get their device outbox instruction |
| `expire_leases` | 15 s | `app.services.outbox.expire_lapsed_leases` | Lapsed device leases become `expired`. The outcome is unknown: the message stays `sending` and is never retried (ADR 0006) |
| `expire_approvals` | 60 s | `AutomationController.expire_approvals`, one transaction per user | Pending approvals past `expires_at` become `expired` (audit `approval_expired`) |

`python -m app.worker --once` runs one tick of every job and exits.

**Robustness.**
- Each job runs in its own task, so a slow or failing job never delays the others. A
  tick that raises is logged with its **error class only** and retried after a bounded
  exponential backoff, `min(WINGMAN_WORKER_BACKOFF_MAX_S, interval * 2^failures)`; the
  first successful tick resets it. A dispatch tick in which every message failed (for
  example, the database is down) also backs off. One failing message never blocks the
  rest of the batch: it stays `queued` and is retried next tick with the **same**
  attempt key, so a transport that already sent replays its stored result (D-006).
- **SIGTERM/SIGINT** finishes the current tick of each job and exits with code 0. A
  tick is never cancelled, so no transaction is cut mid-send. The dispatch job checks
  for a stop between messages, so shutdown waits for at most one transport call per
  replica. Give the platform a grace period longer than one tick (Compose 30 s,
  Container Apps 60 s).
- **Logs** carry IDs, job names, counts and error classes, never message text or
  exception messages (a database error's text can contain bound parameters). Each tick
  writes one `DEBUG` heartbeat line, `worker tick job=<name> ok=<bool> duration_ms=<n>
  <counts>`. SQLAlchemy's engine logger is held at `WARNING`.
- **Liveness**: set `WINGMAN_WORKER_HEALTH_PORT` to serve `GET /health` (stdlib only,
  no app data). It answers `200` while every job has made progress (a finished tick, or
  a tick skipped because another replica holds the lock) within
  `WINGMAN_WORKER_STALE_AFTER_S`, otherwise `503`, and `503` while stopping.

**Several replicas: what keeps it safe.** Two layers, and the second is sufficient on
its own (the tests run two workers concurrently both with and without the first):

1. **Per-job Postgres advisory lock.** Each tick takes `pg_try_advisory_lock(<job key>)`
   on a dedicated connection held for the tick and released with
   `pg_advisory_unlock` (or by Postgres when the connection or process dies). A replica
   that does not get the lock skips that tick. This makes one replica do each job's
   work at a time; it is an efficiency measure. Session-level advisory locks need a
   real session: behind a transaction-pooling proxy (PgBouncer `pool_mode=transaction`)
   set `WINGMAN_WORKER_ADVISORY_LOCKS=false`.
2. **The services' own row locks** (the correctness guarantee). `Dispatcher.dispatch`
   locks the message row `FOR UPDATE`, acts only on `queued`, and makes a backend
   transport call while holding that lock, so a concurrent dispatcher (another
   replica, or the API) waits and then sees the new state and does nothing. Each
   message is committed as `queued` before any transport call (PR #24), each attempt
   has its own key (`send:<message_id>:<attempt>`, D-006), and a device instruction is
   created once per attempt (live-instruction check plus a unique
   `(outbound_message_id, attempt)`). Lease and approval expiry are single guarded
   `UPDATE ... WHERE state = 'leased'|'pending'` statements: a concurrent run blocks on
   the row lock, re-checks the predicate and matches nothing. Device claims already use
   `FOR UPDATE SKIP LOCKED` (ADR 0006); the worker does not use `SKIP LOCKED` itself.

**The mock connector and the worker (I-016).** `MockConnector` state (threads and the
send ledger) lives in the memory of the process that created it. A separate worker
process has its own, empty simulator and would dead-letter every mock send as
"unknown mock thread". So:

- **`python -m app.worker` skips `mock` connections by default**
  (`WINGMAN_WORKER_DISPATCH_MOCK=false`). In Compose the API process still sends mock
  messages that are due immediately; a delayed or backed-off **mock** send stays
  `queued` there. Device connections (SMS, notifications) and lease/approval expiry are
  unaffected, because they keep no in-process state.
- **To exercise reply delays and retries end to end against the mock, run one process**:
  `scripts/dev-run.sh` (`uvicorn app.worker.dev_server:app`). It serves the normal API
  (`app.main.create_app()`) and runs the worker loop in the same event loop with the
  same dispatcher, so the worker sees the simulator the API ingested into. It refuses
  to start with `WINGMAN_ENV=prod`. Do not run a standalone worker with
  `WINGMAN_WORKER_DISPATCH_MOCK=true` next to a separate API.
- Real platforms are unaffected: their state lives in Postgres and on the device.

## Environment variable reference

All runtime settings use the `WINGMAN_` prefix and are read by pydantic-settings
(`app/config.py`, and `app/ai/providers/settings.py` for `WINGMAN_LLM_*`). A `.env` file in
the working directory is also read; that is for local development only. The image never
contains one, and deployed environments set real environment variables.

**Secret** means the value must come from a secret store in any shared or deployed
environment, never from the repo, the image, or a plain env var in an IaC template.

### Application

| Variable | Default | Secret | Description |
|---|---|---|---|
| `WINGMAN_ENV` | `dev` (`prod` in the image) | no | `dev`, `test` or `prod`. `prod` disables the unauthenticated demo connector router |
| `WINGMAN_DATABASE_URL` | `postgresql+asyncpg://wingman:wingman@localhost:5432/wingman` | **yes** (contains the password) | SQLAlchemy async URL; the driver must be `postgresql+asyncpg`. For Azure add `?ssl=require` |
| `WINGMAN_REDIS_URL` | `redis://localhost:6379/0` | yes, if it has a password | Defined in settings but **not used by any code yet** |
| `WINGMAN_LOG_LEVEL` | `INFO` | no | Log level name. Currently stored in settings only; logging setup arrives with WING-022 |
| `WINGMAN_LLM_PROVIDER` | `mock` | no | `mock` (deterministic, no key, no network), `openai` (any OpenAI-compatible endpoint), `anthropic` (not implemented: raises `LLMConfigError`) |

### LLM provider (`WINGMAN_LLM_*`, read only when the provider is not `mock`)

| Variable | Default | Secret | Description |
|---|---|---|---|
| `WINGMAN_LLM_API_KEY` | unset | **yes** | Required for `openai`. Held as a `SecretStr`, never logged |
| `WINGMAN_LLM_BASE_URL` | `https://api.openai.com/v1` | no | OpenAI-compatible chat completions base URL |
| `WINGMAN_LLM_MODEL` | `gpt-4o-mini` | no | Model name sent to the endpoint |
| `WINGMAN_LLM_TIMEOUT_S` | `30` | no | Per-request timeout, `0 < x <= 600` |
| `WINGMAN_LLM_MAX_ATTEMPTS` | `3` | no | Attempts per provider on transient errors, 1 to 10 |
| `WINGMAN_LLM_MAX_OUTPUT_RETRIES` | `1` | no | Extra attempts after malformed or schema-invalid output, 0 to 3 |
| `WINGMAN_LLM_BACKOFF_BASE_S` | `0.5` | no | Retry backoff base, seconds |
| `WINGMAN_LLM_BACKOFF_MAX_S` | `8` | no | Retry backoff cap, seconds |
| `WINGMAN_LLM_INPUT_USD_PER_1M` | unset | no | Input price per 1M tokens; unset records cost as unknown |
| `WINGMAN_LLM_OUTPUT_USD_PER_1M` | unset | no | Output price per 1M tokens |
| `WINGMAN_LLM_STRICT_JSON_SCHEMA` | `false` | no | Ask the endpoint to enforce the schema (OpenAI `strict`) |

`.env.example` lists every `WINGMAN_LLM_*` key (commented); `MAX_OUTPUT_RETRIES`,
`BACKOFF_BASE_S`, `BACKOFF_MAX_S` and `STRICT_JSON_SCHEMA` are optional tuning knobs.

### Background worker (`WINGMAN_WORKER_*`, read by `python -m app.worker`)

The worker also reads `WINGMAN_DATABASE_URL`, `WINGMAN_ENV` and `WINGMAN_LOG_LEVEL`
(`DEBUG` shows the per-tick heartbeat). None of these are secrets.

| Variable | Default | Description |
|---|---|---|
| `WINGMAN_WORKER_DISPATCH_INTERVAL_S` | `2` | Seconds between `dispatch_due` ticks (reply delays and retry backoff are honoured to about this granularity) |
| `WINGMAN_WORKER_LEASE_EXPIRY_INTERVAL_S` | `15` | Seconds between lease-expiry ticks |
| `WINGMAN_WORKER_APPROVAL_EXPIRY_INTERVAL_S` | `60` | Seconds between approval-expiry ticks |
| `WINGMAN_WORKER_DISPATCH_BATCH` | `100` | Most due messages per dispatch tick, oldest first (1 to 1000) |
| `WINGMAN_WORKER_BACKOFF_MAX_S` | `60` | Cap of the exponential backoff after failed ticks |
| `WINGMAN_WORKER_ADVISORY_LOCKS` | `true` | Per-job Postgres advisory locks (one replica works at a time). `false` behind a transaction-pooling proxy; correctness does not depend on it |
| `WINGMAN_WORKER_DISPATCH_MOCK` | `false` | Also dispatch messages on `mock` connections. Only for a process that owns the mock state (I-016); the dev server sets it itself |
| `WINGMAN_WORKER_HEALTH_PORT` | unset (disabled) | Port for the worker's `GET /health` liveness endpoint (Compose and the Azure sample use `8081`) |
| `WINGMAN_WORKER_HEALTH_HOST` | `0.0.0.0` | Bind address of that endpoint |
| `WINGMAN_WORKER_STALE_AFTER_S` | `300` | `/health` answers 503 if any job made no progress for this long |

### Tests and tooling only (never set in a deployed environment)

| Variable | Default | Description |
|---|---|---|
| `WINGMAN_TEST_DATABASE_URL` | unset | asyncpg URL of a server for `tests/db`; role needs `CREATEDB`. Unset: throwaway cluster from local binaries |
| `WINGMAN_PG_BIN` | auto-detected | Directory containing `initdb` and `pg_ctl` for the throwaway cluster |
| `WINGMAN_PG_OS_USER` | `postgres` | OS user that runs the throwaway cluster when tests run as root |
| `DOCKERFILE`, `DOCKER_PULL` | `Dockerfile`, `0` | `scripts/docker-smoke.sh` only |

## Secret loading strategy

1. **Source of truth is a secret store.** In Azure that is Key Vault. Secrets are
   `wingman-database-url` (full `WINGMAN_DATABASE_URL`, including the password) and,
   when a real LLM provider is enabled, `wingman-llm-api-key`.
2. **Delivered as environment variables at run time.** Container Apps secrets are Key
   Vault references, resolved with a user-assigned managed identity that has
   `Key Vault Secrets User` on the vault. Env vars use `secretRef`. No secret value ever
   appears in the IaC, the YAML, the shell history, or the image.
3. **Never in the repo or the image.** `.env` is git-ignored, `.env.example` holds only
   local throwaway defaults, the `.dockerignore` allow-list keeps `.env` out of the build
   context, and the Dockerfile sets no secret `ENV`/`ARG`. The `docker` CI job asserts
   that `/app/.env` is absent.
4. **Rotation.** Key Vault references use the versionless secret URI, so a new secret
   version is picked up when a new revision starts (`az containerapp revision restart`, or
   the next deploy). For a database password: set the new password on the server, write
   the new URL to Key Vault, restart the revision.
5. **CI needs no secrets.** All tests run against the mock provider and a throwaway
   Postgres with a CI-only password.
6. **Local development** uses `.env` (copied from `.env.example`). Never put a real API
   key in a file inside the repo working tree. Export it in your shell for a one-off run.

## Reference deployment: Azure Container Apps

> **SAMPLE CONFIGURATION, UNVERIFIED.** The steps and `infra/azure/deploy.sh` have not been
> run against Azure. The script was only dry-run against a stub `az` (to check the YAML it
> generates) and linted with shellcheck. Expect to adjust names, SKUs and CLI flags. Do not
> treat any of this as a verified production deployment.

Shape: two container apps (the API, and the background worker with no ingress) and one
manual Container Apps job (migrations), all from the same image, plus Azure Database for PostgreSQL Flexible Server 16, Azure Container Registry
and Key Vault. There is no Kubernetes, no Redis (unused so far), and no extra services.

```
 GitHub / laptop --az acr build--> ACR (wingman-backend:<git sha>)
                                     │ AcrPull (managed identity)
                    ┌────────────────┴───────────────┐
         Container Apps job                Container App "wingman-api"
         alembic upgrade head   ──then──►  uvicorn, ingress :8000 HTTPS
                    │              ──then──►  Container App "wingman-worker"
                    │                         python -m app.worker, no ingress, 1 replica
                    │  Key Vault refs (managed identity)  │
                    └───────────► Key Vault ◄────────────┘
                                     │ wingman-database-url
                    PostgreSQL Flexible Server 16 (sslmode=require)
```

### Prerequisites

- Azure subscription, `az` CLI 2.60+ with the `containerapp` extension
  (`az extension add --name containerapp --upgrade`), and `az login`.
- Your account needs Owner (or Contributor + User Access Administrator) on the resource
  group, because the script creates role assignments.
- Register providers once: `az provider register -n Microsoft.App`,
  `-n Microsoft.OperationalInsights`, `-n Microsoft.DBforPostgreSQL`,
  `-n Microsoft.ContainerRegistry`, `-n Microsoft.KeyVault`.

### Steps

Copy [`infra/azure/sample.env`](../infra/azure/sample.env) **outside the repo**, change
the globally unique names, then load it:

```bash
cp infra/azure/sample.env ~/wingman-staging.env && $EDITOR ~/wingman-staging.env
set -a; source ~/wingman-staging.env; set +a
```

Run the steps one at a time and check each result. `infra/azure/deploy.sh all` runs them in
order.

1. **Infrastructure** (`deploy.sh infra`): resource group; ACR (Basic, admin user off);
   user-assigned identity with `AcrPull` on the registry and `Key Vault Secrets User` on the
   vault; Key Vault in RBAC mode (you get `Key Vault Secrets Officer`); PostgreSQL Flexible
   Server 16 (Burstable B1ms, 32 GiB) with database `wingman`. The script generates the admin
   password and writes the full `WINGMAN_DATABASE_URL` (`...?ssl=require`) straight to Key
   Vault as `wingman-database-url`; it never prints it. Finally the Container Apps
   environment.
2. **Registry push** (`deploy.sh image`): `az acr build` builds `backend/Dockerfile` in
   Azure (no local Docker needed) and pushes `wingman-backend:<git sha>`. Tags are immutable
   commit SHAs; never deploy `latest`. Alternatively `docker build` locally, `az acr login`,
   and `docker push`.
3. **Optional LLM key**: to use a real provider, first
   `az keyvault secret set --vault-name $AZ_KEYVAULT_NAME --name wingman-llm-api-key --value "$(read -rs k; echo "$k")"`
   and set `WINGMAN_LLM_PROVIDER=openai`. The script wires `WINGMAN_LLM_API_KEY` only if the
   secret exists. The default stays `mock`.
4. **Migrations before rollout** (`deploy.sh migrate`): creates or updates the manual job
   `wingman-migrate` with the new image and command `alembic upgrade head`, starts it, and
   waits. If it does not reach `Succeeded`, the script stops and the API is **not** updated.
   Migrations must stay backward compatible with the previous app revision (expand, then
   contract), because the old revision keeps serving until the new one is ready.
5. **App rollout** (`deploy.sh app`): creates or updates `wingman-api` with external HTTPS
   ingress to port 8000, single-revision mode, 1 to 2 replicas, and Key Vault secret
   references. **Health probes**: Container Apps ignores the image's Docker `HEALTHCHECK`,
   so the app defines Startup (`/health`, every 3 s, up to 60 s), Liveness (every 30 s) and
   Readiness (every 10 s) probes.
6. **Worker rollout** (`deploy.sh worker`, I-015, **UNVERIFIED**): creates or updates
   `wingman-worker` (`AZ_WORKER_NAME`) from the same image with command
   `python -m app.worker`, the same Key Vault secret references, **no ingress**, single
   revision, `minReplicas: 1` and `maxReplicas: 1` (it must always run; more replicas
   are safe but only add a standby), and `terminationGracePeriodSeconds: 60` so SIGTERM
   can finish the current tick. **Probes**: a non-HTTP process has nothing for the
   API's `/health` probes to hit, so the worker serves its own liveness endpoint on
   `WINGMAN_WORKER_HEALTH_PORT=8081` (container-internal; no ingress needed for probes):
   Startup is a TCP probe on 8081, Liveness is `GET /health` on 8081 every 30 s, which
   fails (503) if a job has made no progress for `WINGMAN_WORKER_STALE_AFTER_S`. No
   Readiness probe: the worker receives no traffic. Roll it out after migrations, in the
   same deploy as the API, so both run the same image.
7. **Smoke test**: `curl -fsS https://<fqdn>/health` should return `{"status":"ok",...,"env":"prod"}`.
   The demo routes under `/dev/connectors/mock/...` must return 404 in `prod`. For the
   worker, `az containerapp logs show -n wingman-worker` should show `worker started`
   (set `WINGMAN_LOG_LEVEL=DEBUG` briefly to see the per-tick heartbeat).

### Rollback

- Image or app problem: redeploy the previous SHA (`IMAGE_TAG=<old sha> deploy.sh app`,
  and `IMAGE_TAG=<old sha> deploy.sh worker`),
  or in multiple-revision mode shift traffic back to the previous revision.
- Migration problem: migrations are written to be reversible (the `migrations` CI job runs
  `downgrade base`). To roll back one revision, run the job with
  `alembic downgrade -1`. Take a Flexible Server backup or point-in-time restore first
  for anything destructive.

### Hardening before real users (not done)

This sample favours a short path to a working staging environment. Before production:

- Postgres private access (VNet-integrated Container Apps environment with a delegated
  subnet and private DNS) instead of `--public-access 0.0.0.0` (which allows all Azure
  services, including other tenants').
- A dedicated least-privilege DB role for the app (no DDL) and a separate migration role.
  Today both use the server admin.
- Entra ID auth to Postgres (no password) or at least regular rotation.
- Production SKUs (General Purpose Postgres with HA and geo-redundant backup), zone-redundant
  Container Apps environment, `minReplicas` sized to traffic.
- A readiness endpoint that checks the database (today `/health` is liveness only).
- Log Analytics retention and alerts, which WING-022 will define.
- CI-driven deploys with OIDC federated credentials (`azure/login` with no stored secret)
  and a protected GitHub environment. Not added because nothing is deployed yet.

## What is verified

As of WING-023 (2026-11-01):

| Item | Status | Evidence |
|---|---|---|
| `scripts/backend-check.sh` | **VERIFIED** locally | 304 tests passed, both with the throwaway cluster and with a Postgres 16 container via `WINGMAN_TEST_DATABASE_URL` |
| `scripts/migration-check.sh` | **VERIFIED** locally | Passed with the throwaway cluster and with a Postgres 16 container; refuses a non-empty DB |
| `contracts/validate.sh` | **VERIFIED** locally | `CONTRACT VALIDATION PASSED` |
| Backend image build, non-root, `/health`, `HEALTHCHECK` | **VERIFIED** locally | `scripts/docker-smoke.sh` passed. In the sandbox the build needed the proxy's CA injected (sandbox-only variant, not committed) |
| Image runs `alembic upgrade head` against Postgres 16 | **VERIFIED** locally | Ran in a container against `postgres:16-alpine` |
| Root `docker-compose.yml` with the new image | **VERIFIED** locally | `--profile backend` up: all three services healthy, `/health` answered |
| `.github/workflows/ci.yml` syntax | **VERIFIED** locally | `actionlint` (with shellcheck and pyflakes rules): 0 findings |
| CI jobs on GitHub runners, including Android | See the WING-023 PR | Not observable from the authoring sandbox until the PR runs |
| `infra/azure/deploy.sh` | **UNVERIFIED** | shellcheck clean; dry run against a stub `az` only |
| Background worker (`python -m app.worker`), I-015 | **VERIFIED** locally | `tests/worker/` against real Postgres: reply delay, retry backoff, lease expiry, approval expiry, two concurrent workers (one send per key), job isolation, SIGTERM exit 0 of the real process, log sentinel |
| `deploy.sh worker` (Azure worker app) | **UNVERIFIED** | shellcheck clean; generated YAML checked in a dry run against a stub `az` only |
| Any Azure deployment | **UNVERIFIED**: none exists | |
