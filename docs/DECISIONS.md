# Integration Decisions and Open Issues

Running log kept by the integrator. Architecture-level decisions graduate to `docs/adr/` (owned by WING-002).

## Decisions

### D-001 (2026-10-31): Seed `main` with the ticket package before WING-001
The GitHub repo was empty. `main` is seeded with `AGENT_RULES.md`, `EXECUTION_PLAN.md`, `TASK_INDEX.md`, `tasks/`, and this log so every agent branch has a common base and can read its ticket from the repo. WING-001 then lands as a normal PR.

### D-002 (2026-10-31): Remote cloud agents open PRs; the integrator reviews and merges
Each ticket goes to a separate remote agent working on its own `wing-###-short-name` branch, which opens a PR against `main`. The integrator re-runs the checks, reviews against the ticket's acceptance criteria and file ownership, then merges. Agents never merge their own PRs or push to `main`.

### D-003 (2026-10-31): Integrator owns shared wiring
Per `EXECUTION_PLAN.md`, only the integrator edits `backend/app/main.py` router registration, Alembic migration ordering, the Android manifest, the DI root, and the navigation graph. Feature PRs that need these changes describe them in the handoff instead.

## Open issues

| ID | Issue | Owner | Status |
|---|---|---|---|
| I-001 | No Android SDK in the cloud sandbox, so Android builds are UNVERIFIED until a runner has the SDK (WING-023) or a developer builds locally | Integrator / WING-023 | Open |
| I-002 | No Docker daemon in the cloud sandbox; `docker-compose.yml` is UNVERIFIED here. DB-dependent tickets must run Postgres another way | Integrator | Open |
| I-003 | G5 (physical-device SMS verification) needs a human with an Android device; cloud agents cannot satisfy it | Repo owner | Open |
