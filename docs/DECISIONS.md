# Integration Decisions and Open Issues

Running log kept by the integrator. Architecture-level decisions graduate to `docs/adr/` (owned by WING-002).

## Decisions

### D-001 (2026-10-31): Seed `main` with the ticket package before WING-001
The GitHub repo was empty. `main` is seeded with `AGENT_RULES.md`, `EXECUTION_PLAN.md`, `TASK_INDEX.md`, `tasks/`, and this log so every agent branch has a common base and can read its ticket from the repo. WING-001 then lands as a normal PR.

### D-002 (2026-10-31): Remote cloud agents open PRs; the integrator reviews and merges
Each ticket goes to a separate remote agent working on its own `wing-###-short-name` branch, which opens a PR against `main`. The integrator re-runs the checks, reviews against the ticket's acceptance criteria and file ownership, then merges. Agents never merge their own PRs or push to `main`.

### D-003 (2026-10-31): Integrator owns shared wiring
Per `EXECUTION_PLAN.md`, only the integrator edits `backend/app/main.py` router registration, Alembic migration ordering, the Android manifest, the DI root, and the navigation graph. Feature PRs that need these changes describe them in the handoff instead.

### D-004 (2026-11-01): Contract v1.0.0 frozen (G1)
Changes to `contracts/` now need their own versioned PR. Deferred to additive PRs by the owning tickets: connection pairing, connector-event ingest route, memory, dates, analytics and billing endpoints.

### D-005 (2026-11-01): Dependency and lockfile conflicts are resolved by the integrator
Wave 2 runs three backend tickets at once. Each may add dependencies to `backend/pyproject.toml`; the integrator merges them and regenerates `uv.lock` at merge time. Each ticket also provides its own FastAPI `APIRouter`, which the integrator registers in `app/main.py`.

### D-006 (2026-11-01): One transport attempt per connector idempotency key
WING-006 reads ADR 0004 literally: each `Idempotency-Key` gets exactly one transport attempt, success or failure, and a repeat returns the stored result with `replayed=true`. **The WING-011 dispatcher must derive a fresh key per retry attempt** (e.g. `send:<message_id>:<attempt>`) while keeping the API-level key stable. Verified live on 2026-11-01: same key twice gave one send; same key with a different body was rejected.

### D-007 (2026-11-01): Fail-closed auth stub as the shared auth interface
`app/security/deps.py::get_current_user_scope() -> UserScope` exists before WING-004 so WING-007/008 can build protected routers in parallel. Until WING-004 replaces it, it rejects every call with 401; there is no bypass. Tests inject a scope through `app.dependency_overrides`. WING-004 must keep the import path and signature.

### D-008 (2026-11-01): Integrator wiring for wave 2
`app/main.py` mounts the WING-006 demo router only when `WINGMAN_ENV != "prod"`, and disposes the DB engine on shutdown. Covered by `tests/test_app_wiring.py` over HTTP.

### D-009 (2026-11-01, superseded by D-010): New migrations are proposed in feature PRs, reviewed by the integrator
WING-003 is merged, so the integrator owns migration ordering. A feature ticket that needs a schema change adds a NEW numbered migration in its PR and flags it in the handoff; never edit `0001`. At most one wave-3 ticket (WING-008) may add `0002` to avoid divergent heads.

### D-010 (2026-11-01): One initial migration until the first production deployment (supersedes D-009)
Repo-owner decision. The app has never been deployed, so until the first production deployment there is exactly ONE Alembic migration, `0001_initial_schema.py`, and schema tickets amend it directly instead of adding `0002`+. CI's migrations job (`scripts/migration-check.sh`) enforces a single head, up/down/up on an empty database, and `alembic check` (no model drift). WING-008 (PR #13) was the first ticket to amend `0001` under this rule (4 provenance columns, 2 CHECKs and 1 index on `conversation_memories`). After the first real deploy, normal additive migrations resume and `0001` is frozen.

### D-011 (2026-11-01): A dedicated integrator agent reviews and merges; green CI is required
The repo owner does not review PRs. A dedicated integrator agent reviews each PR on the owner's behalf: it merges the latest `main` into the branch, checks file ownership and the `AGENT_RULES.md` red lines in the code itself, re-runs the checks locally, pushes, and squash-merges only after **every** GitHub Actions job (`backend`, `migrations`, `contracts`, `docker`, `android`) has concluded `success` on that exact head commit (the merge call pins the head SHA). Agent reports are treated as claims, not evidence. A PR that cannot be made green stays open with a comment explaining why.

### D-012 (2026-11-01): Contract v1.1.0 adopted
PR #15 adds, additively (no existing path, operation, field, enum or `ErrorCode` removed, renamed or narrowed; checked by a structural diff of `contracts/openapi.yaml` against v1.0.0):
- **Device event ingest**, `POST /v1/connections/{connection_id}/events`: one `connector_event.v1` envelope, `Idempotency-Key` = `event_id`, permanent dedup, `mock.*` refused on real connections, and send outcomes accepted only for an instruction leased on that connection.
- **A pull-based device outbox with single-use leases** (ADR 0006), `GET .../outbox` (read-only, no text or destination) and `POST .../outbox/claim`: only the dispatcher creates instructions, an instruction is leased at most once and never renewed, and an expired lease means "outcome unknown": never `sent`, never retried automatically.
- **Memory routes**: `GET /v1/contacts/{contact_id}/memories`, `PUT`/`DELETE /v1/memories/{memory_id}`.

The integrator added one clarification before merging: the claim-time policy re-check cancels exactly what pause/takeover cancel, so a `user_manual` send is not canceled by a pause (v1.0.0 `sendMessage` allows it), and an `autopilot` instruction is canceled once `send` is no longer `VERIFIED` (ADR 0005).

**ADR 0004's `send:<message_id>` key example is superseded by D-006:** connector send keys are per attempt (`send:<message_id>:<attempt>`), which ADR 0006 and the v1.1.0 outbox schemas use.

### D-013 (2026-11-01): WING-010 draft approval reasons are content-based only; WING-011 adds policy and stops on anything but `send_candidate`
PR #20 (WING-010) computes `Draft.approval_reasons` in code from the draft's content only: `date_or_commitment`, `sensitive_content` (escalation or a cited sensitive fact), `low_confidence` (confidence under 0.6, or too long for the brevity setting) and `first_message`. The model's output schema has no approval, send, recipient or policy field. It never sets `assisted_mode`, `outside_active_hours`, `capability_unverified`, `send_limit_reached` or `user_rule`. Therefore:
- **WING-011 must add the policy reasons** on top of `draft.approval_reasons` from effective settings, connection capability and state. It must not treat an empty `approval_reasons` as permission to send.
- **WING-011 must treat any `decision != send_candidate` as a stop.** `no_send` and `ask_user` never reach the dispatcher, whatever the mode.
- Drafts are not persisted (no `drafts` table, I-007). `Draft.id` exists only in the response, the idempotency record and the `draft_generated` audit details.

The integrator added one guardrail before merging. Escalation is detected in code from the draft text as well as from the model's own flag, and at least one cited contact message must itself show matching interest. Otherwise the result is `no_send` (`unsupported_escalation`).

### D-014 (2026-11-01): Pause stops automated sends only; takeover cancels everything automated
Owner-side decision, made by the integrator on the owner's behalf (D-011). Contract v1.1.0 says pause "stops every automated send" and also that "the agent may still queue drafts for approval"; this resolves the ambiguity reported in PR #24.
- **Pause** (global or per contact) stops AUTOMATED sends only: `autopilot` sends, including any already queued, which are canceled at pause time, at dispatch and at device claim. While paused, a new draft goes to the approval queue instead of being sent.
- **Not affected by pause:** a send the user explicitly approved (`approved_draft`, including one approved during the pause) and the user's own `sendMessage` (`user_manual`). Pause does not cancel pending approvals.
- **Takeover** (per contact) cancels everything automated for that contact immediately: pending approvals, approved-but-unsent drafts (`approved_draft` messages still `queued`) and their unclaimed device instructions, and queued `autopilot` sends. The agent creates no drafts or approvals for that contact until resume. The user's own `sendMessage` still goes.
- A message a device has already leased (`sending`) cannot be recalled; its real outcome is recorded (ADR 0006).

PR #24's code already matched this (`PAUSE_CANCELS = (autopilot,)`, `AUTOMATED_ORIGINS = (autopilot, approved_draft)`, `recheck_send`). The integrator added HTTP-level tests for it (`backend/tests/automation/test_d014_pause_semantics.py`).

### D-015 (2026-11-01): Interim handling of contract v1.1.0 gaps found in WING-011
Until the additive contract v1.2 (I-017):
- **Paused is reported as `user_rule`.** `ApprovalReason` has no `paused` value, so an approval caused only by a pause has reason `user_rule`, and the `approval_requested` audit row carries `details.checks.paused = true`.
- **An expired lease is recorded on the instruction and in logs only.** `AgentActionType` has no "lease expired, outcome unknown" type, and `send_failed` would claim a failure nobody observed. The instruction becomes `expired` (visible through `listOutboxInstructions`), a warning is logged with IDs only, and the message stays `sending`.
- **The pipeline runs after the ingest commit, in its own transaction.** `ingestConnectorEvent` commits the inbound message first, then runs memory, analysis, draft and policy synchronously in a new transaction before answering `202`. A pipeline failure is logged by error class only and never undoes the ingest or causes a send. An autopilot send from the pipeline is committed as `queued` before the dispatcher calls the transport, in a further transaction (integrator fix in PR #24, below).
- **Connections have no create or pair route.** They are seeded outside HTTP in dev and tests.

Integrator fix made in PR #24 under D-006: `approve`, `sendMessage` and the pipeline commit the queued outbound message (and the stored idempotent response) before any transport call. Previously the dispatch ran inside the request transaction, so a failure after a real transmission could roll back the only record of it. Now the message stays `queued` and the next dispatch derives the same per-attempt key, so the connector replays its stored result instead of sending again.

Proper contract support (a `paused` approval reason, a `lease_expired` audit type and a connection create/pair route) is deferred to an additive contract v1.2, which the coordinator schedules next.

## Open issues

| ID | Issue | Owner | Status |
|---|---|---|---|
| I-001 | No Android SDK in the cloud sandbox, so Android builds were UNVERIFIED. **Closed:** the CI `android` job (`scripts/android-build.sh`: `assembleDebug` + `testDebugUnitTest`) passes on GitHub runners, on every wave-3 PR and on `main` | Integrator / WING-023 | Closed (PR #8) |
| I-002 | No Docker daemon in the cloud sandbox. Now: the image builds and is smoke-tested in CI (`docker` job, `scripts/docker-smoke.sh`), and `dockerd` can be started in the sandbox (the local build needs the sandbox proxy CA injected into a throwaway Dockerfile copy, never committed). Postgres 16 binaries at `/usr/lib/postgresql/16/bin` remain the default for local DB tests | Integrator | Mitigated |
| I-004 | One problem+json exception handler replaces FastAPI's default 422 body, and datetimes serialize with `Z`. **Closed:** `register_error_handlers(app)` is called in `create_app()`; `tests/test_app_wiring.py` checks 401/404 bodies are `application/problem+json` | WING-004 | Closed (PR #10) |
| I-005 | Run `contracts/validate.sh` in CI. **Closed:** CI `contracts` job | WING-023 | Closed (PR #8) |
| I-006 | Merged branches can't be deleted through the sandbox proxy; delete `wing-001-bootstrap`, `wing-002-contracts`, `wing-003-persistence`, `wing-005-llm-gateway`, `wing-006-connector-sdk`, `wing-012-android-shell`, `wing-int-wave2`, `wing-004-auth`, `wing-007-preferences`, `wing-008-memory`, `wing-014-inbox`, `wing-016-sms`, `wing-023-ci`, `wing-int-wave3`, `contract-v1-1`, `wing-009-analysis`, `wing-013-onboarding`, `wing-int-wave4`, `wing-010-replies`, `wing-008b-memory-api`, `wing-016b-sms-sync`, `wing-int-wave5`, `wing-011-automation`, `android-auth` (and `wing-int-wave6` once merged) in the GitHub UI | Repo owner | Open |
| I-003 | G5 (physical-device SMS verification) needs a human with an Android device; cloud agents cannot satisfy it | Repo owner | Open |
| I-007 | WING-011 needs `approvals` and `drafts` tables plus FKs on the existing `approval_id` columns. **Closed:** PR #24 amended `0001` (D-010) with `drafts` (one agent draft per inbound message), `approvals` (one pending per conversation), tenant-scoped composite FKs, FKs on `messages.approval_id` and `agent_actions.approval_id`, and a unique `messages.approval_id` so an approval can never queue two sends | WING-011 | Closed (PR #24) |
| I-008 | Installing the Android SDK in the sandbox was denied by permissions during WING-012. Superseded by CI: the `android` job builds on GitHub runners (I-001 closed). Still no SDK in the sandbox; local Android checks use the pure-Kotlin JVM verifiers | Repo owner | Mitigated |
| I-009 | `OpenAICompatibleProvider` has never called a real endpoint; the Anthropic provider is not implemented (raises `LLMConfigError`) | Post-MVP | Open |
| I-010 | Device-side connectors (WING-016 SMS) had no route to push `connector_event` envelopes to the backend and no backend-to-device route for a dispatched send. **Contract side** resolved by v1.1.0 (PR #15, D-012, ADR 0006). **Device side** done in PR #21 (`WingmanApiSmsEnvelopeUploader`, `SmsOutboxPoller`), now authenticated through the I-011 session (PR #23). **Backend side** done in PR #24: ingest with permanent `(user_id, event_id)` dedup, the `outbox_instructions` table, claim with `FOR UPDATE SKIP LOCKED`, a claim-time policy re-check and lease expiry (`app.services.outbox`). Backend tested against real Postgres; the device side by JVM tests and the CI compile. **Still open: device verification only.** The two halves have never run together on a phone (I-003) | Repo owner (device test) | Open (device verification only) |
| I-011 | Android `AppContainer` needed a real `tokenProvider`. **Client side done** (PR #23): AppAuth authorization code + PKCE in the system browser (no WebView), tokens AES-256-GCM-encrypted with an Android Keystore key and never logged, refresh near expiry, one refresh and one retry on a 401 and then sign-out (never a loop). The dev-stub token path is debug-only (`src/debug` panel, `src/release` stub, `BuildConfig.DEBUG` guard; no dev secret in the app). Mock builds need no sign-in. Sign-out is on the Settings screen. JVM-tested; compiled in CI. **UNVERIFIED:** Keystore and sign-in on a device, and OIDC against a real IdP (none chosen, I-014) | Repo owner (device, I-014) | Client done; device and IdP unverified |
| I-013 | Memory endpoints were not in the contract, so the WING-008 router was unmounted. **Closed:** contract v1.1.0 (PR #15) added the routes, and PR #19 implemented contract cursor pagination (keyset on `(created_at desc, id desc)`, cursor bound to the contact and `include_history`), switched to the shared problem handler and mounted the router in `create_app()`. Responses are validated against the contract in `tests/ai/memory/test_api.py` | WING-008 follow-up | Closed (PR #19) |
| I-014 | No identity provider chosen. Prod must set `WINGMAN_AUTH_ISSUER`, `WINGMAN_AUTH_AUDIENCE` and `WINGMAN_AUTH_JWKS_URL`; until then every authenticated call returns 401 (fails closed). JWKS fetching is tested only with a mock transport | Repo owner | Open |
| I-012 | `mypy tests/db` reports 27 errors (not part of the check script; `mypy app` is strict-clean) | Low | Open |
| I-015 | Nothing schedules the periodic jobs from WING-011 yet: lease expiry (`python -m app.services.outbox`, about every 30 s), approval expiry (`AutomationController.expire_approvals`) and `Dispatcher.dispatch_due` (reply delays and backend-connector retry backoff). Until then, a lapsed lease stays `leased` (it is still never re-leased or retried, and a late outcome is still accepted); a due device send gets its instruction only when a device claims; pending approvals expire only when someone tries to approve or reject them after `expires_at`; and a delayed or backed-off send to a backend connector waits until something calls `dispatch_due` | WING-022 / WING-023 | Open |
| I-016 | `MockConnector` state (threads, send ledger) lives in process memory. With several workers each has its own simulator, and a restart forgets the send ledger. Dev and tests only, never a real platform | Dev only | Open (accepted) |
| I-017 | Contract v1.2 (additive) needed for the D-015 gaps: a `paused` `ApprovalReason`, a `lease_expired` (outcome unknown) `AgentActionType`, and a connection create/pair route. Until then D-015 applies | Coordinator (next) | Open |
