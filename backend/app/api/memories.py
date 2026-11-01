"""Memory endpoints (WING-008). **PENDING CONTRACT PR: NOT MOUNTED.**

Contract v1.0.0 deliberately has no memory endpoints (D-004: deferred to an additive
contract PR). This router implements the routes proposed in the WING-008 PR handoff so
the integrator can mount it once that contract PR merges::

    from app.api.memories import create_memories_router
    app.include_router(create_memories_router(get_memory_service))

Nothing in ``app/main.py`` mounts it today, and it must stay unmounted until the
contract lands (``tests/ai/memory/test_api.py`` asserts the default app has no
``/v1/.../memories`` routes).

Routes (all user-scoped through ``get_current_user_scope``; another user's resource is
``404 not_found``):

* ``GET    /v1/contacts/{contact_id}/memories``          list active facts (or with history)
* ``PUT    /v1/memories/{memory_id}``                    user correction (supersedes)
* ``DELETE /v1/memories/{memory_id}``                    soft delete, never retrieved again

``PUT``/``DELETE`` are naturally idempotent (ADR 0004): repeating a correction with the
same content returns the same correction; deleting twice is a no-op ``204``.

Errors are ``application/problem+json``. Until WING-004's shared handler lands (I-004)
this router builds the problem bodies itself.
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
from app.db.errors import NotFoundError
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope

CONTRACT_STATUS = "pending-contract-pr"


def _ts(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


class MemoryOut(BaseModel):
    """Proposed ``Memory`` contract schema."""

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


class MemoryListOut(BaseModel):
    model_config = ConfigDict(extra="forbid")

    items: list[MemoryOut]
    next_cursor: str | None = None


class MemoryCorrection(BaseModel):
    """Proposed ``MemoryCorrection`` request body. Unknown fields are rejected."""

    model_config = ConfigDict(extra="forbid")

    content: str = Field(min_length=1, max_length=MAX_FACT_CHARS)


def _problem(status: int, code: str, title: str) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        media_type="application/problem+json",
        content={
            "type": f"https://wingman.invalid/problems/{code}",
            "title": title,
            "status": status,
            "code": code,
        },
    )


def create_memories_router(
    get_memory_service: Callable[..., Any],
) -> APIRouter:
    """Build the (unmounted) memory router.

    ``get_memory_service`` is a FastAPI dependency returning a ``MemoryService``; the
    integrator supplies one built from ``build_gateway()``. Tests override it.
    """
    router = APIRouter(tags=["memories"])
    Scope = Annotated[UserScope, Depends(get_current_user_scope)]
    Session = Annotated[AsyncSession, Depends(get_session)]
    Service = Annotated[MemoryService, Depends(get_memory_service)]

    @router.get("/v1/contacts/{contact_id}/memories", response_model=None)
    async def list_contact_memories(
        contact_id: uuid.UUID,
        scope: Scope,
        session: Session,
        service: Service,
        include_history: Annotated[bool, Query()] = False,
    ) -> Any:
        try:
            facts = await service.list_facts(
                session, scope, contact_id, include_history=include_history
            )
        except NotFoundError:
            return _problem(404, "not_found", "Not found")
        body = MemoryListOut(items=[MemoryOut.from_fact(f) for f in facts])
        return JSONResponse(body.model_dump(mode="json"))

    @router.put("/v1/memories/{memory_id}", response_model=None)
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
            return _problem(404, "not_found", "Not found")
        except MemoryStateError:
            return _problem(422, "validation_error", "This memory cannot be corrected")
        return JSONResponse(MemoryOut.from_fact(fact).model_dump(mode="json"))

    @router.delete("/v1/memories/{memory_id}", status_code=204, response_model=None)
    async def delete_memory(
        memory_id: uuid.UUID,
        scope: Scope,
        session: Session,
        service: Service,
    ) -> Any:
        try:
            await service.delete_fact(session, scope, memory_id)
        except NotFoundError:
            return _problem(404, "not_found", "Not found")
        return Response(status_code=204)

    return router
