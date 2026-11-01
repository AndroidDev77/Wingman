# WING-026: Prepare private beta release

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 6  
**Dependencies:** WING-025  
**Ownership / expected edit paths:**
- `docs/beta/`
- `README.md`
- `scripts/release/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Package an honest, limited beta with onboarding, test plan, support and observable success criteria.

### Required implementation
- Beta release checklist, installation instructions, emulator/device support matrix
- Known-limitation and supported-platform documentation
- Onboarding walkthrough, sample conversations, and feedback form template
- Metrics goal definitions: weekly active users, time saved, assisted-to-date conversion, retention
- Prioritized follow-up roadmap based on observed beta problems

### Acceptance tests / definition of done
- New tester can set up app using documentation
- Unsupported platforms/features visibly distinguished
- Release notes correspond to tested functionality
- No paid launch recommended without one reliable real message connector

### Constraints and integration notes
Track dates confirmed from explicit evidence rather than model inference.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
