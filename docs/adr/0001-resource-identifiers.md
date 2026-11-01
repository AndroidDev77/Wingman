# 0001: Resource identifiers and timestamps

- Status: Accepted (WING-002, 2026-11-01)

## Context
Backend, Android and connectors all exchange IDs. Clients must not depend on ID structure. IDs must be safe to generate on any node, must not leak counts, and should index well in Postgres. Platform message IDs (SMS row IDs, mock IDs) have arbitrary formats.

## Decision
- Every Wingman resource ID is a **lowercase canonical UUID string**, validated by the pattern `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`. The server generates **UUIDv7**, which is time-ordered and gives good B-tree locality.
- Clients treat IDs as **opaque**: they compare them for equality only, and never parse them or sort by them.
- Platform-assigned identifiers are separate `external_*` string fields (1 to 256 chars, no format), and are never used as Wingman IDs.
- Timestamps are **UTC RFC 3339 with a literal `Z`**, with up to 6 fractional digits. Offsets such as `+00:00` are rejected. This matches the existing `GET /health` output, for example `2026-11-01T03:46:14.241376Z`.

## Consequences
- WING-003 uses `uuid` columns. If the Python version in use lacks `uuid.uuid7()`, WING-003 must pick a generator. Contract validation only requires a canonical UUID, so v4 values are accepted too.
- Clients must not infer creation time from an ID. They use `created_at`.
- The backend serializer must emit `Z`, not `+00:00`. Pydantic does this by default for UTC datetimes.
