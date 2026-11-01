# WING-008: Implement conversation memory extraction and retrieval

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 2  
**Dependencies:** WING-003, WING-005  
**Ownership / expected edit paths:**
- `backend/app/ai/memory.py`
- `backend/app/api/memories.py`
- `backend/tests/ai/memory/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Maintain structured, attributable per-conversation facts and compressed long-term context.

### Required implementation
- Memory extractor with source-message IDs, confidence, expiry/update rules
- Vector search and deterministic fallback retrieval
- Conversation summaries and conflict-resolution logic
- Deletion and correction methods
- Unit tests for source attribution, stale facts, and cross-contact isolation

### Acceptance tests / definition of done
- No facts transfer between contacts or tenants
- Contradictory or corrected facts resolve predictably
- Model receives bounded relevant context rather than all history
- Memory deletion excludes removed facts from future retrieval

### Constraints and integration notes
Do not assert inferred sensitive facts as established facts.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
