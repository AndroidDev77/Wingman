# WING-024: Add subscription entitlements and billing stub

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 5  
**Dependencies:** WING-004, WING-022  
**Ownership / expected edit paths:**
- `backend/app/billing/`
- `backend/app/api/billing.py`
- `backend/tests/billing/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Make feature gating and usage accounting subscription-ready while keeping external payment processing optional.

### Required implementation
- Basic, Pro, Premium plan configuration and entitlement service
- Server-enforced feature checks and usage limits
- Trial/free-dev entitlements and webhook adapter interface
- Idempotent entitlement update and subscription state transitions
- Tests for upgrade, downgrade, expiry and unauthorized access

### Acceptance tests / definition of done
- Basic users cannot invoke paid autopilot capabilities
- Expired subscription loses paid entitlements predictably
- Billing mock works without payment-provider credentials
- No live payment flow claimed until provider tested

### Constraints and integration notes
External billing provider integration can be a separate post-MVP ticket.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
