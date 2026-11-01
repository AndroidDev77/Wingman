"""Memory endpoints (WING-008), exactly as in ``contracts/openapi.yaml`` v1.1.0 (D-012).

Mounted in ``app/main.py``::

    app.include_router(create_memories_router(get_memory_service))

Routes (all user-scoped through ``get_current_user_scope``; another user's contact or
memory is ``404 not_found``, never ``403``):

* ``GET    /v1/contacts/{contact_id}/memories``  ``listContactMemories``: keyset pages
  ordered ``(created_at desc, id desc)``; ``cursor``/``limit`` per ADR 0003 (limit
  1..100, default 50; ``next_cursor`` null at the end). The cursor carries the contact
  and ``include_history``; replaying it with a different ``include_history`` (or
  another contact) is an invalid cursor, ``422 validation_error``.
* ``PUT    /v1/memories/{memory_id}``  ``correctMemory``: a ``user_correction`` that
  supersedes the fact. Repeating the same correction returns the existing one.
* ``DELETE /v1/memories/{memory_id}``  ``deleteMemory``: soft delete, ``204``,
  idempotent.

Errors are raised as :class:`app.api.errors.ProblemException` (or are FastAPI request
validation errors) and rendered by the shared WING-004 handlers that
``register_error_handlers`` installs on the app. Memory content is private: it is never
logged and never echoed in an error.
"""

import uuid
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Query, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_serializer
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.memory import FactBasis, FactOrigin, MemoryFact, MemoryService, MemoryStateError
from app.ai.memory.schemas import MAX_FACT_CHARS
from app.api.errors import ErrorCode, ProblemException
from app.db.errors import InvalidCursorError, NotFoundError
from app.db.pagination import DEFAULT_LIMIT, MAX_CURSOR_LEN, MAX_LIMIT
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope

CONTRACT_STATUS = "v1.1.0"


def _ts(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


class MemoryOut(BaseModel):
    """Contract ``Memory``."""

    model_config = ConfigDict(extra="forbid")

    id: uuid.UUID
    contact_id: uuid.UUID
    conversation_id: uuid.UUID | None
    kind: str
    key: str | None
    category: str | None
    content: str
    basis: FactBasis
    origin: FactOrigin
    confidence: float
    sensitive: bool
    source_message_ids: list[uuid.UUID]
    stated_at: datetime | None
    expires_at: datetime | None
    superseded_by_id: uuid.UUID | None
    created_at: datetime

    @field_serializer("stated_at", "expires_at", "created_at")
    def _z(self, value: datetime | None) -> str | None:
        return _ts(value)

    @classmethod
    def from_fact(cls, fact: MemoryFact) -> "MemoryOut":
        return cls(
            id=fact.id,
            contact_id=fact.contact_id,
            conversation_id=fact.conversation_id,
            kind=fact.kind,
            key=fact.key,
            category=fact.category,
            content=fact.content,
            basis=fact.basis,
            origin=fact.origin,
            confidence=fact.confidence,
            sensitive=fact.sensitive,
            source_message_ids=list(fact.source_message_ids),
            stated_at=fact.stated_at,
            expires_at=fact.expires_at,
            superseded_by_id=fact.superseded_by_id,
            created_at=fact.created_at,
        )


class MemoryPageOut(BaseModel):
    """Contract ``MemoryPage``."""

    model_config = ConfigDict(extra="forbid")

    items: list[MemoryOut]
    next_cursor: str | None


class MemoryCorrection(BaseModel):
    """Contract ``MemoryCorrection``. Unknown fields are rejected."""

    model_config = ConfigDict(extra="forbid")

    content: str = Field(min_length=1, max_length=MAX_FACT_CHARS, strict=True)


def _not_found() -> ProblemException:
    return ProblemException(404, ErrorCode.NOT_FOUND, "Resource not found")


def _invalid_cursor() -> ProblemException:
    return ProblemException(
        422,
        ErrorCode.VALIDATION_ERROR,
        "Invalid pagination cursor",
        errors=[{"pointer": "query:cursor", "message": "invalid or expired cursor"}],
    )


def create_memories_router(
    get_memory_service: Callable[..., Any],
) -> APIRouter:
    """Build the memory router.

    ``get_memory_service`` is a FastAPI dependency returning a ``MemoryService``;
    ``app.main`` supplies one built once per app from ``build_gateway()``. Tests
    override it.
    """
    router = APIRouter(tags=["memories"])
    Scope = Annotated[UserScope, Depends(get_current_user_scope)]
    Session = Annotated[AsyncSession, Depends(get_session, scope="function")]
    Service = Annotated[MemoryService, Depends(get_memory_service)]

    @router.get(
        "/v1/contacts/{contact_id}/memories",
        operation_id="listContactMemories",
        response_model=None,
    )
    async def list_contact_memories(
        contact_id: uuid.UUID,
        scope: Scope,
        session: Session,
        service: Service,
        include_history: Annotated[bool, Query()] = False,
        cursor: Annotated[str | None, Query(min_length=1, max_length=MAX_CURSOR_LEN)] = None,
        limit: Annotated[int, Query(ge=1, le=MAX_LIMIT)] = DEFAULT_LIMIT,
    ) -> Any:
        try:
            page = await service.list_page(
                session,
                scope,
                contact_id,
                include_history=include_history,
                cursor=cursor,
                limit=limit,
            )
        except NotFoundError:
            raise _not_found() from None
        except InvalidCursorError:
            raise _invalid_cursor() from None
        body = MemoryPageOut(
            items=[MemoryOut.from_fact(f) for f in page.items], next_cursor=page.next_cursor
        )
        return JSONResponse(body.model_dump(mode="json"))

    @router.put("/v1/memories/{memory_id}", operation_id="correctMemory", response_model=None)
    async def correct_memory(
        memory_id: uuid.UUID,
        body: MemoryCorrection,
        scope: Scope,
        session: Session,
        service: Service,
    ) -> Any:
        try:
            fact = await service.correct_fact(session, scope, memory_id, content=body.content)
        except NotFoundError:
            raise _not_found() from None
        except MemoryStateError as exc:
            # Blank-after-trim content, or a summary (which cannot be corrected).
            pointer = "/content" if "empty" in str(exc) else ""
            raise ProblemException(
                422,
                ErrorCode.VALIDATION_ERROR,
                "This memory cannot be corrected with that content",
                errors=[{"pointer": pointer, "message": str(exc)}],
            ) from None
        return JSONResponse(MemoryOut.from_fact(fact).model_dump(mode="json"))

    @router.delete(
        "/v1/memories/{memory_id}",
        operation_id="deleteMemory",
        status_code=204,
        response_model=None,
    )
    async def delete_memory(
        memory_id: uuid.UUID,
        scope: Scope,
        session: Session,
        service: Service,
    ) -> Response:
        try:
            await service.delete_fact(session, scope, memory_id)
        except NotFoundError:
            raise _not_found() from None
        return Response(status_code=204)

    return router
