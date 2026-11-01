# WING-019: Build compatibility scoring and discovery prototype

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 4  
**Dependencies:** WING-005, WING-007  
**Ownership / expected edit paths:**
- `backend/app/ai/match_evaluator.py`
- `backend/app/api/matches.py`
- `backend/tests/matching/`
- `android/app/src/main/java/com/wingman/features/discovery/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Analyze user-provided or officially supported profile data and explain compatibility against preferences.

### Required implementation
- Profile evaluation interface with evidence-linked scores and unknowns
- Preference-matching rules, dealbreakers and transparent score rationale
- Discovery feed using imported/fixture profiles
- Approve/skip recommendation controls
- Swiping interface behind capability flags; no unsupported automated swipes

### Acceptance tests / definition of done
- Scores can identify missing data instead of inventing profile traits
- Each result includes explanation and scored inputs
- Swiping cannot execute unless connector advertises support
- Fixture-based evaluation tests pass

### Constraints and integration notes
Do not scrape or reverse-engineer unsupported apps in this task.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
