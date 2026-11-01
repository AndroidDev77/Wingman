# WING-016: Validate and implement Android SMS connector

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 3  
**Dependencies:** WING-006, WING-012  
**Ownership / expected edit paths:**
- `android/app/src/main/java/com/wingman/integrations/sms/`
- `docs/integrations/android_sms.md`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Establish one real verified messaging transport for the MVP, prioritizing Android SMS where technically viable.

### Required implementation
- Document default-SMS-app role, permissions, testing prerequisites, and platform constraints
- Implement receiver + sender modules, mapping to connector envelope
- Support app lifecycle, duplicate inbound handling, and failed sends
- Create device test checklist, integration test harness, and short demonstration steps
- Deliver manifest/permission integration patch instructions

### Acceptance tests / definition of done
- On a physical Android device with appropriate SMS role, a real incoming SMS is observable
- An authorized outgoing SMS can be sent through the connector
- No double reply on duplicate broadcast/restart
- If physical device is unavailable, clearly report integration UNVERIFIED and document reproducible test steps

### Constraints and integration notes
Do not falsely label a notification-only integration as full SMS capability. Coordinate manifest changes with integrator.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
