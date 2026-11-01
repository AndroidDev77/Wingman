# WING-009: Implement conversation analysis and stage tracking

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 2  
**Dependencies:** WING-005, WING-008  
**Ownership / expected edit paths:**
- `backend/app/ai/analyzer.py`
- `backend/app/ai/stages.py`
- `backend/tests/ai/analysis/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Classify current conversation status and choose reasonable next steps from explicit evidence.

### Required implementation
- Structured model output for conversation stage, interest signals, ambiguity, next recommended action
- Stages: new, introduction, rapport, interest, date proposal, scheduling, confirmed, follow-up, paused
- Deterministic state transition rules and confidence thresholds
- Tests using scripted conversations with mixed outcomes

### Acceptance tests / definition of done
- Analyzer handles rejection and pauses rather than forcing progression
- Date confirmation is never inferred solely from a proposed time
- Uncertain signals are labeled uncertain
- Stage changes are reproducible in mock-model tests

### Constraints and integration notes
Analysis is advisory and must never itself trigger sending.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
