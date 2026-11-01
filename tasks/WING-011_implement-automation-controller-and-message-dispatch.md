# WING-011: Implement automation controller and message dispatch

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 2  
**Dependencies:** WING-004, WING-006, WING-010  
**Ownership / expected edit paths:**
- `backend/app/services/automation.py`
- `backend/app/services/dispatcher.py`
- `backend/app/api/agent_actions.py`
- `backend/tests/automation/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Build the end-to-end process: inbound event -> context -> draft -> approval or send -> audit log.

### Required implementation
- Manual, assisted, autopilot policies at global and per-contact level
- Policy engine with send capability checks, pause/takeover, sensitive approval, active hours, user limits
- Idempotent send pipeline with delivery state machine, bounded retry, and dead-letter handling
- Action API: list approvals, approve, reject, pause, resume, takeover
- Simulated inbound-to-outbound workflow tests

### Acceptance tests / definition of done
- Mock message flows all the way to an outbound send in autopilot
- Assisted mode never sends before approval
- Takeover disables future automated sends immediately
- A duplicate event does not result in duplicate sends
- Unsupported connector capabilities fail cleanly

### Constraints and integration notes
All sends must flow through dispatcher. Never put direct send calls inside LLM services.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
