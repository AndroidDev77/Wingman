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

**Local Compose.** The root `docker-compose.yml` works unchanged with this image:
`docker compose --profile backend up --build` builds it and runs it next to Postgres 16
and Redis 7. Its `env_file: [.env]` sets `WINGMAN_ENV=dev` from `.env.example`, which
overrides the image's `prod` default locally. Compose does not run migrations; run them
once with `docker compose run --rm backend alembic upgrade head`.

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

`.env.example` lists the common subset; the four `WINGMAN_LLM_*` rows not in it
(`MAX_OUTPUT_RETRIES`, `BACKOFF_BASE_S`, `BACKOFF_MAX_S`, `STRICT_JSON_SCHEMA`) are
optional tuning knobs.

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

Shape: one container app (the API) and one manual Container Apps job (migrations), both from
the same image, plus Azure Database for PostgreSQL Flexible Server 16, Azure Container Registry
and Key Vault. There is no Kubernetes, no Redis (unused so far), and no extra services.

```
 GitHub / laptop --az acr build--> ACR (wingman-backend:<git sha>)
                                     │ AcrPull (managed identity)
                    ┌────────────────┴───────────────┐
         Container Apps job                Container App "wingman-api"
         alembic upgrade head   ──then──►  uvicorn, ingress :8000 HTTPS
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
6. **Smoke test**: `curl -fsS https://<fqdn>/health` should return `{"status":"ok",...,"env":"prod"}`.
   The demo routes under `/dev/connectors/mock/...` must return 404 in `prod`.

### Rollback

- Image or app problem: redeploy the previous SHA (`IMAGE_TAG=<old sha> deploy.sh app`),
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
| Any Azure deployment | **UNVERIFIED**: none exists | |
