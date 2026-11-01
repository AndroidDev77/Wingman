# WING-023: Prepare CI, developer environments and deploy path

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 5  
**Dependencies:** WING-001, WING-003  
**Ownership / expected edit paths:**
- `.github/workflows/`
- `infra/`
- `scripts/`
- `docs/deployment.md`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Make the project reproducible in CI and deployable as a small backend service.

### Required implementation
- Backend lint, typecheck, unit-test, database migration CI jobs
- Android build check when runner/SDK is available
- Docker multi-stage backend image and local Compose setup
- Environment variable reference and secret loading strategy
- Azure Container Apps reference deployment or equivalent, with clear manual steps

### Acceptance tests / definition of done
- One documented command runs backend tests
- CI validates API schema and migration startup
- No secrets in repo or images
- Deploy documentation distinguishes sample config from verified production deployment

### Constraints and integration notes
Avoid premature Kubernetes or multi-service infrastructure.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
