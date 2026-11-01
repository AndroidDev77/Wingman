"""Declarative base, shared column types and mixins.

Conventions (all tables):

* ``id``: ``uuid`` primary key, UUIDv7 generated server-side by :func:`app.db.ids.uuid7`.
* ``created_at`` / ``updated_at``: ``timestamptz`` in UTC. Naive datetimes are rejected.
* User-owned tables carry a non-null ``user_id`` FK (``ON DELETE CASCADE``) and a
  ``UNIQUE (user_id, id)`` key so children reference them with a composite FK
  ``(user_id, parent_id)``. A cross-tenant reference is therefore impossible at the
  database level, not only in repository code.
* Every index on a user-owned table starts with ``user_id``; keyset indexes end in ``id``
  (ADR 0003). ``tests/db/test_schema_conventions.py`` enforces this.
"""

import uuid
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import (
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    MetaData,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects import postgresql
from sqlalchemy.engine import Dialect
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.types import TypeDecorator

from app.db.enums import CONTRACT_ENUMS
from app.db.ids import uuid7

NAMING_CONVENTION = {
    "ix": "ix_%(table_name)s_%(column_0_N_name)s",
    "uq": "uq_%(table_name)s_%(column_0_N_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_N_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}

PLATFORM_PATTERN = "^[a-z][a-z0-9_]{1,31}$"


def utc_now() -> datetime:
    return datetime.now(UTC)


class UTCDateTime(TypeDecorator[datetime]):
    """``timestamptz`` that only accepts timezone-aware values and always returns UTC."""

    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect: Dialect) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("naive datetime given; Wingman stores only timezone-aware UTC")
        return value.astimezone(UTC)

    def process_result_value(self, value: datetime | None, dialect: Dialect) -> datetime | None:
        if value is None:
            return None
        return value.astimezone(UTC)


# One shared SQLAlchemy type per Postgres enum type. ``create_type=False``: the
# Alembic migration owns their lifecycle (created in upgrade, dropped in downgrade).
PG_ENUMS: dict[str, postgresql.ENUM] = {
    ce.pg_name: postgresql.ENUM(
        ce.enum,
        name=ce.pg_name,
        values_callable=lambda e: [m.value for m in e],
        create_type=False,
        validate_strings=True,
    )
    for ce in CONTRACT_ENUMS
}


def pg_enum(name: str) -> postgresql.ENUM:
    return PG_ENUMS[name]


def tenant_fk(
    column: str,
    parent_table: str,
    *,
    ondelete: str = "CASCADE",
    name: str | None = None,
) -> ForeignKeyConstraint:
    """Composite FK ``(user_id, column) -> parent (user_id, id)``.

    Guarantees the referenced row belongs to the same user. With ``ondelete="SET NULL"``
    only ``column`` is nulled (Postgres 15+ column list), never ``user_id``.
    """
    action = f"SET NULL ({column})" if ondelete == "SET NULL" else ondelete
    return ForeignKeyConstraint(
        ["user_id", column],
        [f"{parent_table}.user_id", f"{parent_table}.id"],
        ondelete=action,
        name=name,
    )


def tenant_key() -> UniqueConstraint:
    """``UNIQUE (user_id, id)``: the target of child tables' composite tenant FKs."""
    return UniqueConstraint("user_id", "id")


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)
    type_annotation_map: dict[Any, Any] = {
        datetime: UTCDateTime(),
        uuid.UUID: postgresql.UUID(as_uuid=True),
    }


class IdMixin:
    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid7, sort_order=-20)


class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(
        nullable=False, default=utc_now, server_default=func.now(), sort_order=100
    )
    updated_at: Mapped[datetime] = mapped_column(
        nullable=False,
        default=utc_now,
        onupdate=utc_now,
        server_default=func.now(),
        sort_order=101,
    )


class UserOwnedMixin(IdMixin, TimestampMixin):
    """Tenant-scoped row. Subclasses must include :func:`tenant_key` in ``__table_args__``."""

    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=False, sort_order=-10
    )
