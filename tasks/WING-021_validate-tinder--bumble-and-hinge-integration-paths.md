# WING-021: Validate Tinder, Bumble and Hinge integration paths

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 4  
**Dependencies:** WING-006  
**Ownership / expected edit paths:**
- `docs/integrations/dating_platform_matrix.md`
- `experiments/dating_platforms/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Conduct technical feasibility research into messaging, profile reading, and swiping for target dating platforms; do not build speculative integrations.

### Required implementation
- One-row-per-platform capability matrix: read messages, reply, read profiles, swipe, detected reply action, supported auth, evidence, confidence
- Document Android notification test procedure and results when actual access exists
- Identify integration approaches that are supportable and maintainable
- Recommend v1 scope and no-go conditions
- Prototype only mocks or a verified approved integration

### Acceptance tests / definition of done
- Every capability labeled verified/unverified/unsupported
- Each positive claim includes reproducible observation or official documentation
- No credential harvesting or simulated production success
- Results inform sequencing rather than blocking SMS MVP

### Constraints and integration notes
Feasibility report is valuable even if the conclusion is no support.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
