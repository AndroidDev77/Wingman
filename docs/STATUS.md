# Wingman AI: Task Status Dashboard

Maintained by the integrator. Updated on every dispatch, PR, review and merge.
Verification labels per `AGENT_RULES.md`: **VERIFIED** / **UNVERIFIED** / **UNSUPPORTED**.

Legend: ⬜ pending · 🟦 in progress · 🟨 in review · ✅ completed · ⛔ blocked

| Ticket | Task | Deps | Status | Branch / PR | Notes |
|---|---|---|---|---|---|
| WING-001 | Bootstrap the monorepo | none | ✅ completed | PR #1 | Android build UNVERIFIED (no SDK, I-001) |
| WING-002 | Contracts and architecture | 001 | ✅ completed | PR #2 | Contract v1.0.0 frozen; validate with `contracts/validate.sh` |
| Contract v1.1.0 | Device ingest, device outbox, memory routes | 002 | ✅ completed | PR #15 | Additive (no existing path, field or enum changed); ADR 0006 leases. Memory routes mounted (PR #19); device side (PR #21); backend ingest/outbox/claim (PR #24). Gaps closed by v1.2.0 |
| Contract v1.2.0 | Pairing, capability trust, `paused`, `lease_expired` | 011 | ✅ completed | PR #28 | Additive (key-by-key diff vs v1.1.0); ADR 0007, D-016, I-017 closed. Client-claimed `VERIFIED` stored `UNVERIFIED`; only the operator CLI with evidence verifies. Atomic unpair. Android enums updated by the integrator. Follow-ups I-018, I-019, I-020 (app does not pair yet) |
| I-015 worker | Background worker for dispatch, lease and approval expiry | 011 | ✅ completed | PR #27 | `python -m app.worker` + compose `migrate`/`worker`; per-message transactions through `Dispatcher.dispatch`; safe with replicas (row locks + D-006 keys). `scripts/dev-run.sh` = single-process dev mode with the mock (I-016). Azure worker app UNVERIFIED |
| WING-003 | PostgreSQL persistence | 002 | ✅ completed | PR #6 | 14 tables + `idempotency_keys`; tests run against real Postgres 16 |
| WING-004 | Auth and user isolation | 003 | ✅ completed | PR #10 | OIDC JWT verifier, dev stub refused in prod (startup check), `GET /v1/me` mounted, problem+json handlers registered (I-004 closed). No IdP chosen yet (I-014); real IdP UNVERIFIED |
| WING-005 | LLM gateway | 002 | ✅ completed | PR #3 | Mock + OpenAI-compatible HTTP provider; real endpoint UNVERIFIED |
| WING-006 | Connector SDK and simulator | 002 | ✅ completed | PR #4 | MockConnector; demo router mounted at `/dev/connectors/mock` (non-prod only) |
| WING-007 | Intent and style profiles | 003 | ✅ completed | PR #11 | Six `/v1` preference routes mounted; automation defaults to `manual` |
| WING-008 | Conversation memory | 003, 005 | ✅ completed | PR #13, follow-up PR #19 | Amended `0001` (D-010). Follow-up PR #19: router conforms to contract v1.1.0 (cursor pagination, cursor bound to contact and `include_history`, shared problem handler) and is mounted (I-013 closed). Extraction quality with a real model UNVERIFIED |
| WING-009 | Analysis and stage tracking | 005, 008 | ✅ completed | PR #16 | `ConversationAnalyzer` + deterministic stage rules; advisory only (import-boundary test). `confirmed` needs cited two-party agreement on a specific plan; model failure falls back without advancing. No route (surfaces via `ConversationDetail.analysis`). Classification quality with a real model UNVERIFIED |
| WING-010 | Grounded reply generation | 005, 007, 008, 009 | ✅ completed | PR #20 | `POST /v1/conversations/{id}/drafts` mounted (`Idempotency-Key` per ADR 0004). Advisory only (import-boundary test). Guardrails in code: ungrounded user facts give `ask_user`; commitments need approval; escalation needs a cited contact message showing matching interest (integrator fix); explicit or coercive wording gives `no_send`; model failure gives a typed `no_send`. `approval_reasons` are content-based only (D-013). Drafts not persisted (I-007). Quality with a real model UNVERIFIED |
| WING-011 | Automation controller and dispatch | 004, 006, 010 | ✅ completed | PR #24 | Policy engine (decides from settings, state and capabilities; any `decision != send_candidate` stops), approval queue, pause/resume/takeover (D-014), the single dispatcher (per-attempt keys, retries, dead letter), device ingest and the leased outbox (ADR 0006), and the audit timeline; 16 contract operations mounted. `0001` amended (I-007 closed). Integrator fixes: queued sends commit before any transport call; D-014 tests. 859 backend tests. Periodic jobs not scheduled (I-015); contract gaps handled per D-015 until v1.2 (I-017). Real device send UNVERIFIED |
| I-011 | Android sign-in and token provider | 004, 012, 013 | ✅ completed (client) | PR #23 | AppAuth code + PKCE in the system browser, Keystore AES-GCM token storage, refresh near expiry, one refresh + one retry on 401 then sign-out; dev-token sign-in in debug builds only; mock builds need no sign-in. 295 JVM tests in the local verifier; CI compiles the debug and release variants. Device and real IdP UNVERIFIED (I-014) |
| WING-012 | Android shell and API client | 002 | ✅ completed | PR #5 | 82 JVM tests pass; Android build/Compose UNVERIFIED (no SDK) |
| WING-013 | Onboarding and settings UI | 007, 012 | ✅ completed | PR #17 | Settings and Onboarding registered; first-launch rule in `WingmanApp` (server `version == 0` only). Automation stays manual until a confirmed user action; 412 never overwrites. Compose compiles in CI; not run on a device. Persistence against a live backend UNVERIFIED (device). Sign out on Settings (I-011, PR #23) |
| WING-014 | Inbox and conversation views | 006, 012 | ✅ completed | PR #9 | Registered in `featureScreens()`; Compose compiles in CI; not yet run on a device; data from the mock API |
| WING-015 | Approvals, audit, takeover UI | 011, 012, 014 | ✅ completed | PR #26 | Approvals destination (To approve / Controls / Activity) and nav badge registered. Server is the source of truth: an approved draft shows "Queued, not sent yet" until the server reports `sent`; pause/takeover switches move only after server confirmation. One idempotency key per action, reused on retry; `409 approval_not_pending` re-reads and explains. `MockWingmanApi` follows D-014. Contract v1.2.0 labels added by the integrator (`paused`; `lease_expired` reads as outcome unknown). 350 JVM tests in the local verifier; Compose compiles in CI. Device and live backend UNVERIFIED |
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
| G2 | Backend models and core API work in mock mode | ✅ (one gap, I-021: `listConversations`, `getConversation` and `listMessages` have no backend route) every other v1.1.0 operation is implemented and mounted, with mock LLM and mock connector: auth, preferences, memory, drafts, approvals, sends, automation state, audit, connector-event ingest and the device outbox (WING-011, PR #24) |
| G3 | Android project builds and talks to mock backend | ✅ CI `android` job builds the debug APK (and compiles the release variant) and passes the JVM tests on `main`. The app now signs in and sends a bearer token to a live backend (I-011, PR #23); running it against one on a device is UNVERIFIED |
| G4 | Outbound path idempotent and approval-aware | ✅ PR #24, verified by the integrator: one dispatcher is the only sender (an architecture test fails on a planted violation), per-attempt connector keys (D-006), queued sends committed before the transport so a crash replays rather than resends, one lease per instruction (`SKIP LOCKED`), and approval-aware policy. Proven by the concurrency tests (10 parallel duplicate events, 6 parallel approves and 6 devices claiming each give exactly one send or lease; 3 extra local runs green), the approval tests (assisted never sends before approval, then exactly once; reject sends nothing) and the D-014 tests. A real device send is still G5 |
| G5 | One real connector verified on a physical device | ⛔ WING-016 SMS is UNVERIFIED until a human runs the device checklist (I-003) |
| G6 | Manual and automated flows pass E2E, audit-logged | ⬜ backend flows are audit-logged and pass a live HTTP smoke test (incl. pairing via `POST /v1/connections` and worker-driven delayed sends in single-process dev mode); the WING-015 approvals/audit/takeover UI is done (PR #26). Needs the conversation read routes (I-021), app-side SMS pairing (I-020) and the WING-025 end-to-end suite |
