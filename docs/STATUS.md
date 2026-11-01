# Wingman AI: Task Status Dashboard

Maintained by the integrator. Updated on every dispatch, PR, review and merge.
Verification labels per `AGENT_RULES.md`: **VERIFIED** / **UNVERIFIED** / **UNSUPPORTED**.

Legend: ⬜ pending · 🟦 in progress · 🟨 in review · ✅ completed · ⛔ blocked

| Ticket | Task | Deps | Status | Branch / PR | Notes |
|---|---|---|---|---|---|
| WING-001 | Bootstrap the monorepo | none | ✅ completed | PR #1 | Android build UNVERIFIED (no SDK, I-001) |
| WING-002 | Contracts and architecture | 001 | ✅ completed | PR #2 | Contract v1.0.0 frozen; validate with `contracts/validate.sh` |
| WING-003 | PostgreSQL persistence | 002 | ✅ completed | PR #6 | 14 tables + `idempotency_keys`; tests run against real Postgres 16 |
| WING-004 | Auth and user isolation | 003 | 🟦 in progress | `wing-004-auth` | Wave 3; replaces fail-closed stub `app/security/deps.py`; owns problem+json handler (I-004) |
| WING-005 | LLM gateway | 002 | ✅ completed | PR #3 | Mock + OpenAI-compatible HTTP provider; real endpoint UNVERIFIED |
| WING-006 | Connector SDK and simulator | 002 | ✅ completed | PR #4 | MockConnector; demo router mounted at `/dev/connectors/mock` (non-prod only) |
| WING-007 | Intent and style profiles | 003 | 🟦 in progress | `wing-007-preferences` | Wave 3 |
| WING-008 | Conversation memory | 003, 005 | 🟦 in progress | `wing-008-memory` | Wave 3; vertical slice |
| WING-009 | Analysis and stage tracking | 005, 008 | ⬜ pending | | |
| WING-010 | Grounded reply generation | 005, 007, 008, 009 | ⬜ pending | | Vertical slice |
| WING-011 | Automation controller and dispatch | 004, 006, 010 | ⬜ pending | | Vertical slice |
| WING-012 | Android shell and API client | 002 | ✅ completed | PR #5 | 82 JVM tests pass; Android build/Compose UNVERIFIED (no SDK) |
| WING-013 | Onboarding and settings UI | 007, 012 | ⬜ pending | | |
| WING-014 | Inbox and conversation views | 006, 012 | 🟦 in progress | `wing-014-inbox` | Wave 3; Compose UNVERIFIED without SDK |
| WING-015 | Approvals, audit, takeover UI | 011, 012, 014 | ⬜ pending | | |
| WING-016 | Android SMS connector | 006, 012 | 🟦 in progress | `wing-016-sms` | Wave 3; device test UNVERIFIED until a human runs it (I-003) |
| WING-017 | Notification direct-reply spike | 006, 012 | ⬜ pending | | Optional spike |
| WING-018 | Date proposals and calendar | 003, 011 | ⬜ pending | | Post-MVP |
| WING-019 | Compatibility scoring | 005, 007 | ⬜ pending | | Post-MVP |
| WING-020 | macOS iMessage feasibility | 006 | ⬜ pending | | Post-MVP |
| WING-021 | Tinder/Bumble/Hinge feasibility | 006 | ⬜ pending | | Research only |
| WING-022 | Analytics, cost, logs | 003, 011 | ⬜ pending | | Post-MVP |
| WING-023 | CI and deploy path | 001, 003 | 🟦 in progress | `wing-023-ci` | Wave 3 |
| WING-024 | Subscription entitlements stub | 004, 022 | ⬜ pending | | Post-MVP |
| WING-025 | End-to-end acceptance | 011, 013, 014, 015, 016, 023 | ⬜ pending | | |
| WING-026 | Private beta release | 025 | ⬜ pending | | |

## Integration gates
| Gate | Description | Status |
|---|---|---|
| G1 | Contract schema frozen after WING-002 | ✅ v1.0.0 (PR #2) |
| G2 | Backend models and core API work in mock mode | 🟦 models + mock LLM + mock connector done; `/v1` API pending (004/007/008/011) |
| G3 | Android project builds and talks to mock backend | ⛔ pure-Kotlin layer verified; full build needs an Android SDK (I-001, I-008) |
| G4 | Outbound path idempotent and approval-aware | 🟦 connector-level idempotency verified live; approval path is WING-011 |
| G5 | One real connector verified on a physical device | ⬜ |
| G6 | Manual and automated flows pass E2E, audit-logged | ⬜ |
