"""Apple/CoreData timestamps in chat.db.

``message.date`` (and ``date_read``, ``date_delivered``, ``date_edited``) count from
2001-01-01T00:00:00Z. Since macOS 10.13 / iOS 11 the unit is nanoseconds; older
databases store seconds (community-documented, see docs/integrations/imessage.md
sources). A seconds value for any date before ~5000 AD is below 1e11, while a
nanoseconds value after 2001-01-02 is above 8.6e13, so 1e11 separates them safely.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

APPLE_EPOCH = datetime(2001, 1, 1, tzinfo=UTC)
UNIX_TO_APPLE_OFFSET_S = 978_307_200
_NS_THRESHOLD = 100_000_000_000  # 1e11


def apple_to_datetime(value: int | float | None) -> datetime | None:
    """Convert a chat.db date column to an aware UTC datetime. 0/None means "unset"."""
    if value is None or value == 0:
        return None
    raw = int(value)
    if abs(raw) >= _NS_THRESHOLD:
        seconds, nanos = divmod(raw, 1_000_000_000)
        return APPLE_EPOCH + timedelta(seconds=seconds, microseconds=nanos // 1000)
    return APPLE_EPOCH + timedelta(seconds=raw)


def datetime_to_apple_ns(value: datetime) -> int:
    """Inverse of ``apple_to_datetime`` for nanosecond databases (used by fixtures)."""
    if value.tzinfo is None:
        raise ValueError("naive datetime")
    delta = value.astimezone(UTC) - APPLE_EPOCH
    return (delta.days * 86_400 + delta.seconds) * 1_000_000_000 + delta.microseconds * 1000


def to_contract_timestamp(value: datetime) -> str:
    """UTC RFC 3339 with a literal Z and at most 6 fractional digits (contract pattern)."""
    value = value.astimezone(UTC)
    if value.microsecond:
        return value.strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    return value.strftime("%Y-%m-%dT%H:%M:%SZ")
