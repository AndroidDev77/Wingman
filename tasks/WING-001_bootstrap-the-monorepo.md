# WING-001: Bootstrap the monorepo

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 1  
**Dependencies:** none  
**Ownership / expected edit paths:**
- `backend/`
- `android/`
- `contracts/`
- `docs/`
- `infra/`
- `scripts/`
- `AGENTS.md`
- `README.md`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Create a reproducible monorepo with a bootable FastAPI application, minimal Android shell, and deterministic local developer commands.

### Required implementation
- Python 3.12+ backend with uv or pyproject.toml, lint/type/test tooling
- Android Kotlin/Compose Gradle project with debug build
- Basic FastAPI health endpoint GET /health
- docker-compose.yml for Postgres and Redis (backend optional)
- Root README with setup commands, example env file, documented SDK requirements

### Acceptance tests / definition of done
- GET /health returns HTTP 200 under local execution
- Backend unit-test command completes
- Android debug build completes if SDK is available, otherwise documented as unverified
- No secrets committed

### Constraints and integration notes
Do not implement product logic. The repo owner integrates scaffolding changes before other tasks begin.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
