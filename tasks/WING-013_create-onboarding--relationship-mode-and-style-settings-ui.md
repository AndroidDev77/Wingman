# WING-013: Create onboarding, relationship-mode and style settings UI

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 3  
**Dependencies:** WING-007, WING-012  
**Ownership / expected edit paths:**
- `android/app/src/main/java/com/wingman/features/onboarding/`
- `android/app/src/main/java/com/wingman/features/settings/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Implement user setup and editable dating preferences using backend contracts.

### Required implementation
- Onboarding wizard for intent, style samples, communication tone and autonomy preference
- Per-contact override controls
- Settings screen for active hours, delays, and message approval
- Form validation, loading/error states, persistence via API client
- Compose UI and ViewModel tests

### Acceptance tests / definition of done
- Settings persist across app restart when backend is available
- Five dating modes can be selected
- A per-contact override does not alter global settings
- Automation is off by default until user enables it

### Constraints and integration notes
Avoid writing shared navigation graph; submit routes for the integration agent to register.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
