# Wingman AI: Task Status Dashboard

Maintained by the integrator. Updated on every dispatch, PR, review and merge.
Verification labels per `AGENT_RULES.md`: **VERIFIED** / **UNVERIFIED** / **UNSUPPORTED**.

Legend: ⬜ pending · 🟦 in progress · 🟨 in review · ✅ completed · ⛔ blocked

| Ticket | Task | Deps | Status | Branch / PR | Notes |
|---|---|---|---|---|---|
| WING-001 | Bootstrap the monorepo | none | ✅ completed | PR #1 | Android build UNVERIFIED (no SDK, I-001) |
| WING-002 | Contracts and architecture | 001 | ✅ completed | PR #2 | Contract v1.0.0 frozen; validate with `contracts/validate.sh` |
| WING-003 | PostgreSQL persistence | 002 | 🟦 in progress | `wing-003-persistence` | Wave 2 |
| WING-004 | Auth and user isolation | 003 | ⬜ pending | | |
| WING-005 | LLM gateway | 002 | 🟦 in progress | `wing-005-llm-gateway` | Wave 2 |
| WING-006 | Connector SDK and simulator | 002 | 🟦 in progress | `wing-006-connector-sdk` | Wave 2 |
| WING-007 | Intent and style profiles | 003 | ⬜ pending | | |
| WING-008 | Conversation memory | 003, 005 | ⬜ pending | | Vertical slice |
| WING-009 | Analysis and stage tracking | 005, 008 | ⬜ pending | | |
| WING-010 | Grounded reply generation | 005, 007, 008, 009 | ⬜ pending | | Vertical slice |
| WING-011 | Automation controller and dispatch | 004, 006, 010 | ⬜ pending | | Vertical slice |
| WING-012 | Android shell and API client | 002 | 🟦 in progress | `wing-012-android-shell` | Wave 2; build UNVERIFIED without SDK |
| WING-013 | Onboarding and settings UI | 007, 012 | ⬜ pending | | |
| WING-014 | Inbox and conversation views | 006, 012 | ⬜ pending | | |
| WING-015 | Approvals, audit, takeover UI | 011, 012, 014 | ⬜ pending | | |
| WING-016 | Android SMS connector | 006, 012 | ⬜ pending | | Needs physical device for G5 |
| WING-017 | Notification direct-reply spike | 006, 012 | ⬜ pending | | Optional spike |
| WING-018 | Date proposals and calendar | 003, 011 | ⬜ pending | | Post-MVP |
| WING-019 | Compatibility scoring | 005, 007 | ⬜ pending | | Post-MVP |
| WING-020 | macOS iMessage feasibility | 006 | ⬜ pending | | Post-MVP |
| WING-021 | Tinder/Bumble/Hinge feasibility | 006 | ⬜ pending | | Research only |
| WING-022 | Analytics, cost, logs | 003, 011 | ⬜ pending | | Post-MVP |
| WING-023 | CI and deploy path | 001, 003 | ⬜ pending | | CI-bootstrap portion can start after 001 |
| WING-024 | Subscription entitlements stub | 004, 022 | ⬜ pending | | Post-MVP |
| WING-025 | End-to-end acceptance | 011, 013, 014, 015, 016, 023 | ⬜ pending | | |
| WING-026 | Private beta release | 025 | ⬜ pending | | |

## Integration gates
| Gate | Description | Status |
|---|---|---|
| G1 | Contract schema frozen after WING-002 | ✅ v1.0.0 (PR #2) |
| G2 | Backend models and core API work in mock mode | ⬜ |
| G3 | Android project builds and talks to mock backend | ⬜ |
| G4 | Outbound path idempotent and approval-aware | ⬜ |
| G5 | One real connector verified on a physical device | ⬜ |
| G6 | Manual and automated flows pass E2E, audit-logged | ⬜ |
