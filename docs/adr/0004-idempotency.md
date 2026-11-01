# 0004: Idempotency keys

- Status: Accepted (WING-002, 2026-11-01)

## Context
Mobile networks retry, users double-tap, and connectors re-deliver events. A duplicate send to a dating contact causes real harm. Gate G4 requires an outbound path that is idempotent and approval-aware.

## Decision
- **Every `POST`** in `/v1` requires the `Idempotency-Key` header (8 to 128 chars of `[A-Za-z0-9._:-]`, with a fresh UUIDv4 recommended). This covers sends, approve and reject, draft generation, pause, resume and takeover. `PUT` and `DELETE` are naturally idempotent and cannot cause a send, so they don't take a key. Preferences use `If-Match` for concurrency instead.
- Keys are scoped to `(user, method, route template)` and kept for at least **24 hours**.
- Replay semantics:
  - Same key with the same body replays the stored status and body, with `Idempotent-Replayed: true`. The side effect is not repeated.
  - Same key with a different body returns `422 idempotency_key_mismatch`.
  - Same key while the first request is still being processed returns `409 idempotency_key_in_progress` with `Retry-After`.
  - A missing key returns `400 idempotency_key_required`.
  - Only completed responses are stored, both 2xx and 4xx. A 5xx is not stored, so the client may retry with the same key.
- **Internally**, the dispatcher derives a send key per outbound message, for example `send:<message_id>`. Connectors perform at most one transport send per key and echo the key in `send_acknowledged` and `send_failed` events.
- **Inbound** deduplication uses `(user_id, platform, external_message_id)` rather than idempotency keys.

## Consequences
- A shared idempotency store is needed: a Postgres table (WING-003) with a unique key. Redis alone is not durable enough for sends.
- Android generates one key per user action and reuses it across retries of that action (WING-015: rapid repeated clicks).
- `contracts/validate.sh` fails if any POST lacks a required `Idempotency-Key`.
