"""Opaque keyset cursors (ADR 0003).

A cursor is base64url JSON ``{"k": <listing>, "s": <sort value>, "i": <id>}``. The
listing tag stops a cursor from one listing (e.g. messages) being replayed against
another (e.g. inbox). Clients never parse cursors; anything malformed raises
:class:`InvalidCursorError`.
"""

import base64
import binascii
import json
import uuid
from dataclasses import dataclass
from datetime import datetime

from app.db.errors import InvalidCursorError

MAX_CURSOR_LEN = 512
DEFAULT_LIMIT = 50
MAX_LIMIT = 100

SortValue = datetime | int


@dataclass(frozen=True, slots=True)
class Page[T]:
    items: list[T]
    next_cursor: str | None


@dataclass(frozen=True, slots=True)
class CursorPosition:
    sort_value: SortValue
    id: uuid.UUID


def clamp_limit(limit: int | None) -> int:
    if limit is None:
        return DEFAULT_LIMIT
    if not 1 <= limit <= MAX_LIMIT:
        raise ValueError(f"limit must be between 1 and {MAX_LIMIT}")
    return limit


def encode_cursor(listing: str, position: CursorPosition) -> str:
    value = position.sort_value
    sort: dict[str, str | int] = (
        {"t": value.isoformat()} if isinstance(value, datetime) else {"n": value}
    )
    raw = json.dumps({"k": listing, "s": sort, "i": str(position.id)}, separators=(",", ":"))
    return base64.urlsafe_b64encode(raw.encode()).decode().rstrip("=")


def decode_cursor(listing: str, cursor: str) -> CursorPosition:
    if not cursor or len(cursor) > MAX_CURSOR_LEN:
        raise InvalidCursorError("cursor length out of range")
    try:
        padded = cursor + "=" * (-len(cursor) % 4)
        data = json.loads(base64.urlsafe_b64decode(padded.encode()))
        if not isinstance(data, dict) or data.get("k") != listing:
            raise InvalidCursorError("cursor does not belong to this listing")
        sort = data["s"]
        if not isinstance(sort, dict) or len(sort) != 1:
            raise InvalidCursorError("malformed cursor")
        sort_value: SortValue
        if "t" in sort and isinstance(sort["t"], str):
            sort_value = datetime.fromisoformat(sort["t"])
            if sort_value.tzinfo is None:
                raise InvalidCursorError("malformed cursor")
        elif "n" in sort and type(sort["n"]) is int:
            sort_value = sort["n"]
        else:
            raise InvalidCursorError("malformed cursor")
        return CursorPosition(sort_value=sort_value, id=uuid.UUID(data["i"]))
    except InvalidCursorError:
        raise
    except (binascii.Error, UnicodeDecodeError, ValueError, KeyError, TypeError) as exc:
        raise InvalidCursorError("malformed cursor") from exc
