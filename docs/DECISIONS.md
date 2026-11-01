# Integration Decisions and Open Issues

Running log kept by the integrator. Architecture-level decisions graduate to `docs/adr/` (owned by WING-002).

## Decisions

### D-001 (2026-10-31): Seed `main` with the ticket package before WING-001
The GitHub repo was empty. `main` is seeded with `AGENT_RULES.md`, `EXECUTION_PLAN.md`, `TASK_INDEX.md`, `tasks/`, and this log so every agent branch has a common base and can read its ticket from the repo. WING-001 then lands as a normal PR.

### D-002 (2026-10-31): Remote cloud agents open PRs; the integrator reviews and merges
Each ticket goes to a separate remote agent working on its own `wing-###-short-name` branch, which opens a PR against `main`. The integrator re-runs the checks, reviews against the ticket's acceptance criteria and file ownership, then merges. Agents never merge their own PRs or push to `main`.

### D-003 (2026-10-31): Integrator owns shared wiring
Per `EXECUTION_PLAN.md`, only the integrator edits `backend/app/main.py` router registration, Alembic migration ordering, the Android manifest, the DI root, and the navigation graph. Feature PRs that need these changes describe them in the handoff instead.

### D-004 (2026-11-01): Contract v1.0.0 frozen (G1)
Changes to `contracts/` now need their own versioned PR. Deferred to additive PRs by the owning tickets: connection pairing, connector-event ingest route, memory, dates, analytics and billing endpoints.

### D-005 (2026-11-01): Dependency and lockfile conflicts are resolved by the integrator
Wave 2 runs three backend tickets at once. Each may add dependencies to `backend/pyproject.toml`; the integrator merges them and regenerates `uv.lock` at merge time. Each ticket also provides its own FastAPI `APIRouter`, which the integrator registers in `app/main.py`.

### D-006 (2026-11-01): One transport attempt per connector idempotency key
WING-006 reads ADR 0004 literally: each `Idempotency-Key` gets exactly one transport attempt, success or failure, and a repeat returns the stored result with `replayed=true`. **The WING-011 dispatcher must derive a fresh key per retry attempt** (e.g. `send:<message_id>:<attempt>`) while keeping the API-level key stable. Verified live on 2026-11-01: same key twice gave one send; same key with a different body was rejected.

### D-007 (2026-11-01): Fail-closed auth stub as the shared auth interface
`app/security/deps.py::get_current_user_scope() -> UserScope` exists before WING-004 so WING-007/008 can build protected routers in parallel. Until WING-004 replaces it, it rejects every call with 401; there is no bypass. Tests inject a scope through `app.dependency_overrides`. WING-004 must keep the import path and signature.

### D-008 (2026-11-01): Integrator wiring for wave 2
`app/main.py` mounts the WING-006 demo router only when `WINGMAN_ENV != "prod"`, and disposes the DB engine on shutdown. Covered by `tests/test_app_wiring.py` over HTTP.

### D-009 (2026-11-01): New migrations are proposed in feature PRs, reviewed by the integrator
WING-003 is merged, so the integrator owns migration ordering. A feature ticket that needs a schema change adds a NEW numbered migration in its PR and flags it in the handoff; never edit `0001`. At most one wave-3 ticket (WING-008) may add `0002` to avoid divergent heads.

## Open issues

| ID | Issue | Owner | Status |
|---|---|---|---|
| I-001 | No Android SDK in the cloud sandbox, so Android builds are UNVERIFIED until a runner has the SDK (WING-023) or a developer builds locally | Integrator / WING-023 | Open |
| I-002 | No Docker daemon in the cloud sandbox; `docker-compose.yml` is UNVERIFIED here. Mitigated: Postgres 16 server binaries are at `/usr/lib/postgresql/16/bin` for tests | Integrator | Mitigated |
| I-004 | One problem+json exception handler should replace FastAPI's default 422 body, and datetimes must serialize with `Z` | WING-004 | Open |
| I-005 | Run `contracts/validate.sh` in CI | WING-023 | Open |
| I-006 | Merged branches can't be deleted through the sandbox proxy; delete `wing-001-bootstrap`, `wing-002-contracts`, `wing-003-persistence`, `wing-005-llm-gateway`, `wing-006-connector-sdk`, `wing-012-android-shell` in the GitHub UI | Repo owner | Open |
| I-003 | G5 (physical-device SMS verification) needs a human with an Android device; cloud agents cannot satisfy it | Repo owner | Open |
| I-007 | WING-011 needs `approvals` and `drafts` tables plus FKs on the existing `approval_id` columns (new migration, never edit `0001`) | WING-011 | Open |
| I-008 | Installing the Android SDK in the sandbox was denied by permissions during WING-012. With the repo owner's approval it could be installed to verify G3 here; otherwise a developer runs `scripts/android-build.sh` | Repo owner | Open |
| I-009 | `OpenAICompatibleProvider` has never called a real endpoint; the Anthropic provider is not implemented (raises `LLMConfigError`) | Post-MVP | Open |
| I-010 | No contract route yet for device-side connectors (WING-016 SMS) to push `connector_event` envelopes to the backend. Needs an additive contract PR (e.g. `POST /v1/connector-events`) before the real SMS vertical slice can run end to end | Integrator / WING-011 | Open |
| I-011 | Android `AppContainer` needs a real `tokenProvider` once WING-004 lands; until then real-backend calls carry no `Authorization` header | WING-004 follow-up | Open |
| I-012 | `mypy tests/db` reports 27 errors (not part of the check script; `mypy app` is strict-clean) | Low | Open |
