# WING-020: Explore macOS iMessage companion feasibility

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 4  
**Dependencies:** WING-006  
**Ownership / expected edit paths:**
- `macos/`
- `docs/integrations/imessage.md`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Investigate a user-authorized local Mac companion that can access Messages and send on behalf of the Mac user.

### Required implementation
- Architecture spike for database/AppleScript/Automation permissions, version compatibility and limitations
- Prototype with local-only test fixtures or real device when available
- Capability and reliability matrix; event capture, read, send, attachments
- How to pair the companion with backend and protect local credentials
- Explicit go/no-go conclusion

### Acceptance tests / definition of done
- Report tested macOS version and permissions or mark UNVERIFIED
- Demonstrate a real send only if supported and actually tested
- Never claim standalone iPhone/iMessage API access
- Work is isolated from the MVP critical path

### Constraints and integration notes
Do not use private APIs as if they were official, stable integrations.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
