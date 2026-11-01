# WING-006: Implement messaging connector SDK and simulator

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 2  
**Dependencies:** WING-002  
**Ownership / expected edit paths:**
- `backend/app/connectors/`
- `backend/app/api/demo_connector.py`
- `backend/tests/connectors/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Define transport-agnostic messaging capabilities and build a mock connector capable of receiving, reading, and sending test messages.

### Required implementation
- Connector protocol: capabilities, list conversations, read messages, send message, observe events; optional get profiles and swipe
- Capabilities model with unsupported operation errors
- MockConnector with deterministic incoming message injection and simulated outgoing acknowledgements
- Deduplication by platform+external message ID and connector event schema
- Backend demo API/CLI and tests

### Acceptance tests / definition of done
- A simulated incoming message can be injected and retrieved
- Mock connector records exactly one outgoing send per idempotency key
- Unimplemented capabilities return typed unsupported result
- No live dating-app API is claimed or hardcoded

### Constraints and integration notes
The generic connector contract is the source of truth for platform agents.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
