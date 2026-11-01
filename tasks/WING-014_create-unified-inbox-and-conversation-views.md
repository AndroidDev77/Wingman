# WING-014: Create unified inbox and conversation views

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 3  
**Dependencies:** WING-006, WING-012  
**Ownership / expected edit paths:**
- `android/app/src/main/java/com/wingman/features/inbox/`
- `android/app/src/main/java/com/wingman/features/conversation/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Build the primary user experience for browsing and reviewing messages across verified connectors.

### Required implementation
- Unified inbox with platform badge, last activity, status, and unread count
- Conversation details with inbound/outbound messages and timestamps
- Message draft area and mock-to-API wiring
- Filters for intent and automation mode
- Compose UI test fixtures for empty/loading/error states

### Acceptance tests / definition of done
- User can open a mock conversation and see chronological messages
- Manual draft creation works through API or mock mode
- Unsupported platforms are labeled as unavailable, not falsely connected
- UI handles 100+ message test fixtures without obvious stalls

### Constraints and integration notes
Do not add platform-specific assumptions to shared conversation UI.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
