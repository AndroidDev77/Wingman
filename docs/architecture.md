# Wingman AI architecture

Status: accepted with WING-002. Interfaces are defined in [`contracts/`](../contracts/README.md), and key decisions are recorded in [`docs/adr/`](adr/).

## Principles

1. **LLM output is a suggestion, never an authorization.** Drafts, analyses and stage classifications are advisory data. Only the automation controller decides whether something may be sent, and it decides from user settings, capabilities and state, not from model text.
2. **Every outbound message goes through one dispatcher.** No LLM service, API handler or connector sends on its own. The dispatcher is the only caller of `Connector.send`.
3. **Capabilities are explicit and verified.** A connector advertises `read`, `draft`, `send`, `swipe` and `calendar_write` independently, each `VERIFIED`, `UNVERIFIED` or `UNSUPPORTED`. Nothing is inferred from anything else. Notification access, for example, does not imply send.
4. **Delivery is reported as observed.** `queued`, `sending`, `sent` and `delivered` are distinct states, and each advances only on the corresponding connector event.
5. **Every action is per-user, idempotent and audited.** Every row and every query is user-scoped, every send-capable action carries an idempotency key, and every decision is written to the append-only audit log (`agent_actions`) with redacted details.

## Modules

| Module | Ticket | Code location | Responsibility |
|---|---|---|---|
| HTTP API | WING-002 contract, many implementers | `backend/app/api/` | Implements `contracts/openapi.yaml`. Thin handlers that delegate to services |
| Auth | WING-004 | `backend/app/security/`, `services/auth.py` | Verifies OIDC JWTs and resolves `sub` to a user. Provides the user-scope dependency |
| Persistence | WING-003 | `backend/app/db/`, `alembic/` | Async SQLAlchemy models and repositories, all user-scoped. Unique `(user_id, platform, external_message_id)` |
| Preferences | WING-007 | `services/preferences.py` | Intent, style and automation settings. Versioned. Per-contact override precedence |
| LLM gateway | WING-005 | `app/ai/llm_gateway.py`, `ai/providers/` | Vendor-neutral structured-output calls, plus retries, cost accounting and a mock provider |
| Memory | WING-008 | `app/ai/memory.py` | Attributable per-contact facts with source message IDs. Bounded retrieval |
| Analyzer | WING-009 | `app/ai/analyzer.py`, `stages.py` | Advisory `ConversationAnalysis` and `ConversationStage` |
| Reply generator | WING-010 | `app/ai/reply_generator.py` | Produces a `Draft` (`send_candidate`, `no_send` or `ask_user`) with grounding and approval flags |
| Automation controller | WING-011 | `services/automation.py` | Policy engine: decides whether to discard a draft, send it to the approval queue, or hand it to the dispatcher |
| Dispatcher | WING-011 | `services/dispatcher.py` | The single send path: idempotent, handles retries and dead letters, runs the delivery state machine |
| Connector SDK | WING-006 | `app/connectors/` | `Connector` protocol, `ConnectorCapabilities`, event ingestion and dedup, `MockConnector` |
| Device connectors | WING-016, WING-017 | `android/.../integrations/` | SMS and notification adapters that emit `connector_event.v1` and execute dispatched sends |
| Dates and calendar | WING-018 | `services/dates.py`, `integrations/calendar/` | `DateProposal` lifecycle. Calendar writes need `calendar_write` plus authorization |
| Observability | WING-022 | `app/observability/` | Redacted logs, usage events, cost tracking |
| Android app | WING-012 to WING-015 | `android/` | Typed client for `/v1`. The server is the source of truth, and the app never displays an optimistic "sent" |

## Trust boundaries

```
 ┌──────────────── Android device (user-controlled) ────────────────┐
 │  Wingman app (UI)          Device connectors (SMS, notifications) │
 │   - holds user's JWT        - hold OS permissions (SMS role,      │
 │   - renders server state      notification access)                │
 └────────┬───────────────────────────┬──────────────────────────────┘
          │ HTTPS + bearer JWT        │ HTTPS + bearer JWT
          │ /v1 (openapi.yaml)        │ connector_event.v1 up / dispatched sends down
 ═════════╪═══════════════════════════╪══════════ boundary B1 ═════════
 ┌────────▼───────────────────────────▼──────── Wingman backend ─────┐
 │  API -> services -> automation controller -> dispatcher           │
 │  Postgres (user-scoped rows)   Redis   audit log                  │
 └────────┬──────────────────────────────────────┬───────────────────┘
 ═════════╪══════ boundary B2 ═══════════════════╪═══ boundary B3 ════
          │ prompts (minimized context)          │ platform APIs (only where
 ┌────────▼──────────┐                 ┌─────────▼────────┐  officially supported)
 │ LLM provider      │                 │ Messaging        │
 │ (untrusted output)│                 │ platforms        │
 └───────────────────┘                 └──────────────────┘
```

| Boundary | What crosses it | Trust rule |
|---|---|---|
| **B1: device to backend** | API calls, connector events, send instructions | Every request is authenticated (bearer JWT) and validated against the contract, and unknown fields are rejected. The backend re-checks user ownership of every ID, and another user's resource gets `404`. Connector events are deduplicated by `(user, platform, external_message_id)` and rejected if their `connection_id` is not owned by `user_id`. |
| **B2: backend to LLM provider** | Minimized conversation context and memories, plus structured outputs | Model output is **untrusted input**. It is schema-validated, and malformed output is rejected, never "best-effort" executed. Outputs cannot set policy, approvals, capabilities or recipients. Raw private text is never logged. API keys exist only on the backend, and mock mode needs none. |
| **B3: backend/device to messaging platforms** | Inbound messages, outbound sends, receipts | Only capabilities the connector advertises are used. `UNSUPPORTED` returns a typed error, and `UNVERIFIED` send is allowed only with explicit approval. No scraping, credential harvesting or unsupported swiping. Delivery state follows real acknowledgements. |

Within the backend, the boundary between the **AI layer** (gateway, memory, analyzer and generator) and the **action layer** (controller and dispatcher) is a code-level rule: the AI layer has no access to connectors. WING-011 should enforce this with an import-boundary test.

## Vertical slice: incoming message to audit log

```
connector ─(1)─▶ ingest ─(2)─▶ memory ─(3)─▶ analyze ─(4)─▶ generate draft
                                                                   │
           audit log ◀─(8)─ connector ack ◀─(7)─ dispatcher ◀─(6)─ policy ─(5)─┘
                                                     ▲                  │
                                     user approve ───┘     approval queue ◀┘
```

1. **Incoming message.** A connector emits `message.received` (or `mock.inbound_message_injected` in tests), a `connector_event.v1` envelope. Ingest validates it, checks that the connection belongs to `user_id`, and deduplicates on `(user_id, platform, external_message_id)`. A duplicate is acknowledged and dropped, with no second message and no second draft. The message is stored with `delivery_state: received`. Audit: `message_received`.
2. **Conversation memory.** WING-008 extracts facts, each attributed to source message IDs, scoped to this user and contact. Retrieval returns a bounded, relevant set.
3. **Analysis.** WING-009 updates the advisory `ConversationAnalysis` (stage, signals, `uncertain`, `next_action`). Audit: `analysis_updated`. Analysis never triggers a send.
4. **AI-generated reply.** WING-010 calls the LLM gateway with the effective preferences, grounded memories and recent messages. It returns a `Draft` with a `decision`, `text`, `rationale`, `confidence`, `requires_approval`, `approval_reasons` and `grounding`. `no_send` and `ask_user` are valid outcomes. Audit: `draft_generated`.
5. **Automation policy.** The controller (WING-011) evaluates the draft against effective settings and state, in order. The first failing check decides the outcome:
   - draft `decision` is not `send_candidate`: stop (an `ask_user` result is surfaced to the user)
   - global pause, or contact `paused` or `taken_over`: no automated send (when paused, the draft may go to approval; when taken over, it is discarded)
   - connection `send` is `UNSUPPORTED`: `policy_blocked` with `capability_unsupported`
   - effective mode `manual`: no send, and the draft is available on request only
   - effective mode `assisted`, `send` is `UNVERIFIED`, the draft has `requires_approval`, the reason is in `approval_required_for`, the time is outside active hours, or the daily limit is reached: create an `Approval` (`approval_requested`)
   - effective mode `autopilot` and all checks pass: hand the draft to the dispatcher

   An `Approval` is approved only by an authenticated user call (`POST /v1/approvals/{id}/approve` with an `Idempotency-Key`), never by the model.
6. **Outgoing message (dispatcher).** The dispatcher creates the outbound `Message` (`queued`, audit `send_queued`) under a send idempotency key derived from the message ID. Just before sending, it re-checks capability and pause state, because a takeover may have happened since step 5. If the contact was taken over, the message becomes `canceled`. Otherwise it calls `Connector.send` once per key (`sending`, `send_attempted`).
7. **Acknowledgement.** The connector reports `message.send_acknowledged` (moving the message to `sent`), `message.send_failed` (moving it to `failed`, then a bounded retry back to `queued`, or `dead_lettered`), and optionally `message.delivery_receipt` (moving it to `delivered`). States change only on these events.
8. **Audit log.** Every step above appends an `AgentAction` with an actor, a reason code and redacted `details` (no message text). `GET /v1/agent-actions` exposes the timeline for WING-015.

User-authored sends (`POST /v1/conversations/{id}/messages`) enter at step 6. They skip the AI and the policy's mode checks, but still go through the dispatcher's capability check, idempotency and audit.

## Stopping the agent

- **Pause (per contact or global):** takes effect before the response returns. No automated sends happen afterwards, and queued automated messages are `canceled`.
- **Takeover (per contact):** does everything pause does, and also cancels pending approvals and stops the agent generating drafts and approvals on its own until resume. The user can still request drafts and send manually.
- Messages already `sending` cannot be recalled, so their real outcome is still recorded.

## Data handling

- Message text and memory facts are stored per user and never written to application logs. Audit `details` hold only IDs, counts, codes and latencies.
- Every repository method takes the user scope. Cross-user access is a `404`.
- LLM prompts include only the bounded context needed for the current draft.

## Out of scope for v1 HTTP contract

- Swiping and discovery (WING-019): `swipe` exists only as a capability flag. No endpoint requires it.
- Date-proposal endpoints (WING-018): the `DateProposal` shape and `DateProposalState` enum are defined so that persistence can be built. Endpoints will come in an additive contract PR.
- Connection onboarding and pairing endpoints, analytics and billing: each of these goes in its owning ticket's additive contract PR.

Added in contract v1.1.0 (PR #15, D-012), so no longer out of scope: device event ingest (`POST /v1/connections/{connection_id}/events`), the pull-based device outbox with single-use leases (`GET /v1/connections/{connection_id}/outbox`, `POST .../outbox/claim`, ADR 0006), and the memory routes (`GET /v1/contacts/{contact_id}/memories`, `PUT`/`DELETE /v1/memories/{memory_id}`). They are specified but not implemented yet: WING-011 implements ingest, outbox and claim; the WING-008 memory router still needs contract pagination before it is mounted.
