# WING-010: Generate personalized, grounded replies

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 2  
**Dependencies:** WING-005, WING-007, WING-008, WING-009  
**Ownership / expected edit paths:**
- `backend/app/ai/reply_generator.py`
- `backend/app/ai/prompts/`
- `backend/tests/ai/replies/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Generate concise, conversational draft replies consistent with style profile, dating goal, and verified memory.

### Required implementation
- Response generator endpoint/service accepting conversation context and goal
- Prompt templates for serious, casual, casual physical, open, custom
- Draft response, rationale, confidence, required-approval flags
- Guardrails against fabricated personal facts or unauthorized commitments
- Golden-fixture test cases for several texting styles

### Acceptance tests / definition of done
- No reply fabricates location, profession, relationship status, or date availability
- Style controls visibly affect results in fixtures
- Outputs are structured and validated
- When context is inadequate, output can be no-send/ask-user

### Constraints and integration notes
Do not make each mode a canned pickup-line template.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
