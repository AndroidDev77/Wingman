# Wingman AI: Task Status Dashboard

Maintained by the integrator. Updated on every dispatch, PR, review and merge.
Verification labels per `AGENT_RULES.md`: **VERIFIED** / **UNVERIFIED** / **UNSUPPORTED**.

Legend: ⬜ pending · 🟦 in progress · 🟨 in review · ✅ completed · ⛔ blocked

| Ticket | Task | Deps | Status | Branch / PR | Notes |
|---|---|---|---|---|---|
| WING-001 | Bootstrap the monorepo | none | ✅ completed | PR #1 | Android build UNVERIFIED (no SDK, I-001) |
| WING-002 | Contracts and architecture | 001 | ✅ completed | PR #2 | Contract v1.0.0 frozen; validate with `contracts/validate.sh` |
| Contract v1.1.0 | Device ingest, device outbox, memory routes | 002 | ✅ completed | PR #15 | Additive (no existing path, field or enum changed); ADR 0006 leases. Memory routes implemented and mounted (PR #19). Device side of ingest/claim implemented (PR #21); backend ingest/outbox/claim still WING-011 |
| WING-003 | PostgreSQL persistence | 002 | ✅ completed | PR #6 | 14 tables + `idempotency_keys`; tests run against real Postgres 16 |
| WING-004 | Auth and user isolation | 003 | ✅ completed | PR #10 | OIDC JWT verifier, dev stub refused in prod (startup check), `GET /v1/me` mounted, problem+json handlers registered (I-004 closed). No IdP chosen yet (I-014); real IdP UNVERIFIED |
| WING-005 | LLM gateway | 002 | ✅ completed | PR #3 | Mock + OpenAI-compatible HTTP provider; real endpoint UNVERIFIED |
| WING-006 | Connector SDK and simulator | 002 | ✅ completed | PR #4 | MockConnector; demo router mounted at `/dev/connectors/mock` (non-prod only) |
| WING-007 | Intent and style profiles | 003 | ✅ completed | PR #11 | Six `/v1` preference routes mounted; automation defaults to `manual` |
| WING-008 | Conversation memory | 003, 005 | ✅ completed | PR #13, follow-up PR #19 | Amended `0001` (D-010). Follow-up PR #19: router conforms to contract v1.1.0 (cursor pagination, cursor bound to contact and `include_history`, shared problem handler) and is mounted (I-013 closed). Extraction quality with a real model UNVERIFIED |
| WING-009 | Analysis and stage tracking | 005, 008 | ✅ completed | PR #16 | `ConversationAnalyzer` + deterministic stage rules; advisory only (import-boundary test). `confirmed` needs cited two-party agreement on a specific plan; model failure falls back without advancing. No route (surfaces via `ConversationDetail.analysis`). Classification quality with a real model UNVERIFIED |
| WING-010 | Grounded reply generation | 005, 007, 008, 009 | ✅ completed | PR #20 | `POST /v1/conversations/{id}/drafts` mounted (`Idempotency-Key` per ADR 0004). Advisory only (import-boundary test). Guardrails in code: ungrounded user facts give `ask_user`; commitments need approval; escalation needs a cited contact message showing matching interest (integrator fix); explicit or coercive wording gives `no_send`; model failure gives a typed `no_send`. `approval_reasons` are content-based only (D-013). Drafts not persisted (I-007). Quality with a real model UNVERIFIED |
| WING-011 | Automation controller and dispatch | 004, 006, 010 | ⬜ pending | | Vertical slice |
| WING-012 | Android shell and API client | 002 | ✅ completed | PR #5 | 82 JVM tests pass; Android build/Compose UNVERIFIED (no SDK) |
| WING-013 | Onboarding and settings UI | 007, 012 | ✅ completed | PR #17 | Settings and Onboarding registered; first-launch rule in `WingmanApp` (server `version == 0` only). Automation stays manual until a confirmed user action; 412 never overwrites. Compose compiles in CI; not run on a device. Persistence against a live backend UNVERIFIED until I-011 |
| WING-014 | Inbox and conversation views | 006, 012 | ✅ completed | PR #9 | Registered in `featureScreens()`; Compose compiles in CI; not yet run on a device; data from the mock API |
| WING-015 | Approvals, audit, takeover UI | 011, 012, 014 | ⬜ pending | | |
| WING-016 | Android SMS connector | 006, 012 | ✅ completed (**UNVERIFIED** on device) | PR #12, follow-up PR #21 | Manifest patched; adapters compile in CI. Follow-up PR #21: HTTP event uploader and outbox claim poller (contract v1.1.0, ADR 0006), started from `MainActivity`; WorkManager dependency added, periodic worker not yet. 261 JVM tests. Backend side of ingest/claim is WING-011 (I-010); no token provider (I-011). Real SMS receive/send UNVERIFIED until a human runs the device checklist (I-003) |
| WING-017 | Notification direct-reply spike | 006, 012 | ⬜ pending | | Optional spike |
| WING-018 | Date proposals and calendar | 003, 011 | ⬜ pending | | Post-MVP |
| WING-019 | Compatibility scoring | 005, 007 | ⬜ pending | | Post-MVP |
| WING-020 | macOS iMessage feasibility | 006 | ⬜ pending | | Post-MVP |
| WING-021 | Tinder/Bumble/Hinge feasibility | 006 | ⬜ pending | | Research only |
| WING-022 | Analytics, cost, logs | 003, 011 | ⬜ pending | | Post-MVP |
| WING-023 | CI and deploy path | 001, 003 | ✅ completed | PR #8 | Five CI jobs green on `main`. Azure deploy path is an UNVERIFIED sample; nothing deployed |
| WING-024 | Subscription entitlements stub | 004, 022 | ⬜ pending | | Post-MVP |
| WING-025 | End-to-end acceptance | 011, 013, 014, 015, 016, 023 | ⬜ pending | | |
| WING-026 | Private beta release | 025 | ⬜ pending | | |

## Integration gates
| Gate | Description | Status |
|---|---|---|
| G1 | Contract schema frozen after WING-002 | ✅ v1.0.0 (PR #2); additive v1.1.0 (PR #15: device ingest, device outbox with leases, memory routes) |
| G2 | Backend models and core API work in mock mode | 🟦 models, mock LLM, mock connector, auth (`/v1/me`), preferences, analysis (WING-009), memory routes (PR #19) and drafts (WING-010, PR #20) done and mounted; approvals, sends, automation state, connector-event ingest and the device outbox are pending (WING-011) |
| G3 | Android project builds and talks to mock backend | ✅ CI `android` job builds the debug APK and passes the JVM tests on `main`. The app talks to the in-app mock API; talking to a live backend is still mock (no token provider, I-011) |
| G4 | Outbound path idempotent and approval-aware | 🟦 connector-level idempotency verified live; the device side of the leased outbox (one radio attempt per key, lease deadline before the radio) is JVM-tested (PR #21); drafts are advisory with content approval flags (D-013). The controller, approvals, dispatcher and backend outbox are still WING-011 |
| G5 | One real connector verified on a physical device | ⛔ WING-016 SMS is UNVERIFIED until a human runs the device checklist (I-003) |
| G6 | Manual and automated flows pass E2E, audit-logged | ⬜ |
