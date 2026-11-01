# WING-015: Build approval queue, audit timeline, and takeover

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 3  
**Dependencies:** WING-011, WING-012, WING-014  
**Ownership / expected edit paths:**
- `android/app/src/main/java/com/wingman/features/approvals/`
- `android/app/src/main/java/com/wingman/features/automation/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Give users control over what the agent sends and the ability to stop automation instantly.

### Required implementation
- Pending drafts with approve/edit/reject actions
- Per-contact pause/resume/takeover switch
- Audit trail showing proposal, approval, sent, failed states
- Notification badge for pending approvals
- UI tests for rapid repeated clicks and server errors

### Acceptance tests / definition of done
- Assisted drafts never show as sent before backend confirmation
- Takeover UI reports paused state only after server confirmation
- Action loading/error state prevents accidental duplicate submissions
- Each sent response has an auditable action record

### Constraints and integration notes
Use server as source of truth; do not optimistically claim send succeeded.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
