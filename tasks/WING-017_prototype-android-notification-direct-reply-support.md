# WING-017: Prototype Android notification direct-reply support

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 3  
**Dependencies:** WING-006, WING-012  
**Ownership / expected edit paths:**
- `android/app/src/main/java/com/wingman/integrations/notifications/`
- `docs/integrations/notification_matrix.md`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Test exactly which Android messaging apps expose notification text and RemoteInput reply actions.

### Required implementation
- NotificationListenerService proof of concept with user-granted notification access
- RemoteInput capability detection and reply adapter where action exists
- Capability/behavior matrix with Tinder, Bumble, Hinge explicitly marked unverified until device tested
- Graceful handling of missing content, group conversations, notification updates
- Demo test plan and example log output with sensitive text redacted

### Acceptance tests / definition of done
- Test notifications expose explicit can_read/can_reply capability flags
- Adapter never claims a send when RemoteInput action is missing
- No accessibility-based mass tapping/swiping assumed
- All untested target apps explicitly marked unverified

### Constraints and integration notes
This is a feasibility task, not permission to promise Tinder/Bumble/Hinge support.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
