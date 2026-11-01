# Wingman AI: Execution Plan

## Recommended team
- **Agent A / repo and contracts:** WING-001, WING-002; owns shared architecture and merges interface decisions.
- **Agent B / backend and data:** WING-003, WING-004, WING-007; independent once contracts are merged.
- **Agent C / AI services:** WING-005, WING-008, WING-009, WING-010; staged according to dependencies.
- **Agent D / connector engineering:** WING-006, WING-016, WING-017; plus the separate research tickets WING-020 and WING-021.
- **Agent E / Android application:** WING-012, WING-013, WING-014, WING-015; different feature packages for separate agents.
- **Agent F / integrations and operations:** WING-011, WING-018, WING-022, WING-023, WING-024, WING-025, WING-026.

## Parallelization schedule
**Wave 0 (bootstrap):** WING-001 only.

**Wave 1 (contract):** WING-002 and WING-023 (CI bootstrap portions only; full task requires WING-003). Merge WING-002 first.

**Wave 2 (parallel independent modules):** WING-003 (DB), WING-005 (LLM gateway), WING-006 (connector SDK), WING-012 (Android shell), WING-021 (dating app feasibility). WING-020 can also start after WING-006.

**Wave 3 (parallel features):** WING-004 (auth), WING-007 (preferences), WING-008 (memory), WING-014 (inbox), WING-016 (SMS), WING-017 (notification feasibility), WING-023 (CI/deploy). Start WING-009 after WING-008.

**Wave 4 (conversation engine):** WING-010 after WING-009; WING-013 after WING-007; WING-019 after WING-007. Then WING-011 after WING-010 and auth/connector are merged.

**Wave 5 (product integration):** WING-015, WING-018, WING-022; then WING-024. WING-025 begins after the app/engine/connector are integrated.

**Wave 6 (ship):** WING-026.

## MVP cutline
Essential: WING-001 through WING-016, WING-023, WING-025, WING-026, except WING-017 may be a separate experimental spike. The minimum demonstration is: user configures an intent and style -> an incoming message is captured -> AI proposes a grounded reply -> manual/assisted/autopilot policy applies -> an outgoing message is sent on **a verified channel** -> user can review and stop the agent. SMS is the preferred real-channel target, but must be verified on an actual Android device.

Later/optional for the MVP: WING-017 through WING-022 and WING-024, except where necessary to validate a particular customer flow.

## Non-negotiable integration gates
1. G1: Contract schema frozen after WING-002.
2. G2: Backend models and core API work in mock mode.
3. G3: Android project builds and talks to mock backend.
4. G4: Outbound path is idempotent and approval-aware.
5. G5: At least one real messaging connector verified with a physical-device demonstration.
6. G6: Manual and automated flows pass end-to-end and are audit-logged.

## Assignment format
Give an agent `AGENT_RULES.md` and ONE `tasks/WING-###_*.md` ticket, plus the repository. When a task is complete, demand the handoff report and tests before merging. Use separate git worktrees for concurrent agents. Avoid two agents editing manifest, DI root, database migrations, and navigation graph at the same time. Have the integrator own those merges.
