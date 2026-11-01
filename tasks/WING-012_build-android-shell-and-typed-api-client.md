# WING-012: Build Android shell and typed API client

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 3  
**Dependencies:** WING-002  
**Ownership / expected edit paths:**
- `android/app/src/main/java/com/wingman/core/`
- `android/app/src/main/java/com/wingman/navigation/`
- `android/app/src/main/java/com/wingman/ui/theme/`
- `android/app/src/test/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Establish a clean Compose application, navigation, DI, networking, and typed UI state.

### Required implementation
- Compose Navigation routes for Dashboard, Inbox, Conversation, Settings, Approvals, Discovery
- Retrofit/Ktor typed client derived from versioned contracts where feasible
- StateFlow + ViewModel patterns, error/loading states
- Material 3 consistent design system
- Offline mock-data preview and unit tests

### Acceptance tests / definition of done
- Android app navigates between empty placeholder screens
- Mock API client works without running backend
- Build and unit tests run where Android SDK is available
- Network error is surfaced without app crash

### Constraints and integration notes
Own only core/nav/theme modules; feature agents own their feature packages.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
