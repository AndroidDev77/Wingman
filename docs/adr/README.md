# Architecture Decision Records

Short records of decisions that shape the shared contracts. To supersede one, add a new ADR and mark the old one `Superseded by NNNN`. Do not rewrite history.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-resource-identifiers.md) | Resource IDs are opaque lowercase UUID strings (UUIDv7), and timestamps are UTC `Z` | Accepted |
| [0002](0002-error-format.md) | Errors use RFC 9457 problem+json with a stable `code` | Accepted |
| [0003](0003-cursor-pagination.md) | Pagination is cursor-based (`cursor`, `limit`, `next_cursor`) | Accepted |
| [0004](0004-idempotency.md) | Every POST requires an `Idempotency-Key` with replay semantics | Accepted |
| [0005](0005-connector-capability-model.md) | Connector capabilities are independent and verification-labeled | Accepted |
| [0006](0006-device-outbox-leases.md) | Device connectors ingest events by POST and pull single-use leased send instructions from an outbox | Accepted |
| [0007](0007-connection-pairing-and-capability-trust.md) | The app pairs connectors over the API; client-claimed capabilities are never `VERIFIED`; unpair cancels pending automated work | Accepted |
