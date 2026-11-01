# WING-005: Create provider-agnostic LLM gateway

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 2  
**Dependencies:** WING-002  
**Ownership / expected edit paths:**
- `backend/app/ai/providers/`
- `backend/app/ai/llm_gateway.py`
- `backend/tests/ai/providers/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Provide one stable async model interface for structured outputs, provider errors, usage tracking, and deterministic tests.

### Required implementation
- LLMProvider protocol and one configurable implementation
- MockLLMProvider for tests
- JSON/Pydantic structured output validation, timeout, retry, and error classification
- Token/latency/cost accounting interfaces with configurable unit rates
- Unit tests for malformed output, rate limits, timeout, and fallback behavior

### Acceptance tests / definition of done
- No downstream service depends on a vendor SDK
- Mock mode runs without API keys
- Malformed model data is rejected rather than treated as action permission
- Tests cover retry limits and token-cost records

### Constraints and integration notes
Keep prompts out of controllers. Do not expose raw private messages in logs.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
