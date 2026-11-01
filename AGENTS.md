# Instructions for coding agents

`AGENT_RULES.md` is the binding working agreement. This file covers the mechanics.

## Before you write code
1. Read `AGENT_RULES.md`, `TASK_INDEX.md`, `contracts/`, and your one ticket in `tasks/`.
2. Check `docs/STATUS.md` that every ticket you depend on is ✅. If not, stop and report the blocker; build only non-conflicting preparatory code.
3. Branch from the latest `main`: `git checkout -b wing-###-short-name origin/main`.

## While you work
- Edit only the paths listed under **Ownership** in your ticket. If you need a change elsewhere, describe it in your handoff instead of making it.
- **Integrator-owned files (never edit):** router registration in `backend/app/main.py`, Alembic migration ordering, `android/app/src/main/AndroidManifest.xml`, the Android DI root and navigation graph, `docs/STATUS.md`, `docs/DECISIONS.md`, `TASK_INDEX.md`, `tasks/`.
- Contracts in `contracts/` are frozen after WING-002. A change needs its own versioned contract PR.
- Tests must run without network access to model vendors or real messaging platforms. Use deterministic mocks.
- Never commit secrets. `.env` is git-ignored; add new settings to `.env.example` with safe placeholder values.

## Before you open the PR
- Backend: `scripts/backend-check.sh` must pass.
- Android: run `scripts/android-build.sh` if you have an SDK; otherwise say **UNVERIFIED** in the handoff.
- PR title: `WING-###: <ticket title>`. PR body: the six-part handoff report from `AGENT_RULES.md`, with test output you actually observed.
- Do not merge your own PR. The integrator reviews and merges.
