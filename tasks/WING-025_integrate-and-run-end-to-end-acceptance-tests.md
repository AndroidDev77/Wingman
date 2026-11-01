# WING-025: Integrate and run end-to-end acceptance tests

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 6  
**Dependencies:** WING-011, WING-013, WING-014, WING-015, WING-016, WING-023  
**Ownership / expected edit paths:**
- `tests/e2e/`
- `docs/e2e_results.md`
- `scripts/e2e/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Merge verified modules and demonstrate the complete manual, approval and autopilot flows using mocks plus one real connector.

### Required implementation
- E2E scenarios for onboarding, message injection, personalized reply, approval, send, memory, and takeover
- Connector matrix showing real hardware verification and unverified channels
- Failure injection for duplicate messages, offline device, retry and cancellation
- Document exact run commands, observed results and unresolved blockers
- Coordinate integration/merge fixes with module owners

### Acceptance tests / definition of done
- Mock end-to-end flow passes with zero duplicate sends
- Android app drives backend and displays action logs
- One real channel sends a response on device OR MVP labeled blocked with proof of why
- Every acceptance result has reproducible steps and does not claim unexecuted tests

### Constraints and integration notes
Integrator is responsible for modifying shared wiring. No parallel feature agent should change integrated interfaces mid-test.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
