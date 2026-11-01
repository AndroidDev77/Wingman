"""Shared field types that mirror the primitives in `contracts/schemas/*.v1.json`.

Kept private to the connectors package: other modules import the public names
re-exported from `app.connectors`.
"""

from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta
from typing import Annotated, Any, ClassVar
from uuid import UUID

from pydantic import (
    AfterValidator,
    BaseModel,
    BeforeValidator,
    ConfigDict,
    SerializerFunctionWrapHandler,
    StringConstraints,
    model_serializer,
)

_TIMESTAMP_RE = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?Z$"
)
_ID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def _check_timestamp(value: object) -> object:
    if isinstance(value, str):
        if not _TIMESTAMP_RE.match(value):
            raise ValueError("timestamp must be UTC RFC 3339 with a literal 'Z' (ADR 0001)")
        return value
    if isinstance(value, datetime):
        if value.tzinfo is None or value.utcoffset() != timedelta(0):
            raise ValueError("timestamp must be timezone-aware UTC (ADR 0001)")
        return value
    raise ValueError("timestamp must be an RFC 3339 string or a datetime")


def _to_utc(value: datetime) -> datetime:
    return value.astimezone(UTC)


def _check_id(value: object) -> object:
    if isinstance(value, str) and not _ID_RE.match(value):
        raise ValueError("id must be a lowercase canonical UUID string (ADR 0001)")
    return value


UtcTimestamp = Annotated[datetime, BeforeValidator(_check_timestamp), AfterValidator(_to_utc)]
"""UTC timestamp. Parses only `...Z` strings; always serializes with `Z`."""

WingmanId = Annotated[UUID, BeforeValidator(_check_id)]
"""Wingman resource ID: lowercase canonical UUID."""

ExternalId = Annotated[str, StringConstraints(min_length=1, max_length=256)]
"""Platform-assigned identifier: opaque, 1 to 256 chars."""

PlatformKey = Annotated[str, StringConstraints(pattern=r"^[a-z][a-z0-9_]{1,31}$")]
"""Lowercase snake_case platform key, e.g. `mock`, `sms`."""

IdempotencyKey = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9._:-]{8,128}$")]
"""Send idempotency key (ADR 0004)."""


class ContractModel(BaseModel):
    """Base for models that mirror a contract schema.

    `extra="forbid"` mirrors `additionalProperties: false`. Fields listed in
    `_omit_if_none` are optional-but-not-nullable in the schema, so they are dropped
    from the serialized form when unset instead of being written as `null`.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    _omit_if_none: ClassVar[frozenset[str]] = frozenset()

    @model_serializer(mode="wrap")
    def _serialize(self, handler: SerializerFunctionWrapHandler) -> Any:
        data = handler(self)
        if isinstance(data, dict):
            for name in self._omit_if_none:
                if name in data and data[name] is None:
                    del data[name]
        return data

    def to_contract_dict(self) -> dict[str, Any]:
        """JSON-compatible dict that validates against the contract schema."""
        return self.model_dump(mode="json")
