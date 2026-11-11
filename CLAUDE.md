# Wingman AI: notes for Claude Code

Android-first AI dating assistant. It drafts replies; it sends only through verified, user-enabled connectors under an approval policy the user controls. Private beta, LIMITED (see `docs/beta/`).

## Read first
- `AGENT_RULES.md`: the binding working agreement (VERIFIED / UNVERIFIED / UNSUPPORTED labels, every send through the dispatcher, LLM output is never authorization, no fabricated facts, tenant isolation, redacted logs).
- `AGENTS.md`: workflow mechanics (one ticket, one `wing-###-short-name` branch, one PR; integrator-owned files).
- `docs/STATUS.md`: live ticket and gate status. `docs/DECISIONS.md`: decisions (D-###) and open issues (I-###). Check both before changing behavior.
- `docs/architecture.md` and `docs/adr/` for design; `contracts/` (OpenAPI + JSON Schemas) is the source of truth for every API and event shape.

## Layout
`backend/` FastAPI + SQLAlchemy 2 + Alembic (Python 3.12, uv) · `android/` Kotlin + Compose · `macos/` iMessage companion (Swift) · `contracts/` · `tests/e2e/` · `scripts/` · `config/release/feature_gates.json` · `tasks/` (tickets).

## Commands
| Task | Command |
|---|---|
| Backend lint, types, tests (real Postgres, throwaway cluster) | `scripts/backend-check.sh` |
| Contracts | `contracts/validate.sh` |
| Migrations up/down/up | `scripts/migration-check.sh` |
| E2E (core) / E2E (platforms) | `scripts/e2e/run.sh` / `scripts/e2e/platforms/run.sh` |
| Full release gate | `scripts/release/check.sh` |
| Android debug APK + JVM tests | `scripts/android-build.sh` (needs the Android SDK) |
| Run backend locally on macOS | `bash scripts/local/start-mac.sh` (Docker or Homebrew Postgres; prints a dev token; opens http://127.0.0.1:8000/docs) |
| Dev-only backend (API + worker, mock LLM, mock connector) | `scripts/dev-run.sh` (needs a migrated DB) |
| Mint a dev token | `cd backend && uv run python -m app.security.dev_stub --sub <name>` |

Android app: `cd android && ./gradlew :app:installDebug` runs on built-in mock data; add `-Pwingman.useMockApi=false` to use the local backend at `10.0.2.2:8000` (emulator) and sign in with "Developer sign-in (debug build only)". The experimental Hinge connector and the read-only screen reader (D-032, `docs/integrations/screen_reader.md`) compile only with `-Pwingman.experimental=true` (never a Play build, D-023/D-026).

## Rules that bite
- One Alembic migration (`0001`) until the first production deploy: amend it, don't add `0002` (D-010).
- Contract changes are additive and versioned (current v1.3.2); update examples and negative examples, run `contracts/validate.sh`.
- Use `FOR NO KEY UPDATE` for exclusive row locks; `FOR UPDATE` only where key changes or deletes must be blocked (D-020).
- Never put platform credentials or session tokens on the backend, in logs, fixtures or the repo (D-021).
- Never claim a capability is VERIFIED without a reproducible real-device test; release gates refuse autonomous features without evidence.
- CI (9 required jobs) must be green before merging; don't merge your own PR when working as a ticket agent.
