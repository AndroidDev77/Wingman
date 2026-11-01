"""Scoped repository base.

Every repository for a user-owned table subclasses :class:`ScopedRepository`, which
binds a :class:`UserScope` at construction. All reads go through :meth:`_select`, which
always adds ``WHERE user_id = :scope``; all writes stamp ``user_id`` from the scope and
reject a caller-supplied one. A row owned by another user behaves exactly like a missing
row (``None`` / :class:`NotFoundError`), matching the API's ``404`` rule.

Repositories ``flush`` but never ``commit``: the caller owns the transaction
(:func:`app.db.session.get_session` / :func:`session_scope`).
"""

import uuid
from collections.abc import Sequence
from typing import Any, ClassVar

from sqlalchemy import Select, select, tuple_, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import InstrumentedAttribute

from app.db.base import UserOwnedMixin
from app.db.errors import NotFoundError
from app.db.pagination import (
    CursorPosition,
    Page,
    SortValue,
    clamp_limit,
    decode_cursor,
    encode_cursor,
)
from app.db.scope import UserScope

_PROTECTED_FIELDS = frozenset({"id", "user_id", "created_at"})


class ScopedRepository[ModelT: UserOwnedMixin]:
    model: ClassVar[type[Any]]

    def __init__(self, session: AsyncSession, scope: UserScope) -> None:
        if not isinstance(scope, UserScope):
            raise TypeError("repositories require a UserScope")
        self.session = session
        self.scope = scope

    # ------------------------------------------------------------------ reads
    def _select(self) -> Select[ModelT]:
        return select(self.model).where(self.model.user_id == self.scope.user_id)

    async def get(self, id_: uuid.UUID) -> ModelT | None:
        """The row with this id in this user's scope, else ``None``."""
        row: ModelT | None = await self.session.scalar(self._select().where(self.model.id == id_))
        return row

    async def get_fresh(self, id_: uuid.UUID) -> ModelT | None:
        """Like :meth:`get`, but re-reads the row even if the session already holds it
        (for state another transaction may have committed meanwhile)."""
        row: ModelT | None = await self.session.scalar(
            self._select().where(self.model.id == id_).execution_options(populate_existing=True)
        )
        return row

    async def require(self, id_: uuid.UUID) -> ModelT:
        """Like :meth:`get` but raises :class:`NotFoundError`."""
        row = await self.get(id_)
        if row is None:
            raise NotFoundError(f"{self.model.__tablename__} {id_} not found")
        return row

    # ------------------------------------------------------------------ writes
    def _new(self, **values: Any) -> ModelT:
        if "user_id" in values:
            raise ValueError("user_id comes from the repository scope")
        row: ModelT = self.model(user_id=self.scope.user_id, **values)
        self.session.add(row)
        return row

    async def _add(self, **values: Any) -> ModelT:
        row = self._new(**values)
        await self.session.flush()
        return row

    async def _update(self, id_: uuid.UUID, **values: Any) -> ModelT:
        """Scoped partial update. Raises :class:`NotFoundError` outside the scope."""
        bad = _PROTECTED_FIELDS.intersection(values)
        if bad:
            raise ValueError(f"cannot update {sorted(bad)}")
        stmt = (
            update(self.model)
            .where(self.model.user_id == self.scope.user_id, self.model.id == id_)
            .values(**values)
            .returning(self.model)
            .execution_options(synchronize_session="fetch")
        )
        row: ModelT | None = await self.session.scalar(stmt)
        if row is None:
            raise NotFoundError(f"{self.model.__tablename__} {id_} not found")
        return row

    # ------------------------------------------------------------------ keyset pages
    async def _page(
        self,
        stmt: Select[ModelT],
        *,
        listing: str,
        sort_column: InstrumentedAttribute[Any],
        descending: bool,
        cursor: str | None,
        limit: int | None,
    ) -> Page[ModelT]:
        """Keyset page ordered by ``(sort_column, id)`` (ADR 0003)."""
        size = clamp_limit(limit)
        key = tuple_(sort_column, self.model.id)
        if cursor is not None:
            pos = decode_cursor(listing, cursor)
            bound = tuple_(pos.sort_value, pos.id)
            stmt = stmt.where(key < bound if descending else key > bound)
        if descending:
            stmt = stmt.order_by(sort_column.desc(), self.model.id.desc())
        else:
            stmt = stmt.order_by(sort_column.asc(), self.model.id.asc())
        rows: Sequence[ModelT] = (await self.session.scalars(stmt.limit(size + 1))).all()
        items = list(rows[:size])
        next_cursor = None
        if len(rows) > size:
            last = items[-1]
            sort_value: SortValue = getattr(last, sort_column.key)
            next_cursor = encode_cursor(listing, CursorPosition(sort_value, last.id))
        return Page(items=items, next_cursor=next_cursor)
