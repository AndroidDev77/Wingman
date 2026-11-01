# WING-022: Instrument analytics, cost and operational logs

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 5  
**Dependencies:** WING-003, WING-011  
**Ownership / expected edit paths:**
- `backend/app/observability/`
- `backend/app/api/analytics.py`
- `backend/tests/analytics/`
- `android/app/src/main/java/com/wingman/features/analytics/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Measure outcomes and cost without claiming that proposed or unconfirmed dates occurred.

### Required implementation
- Usage events: analyzed messages, sent messages, manual overrides, connector failures, dates proposed/confirmed
- User dashboard APIs for aggregates by period, intent and connector
- LLM token/cost recording and send delivery latency
- Redacted structured logs and request tracing hooks
- Funnel metrics definitions and analytics tests

### Acceptance tests / definition of done
- Dashboard clearly distinguishes proposed versus confirmed dates
- Historical metrics dedupe retried events
- Cost reporting derives from recorded usage, not fabricated numbers
- Sensitive conversation content excluded from generic application logs

### Constraints and integration notes
Do not measure private partner characteristics without explicit product need.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
