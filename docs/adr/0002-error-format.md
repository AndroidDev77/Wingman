# 0002: Error format

- Status: Accepted (WING-002, 2026-11-01)

## Context
Android needs to branch reliably on error kinds (e.g. a duplicate submission, a pending approval that has already been decided, an unsupported capability) without parsing prose, and errors must not leak whether another user's resource exists.

## Decision
- Every non-2xx response has the content type `application/problem+json` and follows **RFC 9457** (`type`, `title`, `status`, `detail`, `instance`). Wingman adds three extensions: **`code`** (required, from the closed `ErrorCode` enum), `request_id`, and `errors[]` (field-level details using JSON Pointer).
- `type` is `https://wingman.invalid/problems/<code>`.
- Clients branch on `code`. `title` and `detail` are human-readable text and may change. When a client sees an unknown `code`, it falls back to the HTTP status.
- A resource owned by another user returns **`404 not_found`**, never `403`.
- Unknown request fields, unknown enum values and malformed configuration return `422 validation_error`.

## Consequences
- WING-004 and the other API tickets install one exception handler that maps domain errors to `Problem`. FastAPI's default `{"detail": ...}` 422 body must be replaced.
- Adding a new `code` is an additive contract change.
- `contracts/validate.sh` enforces problem+json on every declared error response.
