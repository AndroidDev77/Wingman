# 0003: Cursor pagination

- Status: Accepted (WING-002, 2026-11-01)

## Context
Inboxes, message threads, approvals and the audit log grow while users scroll them. Offset pagination skips or duplicates rows when new rows are inserted. WING-014 must handle threads of more than 100 messages.

## Decision
- List endpoints take `cursor` (an opaque string, at most 512 chars) and `limit` (1 to 100, default 50), and return `{ "items": [...], "next_cursor": <string|null> }`. A `null` `next_cursor` marks the end.
- Each endpoint documents a total order that ends with `id` as the tiebreak. Inbox, approvals and audit are newest first. Messages are chronological (oldest first), and `next_cursor` moves forward in time.
- The server encodes keyset position in the cursor (for example base64url JSON). Clients never construct or parse a cursor. An invalid cursor returns `422 validation_error`.
- No total counts are returned. Badges use dedicated fields such as `pending_approval_count` and `unread_count`.

## Consequences
- Keyset queries need indexes on the sort key plus `id` (WING-003).
- A cursor carries filters implicitly. Changing filters means starting again without a cursor.
