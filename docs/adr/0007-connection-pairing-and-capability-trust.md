# 0007: Connection pairing and capability trust

- Status: Accepted (contract v1.2.0, 2026-11-01). Resolves I-017 (with the `paused` approval reason and the `lease_expired` audit type). Refines ADR 0005 for client-submitted capabilities.

## Context
Up to contract v1.1.0 there was no API for creating a platform connection. Dev setups and smoke tests seeded `platform_connections` directly (D-015). The Android SMS connector (WING-016) needs a real connection ID before it can ingest events or claim instructions (ADR 0006).

The capability document is the risky part. ADR 0005 makes autopilot depend on `send: VERIFIED`, and `VERIFIED` means "reproduced on a real device, with evidence recorded". If a client could pair with `send: VERIFIED`, any app build, modified client or bug could switch on unattended sending to a dating contact. A capability the client asserts is a claim, not evidence.

## Decision

### Pairing: `POST /v1/connections` (`createConnection`)
- The authenticated app pairs a connector to **the caller's** account. The server records `platform`, `display_name`, `paired_at` and, for device-side platforms (`sms`, `android_notification`), the `device_install_id` binding. This is the same per-install UUID the app already sends to `claimOutboxInstructions`.
- One install has at most one **paired** connection per platform: a partial unique index on `(user_id, platform, device_install_id) WHERE device_install_id IS NOT NULL AND unpaired_at IS NULL`. Pairing again with a new `Idempotency-Key` returns the existing connection with `200`; a replay of the same key returns the original `201`. After unpairing, pairing creates a new connection.
- **`mock`** (the backend-side WING-006 simulator) is pairable only when `WINGMAN_ENV != prod`. In `prod` it is `422 validation_error` at `/platform`. It takes no `device_install_id` and no client `capabilities`: the server stores the simulator's own document. That document is `VERIFIED` against the deterministic simulator, as ADR 0005 allows, and its notes say it is simulated.
- Other platforms (`tinder`, `bumble`, `hinge`, `imessage`) are not pairable in 1.2.0. The request schema enumerates the pairable ones.
- Audit `connection_paired` carries IDs and codes only, including which capabilities the client *claimed* as `VERIFIED`.

### Capability trust: the server never accepts `VERIFIED` from a client
`app.services.connections.trust_claimed_capabilities` is applied to **every** client-submitted capability document, both at pairing and for later `connection.capabilities_changed` events:

| Client claims | Stored |
|---|---|
| `UNSUPPORTED` | `UNSUPPORTED` (a device may always lower a capability) |
| `UNVERIFIED` | `UNVERIFIED`, with any `evidence`/`verified_at` dropped |
| `VERIFIED` | `UNVERIFIED`, with a note saying it was claimed. **Exception:** if the server already holds `VERIFIED` for that capability *and* the same `connector_version`, the server's own entry (its evidence) is kept |

A client can therefore keep a server-recorded `VERIFIED`, or lower it, but never create or raise one. A new connector version starts unverified again, because the evidence was for the old build.

### How `VERIFIED` is ever set
Only through **server-side evidence**: `ConnectionService.record_verification` and its operator CLI, `python -m app.services.connections verify --user-id U --connection-id C --capability send --evidence REF`. It has **no HTTP route**. It needs a non-empty `evidence` reference to a reproduced device run recorded outside the client, such as the G5 device checklist entry in `docs/integrations/android_sms.md` (I-003) or a CI device-farm job ID. It sets `status`, `evidence` and `verified_at` for exactly one capability and logs the operator action with IDs only. The `mock` simulator document is the only other `VERIFIED` source, and it is never available in `prod`.

Consequence: a freshly paired SMS connection is never autopilot-eligible. Its drafts go to the approval queue (`capability_unverified`) until an operator records a reproduced device run. The claim-time re-check (ADR 0006) cancels any `autopilot` instruction whose connection is no longer `VERIFIED`.

### Unpairing: `POST /v1/connections/{id}/unpair` (`unpairConnection`)
Unpairing is effective before the response, in one transaction, with the global lock order (user -> approval -> instruction -> message):
- The connection becomes `disconnected`, with `unpaired_at` set and every capability `UNSUPPORTED`. A CHECK enforces that an unpaired connection is always disconnected. The dispatcher's and the claim's existing re-checks then cancel anything that still reaches them, and `sendMessage` is `422 capability_unsupported`.
- Its **unclaimed** (`pending`) outbox instructions are canceled, as are its `queued` outbound messages of **any** origin, because no transport remains for them. Pending approvals for its conversations are canceled too. Each is audited (`send_canceled` / `approval_canceled`, reason `connection_unavailable`), plus one `connection_unpaired` summary with counts.
- A message a device has already **leased** (`sending`) cannot be recalled. It is reported as `in_flight_message_count`, and its late outcome event is still accepted (results are the truth, ADR 0006). Every other event on an unpaired connection is `422` at `/connection_id`.
- Nothing is deleted. History stays readable. Unpairing twice is a no-op with zero counts.

`DELETE` was deliberately not added. Hard-deleting a connection would cascade to conversations and audit history, and the contract's `DELETE` operations take no `Idempotency-Key`.

### Also in v1.2.0 (I-017)
- `ApprovalReason.paused`: an approval caused by a global or contact pause now carries `paused` instead of D-015's interim `user_rule` (`details.checks.paused` stays). `paused` is added alongside any other reasons that apply.
- `AgentActionType.lease_expired`: written by lease expiry (actor `system`, reason `outcome_unknown`; details are only `instruction_id`, `connection_id`, `attempt`, `outcome: unknown`, `retried: false`). It is never `send_failed`, because nobody observed a failure. The message stays `sending` and is never retried automatically.

## Consequences
- The Android app pairs SMS once per install with `createConnection` and stores the returned `id` as the connection used for ingest and claims. Unpair is the user's "disconnect" action.
- `platform_connections` gains `device_install_id`, `paired_at`, `unpaired_at`, a partial unique index and one CHECK. These were amended into `0001` per D-010.
- Clients must tolerate the new enum values (`paused`, `lease_expired`, `connection_paired`, `connection_unpaired`), as the contract already requires for minor versions.
- **Not covered.** The claim route does not yet reject a `device_install_id` different from the bound one. The binding is recorded and returned, but enforcing it at claim time would change v1.1.0 claim semantics, so it is left for a later additive decision. Operator verification is a CLI, not an audited admin API. Both are follow-ups, not gaps in this contract.
