# 0006: Device event ingest and a pull-based, leased device outbox

- Status: Accepted (contract v1.1.0, 2026-11-01). Refines ADR 0004 for the ingest route only. Resolves the contract side of I-010.

## Context
Some connectors run on the user's phone, not in the backend: SMS (WING-016) needs the default-SMS-app role and `SmsManager`, and notification replies (WING-017) need the notification listener. Contract v1.0.0 had no way for such a connector to report events, and no way for the backend to hand it a send that the dispatcher (WING-011) authorized. Until both exist, device envelopes stay queued on the phone (`NoIngestRouteUploader`), and a device send can only come from the debug harness.

The constraints are:
- **The dispatcher is the only sender** (architecture principle 2). For a device transport that means the backend decides and the device executes. The backend never transmits on a device transport, and a device never sends anything the dispatcher did not authorize.
- **A duplicate send to a dating contact causes real harm** (ADR 0004). Retries, flaky mobile networks, two installs of the app, or a reinstall must never produce a second transmission.
- **D-006:** each connector idempotency key gets exactly one transport attempt. A retry needs a new key (`send:<message_id>:<attempt>`).
- **Delivery truth:** `queued`, `sending`, `sent` and `delivered` advance only on real connector events.

## Decision

### Uplink: `POST /v1/connections/{connection_id}/events` (`ingestConnectorEvent`)
- The body is one `connector_event.v1` envelope, and `Idempotency-Key` must equal its `event_id`.
- The server checks ownership first (the connection and `user_id` must be the caller's, otherwise `404`). It then checks consistency: the path and body `connection_id` must match, the platform must match the connection, and the envelope must validate. Otherwise it returns `422`.
- `mock.*` events are accepted only on a `mock` connection. On a real connection they are `422` with pointer `/type`.
- Send outcomes (`send_acknowledged`, `send_failed`, `delivery_receipt`) must reference an instruction the dispatcher issued to **this** connection that was actually leased. Otherwise they are `422` with a pointer under `/payload`. A device can report the outcome of a send it was authorized to perform, but it can never create an outbound message.
- A new event returns `202 accepted`. A repeat returns `200` with `duplicate_event` (same `event_id`) or `duplicate_message` (same `(user_id, platform, external_message_id)` under a different `event_id`) and has no side effect. Event dedup is permanent, unlike the 24-hour idempotency retention. This route answers `200 duplicate_event` instead of replaying the original `202`. A reused key with a different body is still `422 idempotency_key_mismatch`.

### Downlink: a pull-based outbox with single-use leases
- **Only the dispatcher creates instructions.** WING-011 creates an instruction after the policy, approval and capability checks. No API operation creates one. Each instruction is one attempt: `outbound_message_id`, `attempt`, and a unique per-attempt `idempotency_key` (D-006).
- **Claiming leases instructions.** `POST /v1/connections/{id}/outbox/claim` (`claimOutboxInstructions`, with `Idempotency-Key` and a `device_install_id`) atomically leases `pending` instructions to the calling install, up to `max_instructions` (1 to 20). The server sets `lease_expires_at`: 120 s by default, bounded to 30 to 600 s. Each leased message moves `queued -> sending` (audit `send_attempted`).
- **Policy is re-checked at claim time.** Global pause and contact pause or takeover cancel the instruction instead of leasing it, exactly when those actions would have canceled the queued message: a `user_manual` send is never canceled by them, because `sendMessage` is allowed while paused or taken over. An `UNSUPPORTED` send capability cancels any instruction, and a `send` capability that is no longer `VERIFIED` cancels an `autopilot` instruction (ADR 0005). This is the last moment the backend can still stop a device send, which matches the dispatcher's "re-check just before sending" rule.
- **An instruction is leased at most once, ever.** Two devices polling at the same time cannot both receive it; the implementation uses row locks such as `FOR UPDATE SKIP LOCKED`. A lease is never renewed, transferred or re-issued. A claim also returns the caller's own unexpired leases, so a device that lost a response can recover them. A replay of the same `Idempotency-Key` returns the original response.
- **An expired lease means the outcome is unknown.** If a lease expires without a result, the instruction becomes `expired`. The message stays `sending`: it is never reported `sent`, and it is never retried automatically, because the SMS may already have gone out. A late outcome event is still accepted and completes the instruction. The dispatcher creates a **new** instruction (`attempt + 1`, new key) only after a definitive `send_failed` with `retryable: true`.
- **The deadline is enforced on the device.** The claim result carries `server_time`. The device must not start a transport attempt after `local_now + (lease_expires_at - server_time)` minus a margin. If it cannot start in time, it reports `send_failed` with code `timeout` and `retryable: true`, and does not send.
- **Listing is read-only.** `GET /v1/connections/{id}/outbox` (`listOutboxInstructions`) returns `OutboxInstructionSummary` rows, which carry no text and no destination. It is for reconciliation and never leases anything.

### Why pull, not push
- **No new infrastructure, and it works offline.** A push channel needs FCM, which brings Google Play services, a server key and token registration, or a long-lived socket. Polling uses the authenticated `/v1` client the app already has (WING-012). A phone that is offline simply claims nothing and loses nothing. FCM can be added later as a *wake-up hint* that triggers a claim; it would never carry the instruction itself.
- **The device states when it is ready.** The send must happen in a process that holds the SMS role, has radio service, and holds a current key ledger. A claim happens only at that point, so a lease is never handed to a device that cannot use it.
- **Exactly one executor is decided on the server, at one instant.** With push, the server cannot know whether a message reached one device, two devices or none. A claim is a single database transition, so "two devices both sent it" is ruled out by construction rather than by client discipline.
- **Unknown outcomes stay unknown.** A lease that lapses is recorded as `expired`, not as failed and not as sent. Retrying it automatically could double-send.

### Defense in depth against a double send
1. Server: one lease per instruction, and one instruction per idempotency key.
2. Device: one transport attempt per key, recorded in the durable ledger before the radio is called (D-006, WING-016 `SmsSender`).
3. Server: send outcomes must match a leased instruction, and repeats are deduplicated.

## Consequences
- **WING-011** needs:
  - an `outbox_instructions` table: user-scoped; `connection_id`, `outbound_message_id`, `attempt`, a unique `idempotency_key`, `state`, `device_install_id`, `leased_at`, `lease_expires_at`, `created_at`, plus a unique `(outbound_message_id, attempt)`
  - an `event_ingest` record keyed by `(user_id, event_id)`
  - a job that moves lapsed leases to `expired`

  For device-side connections, the dispatcher creates an instruction instead of calling `Connector.send`. Backend-side connectors, such as `MockConnector`, keep the direct call.
- **WING-016** replaces `NoIngestRouteUploader` with an HTTP uploader that sends `event_id` as the key and maps responses as described above. It adds a poller that claims instructions and turns each into an `SmsSendCommand`, with `authorizedBy` taken from `authorization` and the deadline check applied.
- **Possible double-processing on a failed upload.** If the device sends but its outcome upload keeps failing past the lease, the message stays `sending` and the instruction `expired`. The user sees an unresolved send rather than a duplicate. The durable envelope outbox on the device eventually delivers the outcome.
- **Device clocks are untrusted.** Each device deadline is computed relative to the server's clock, and the server records the lease times.
- **A device reports its own capabilities.** A device can report capability changes, but capability alone never authorizes a send. ADR 0005 policy still runs at dispatch and at claim time.
