"""``POST /v1/conversations/{conversation_id}/drafts`` (WING-010), as in ``contracts/openapi.yaml``.

Asks the reply generator for a suggestion. It never sends, never queues a send and never
creates an approval: the result is a contract ``Draft`` that the user reads, edits and
sends manually (or that WING-011 evaluates). ``no_send`` and ``ask_user`` are normal
``201`` results.

``Idempotency-Key`` is required (ADR 0004): the same key and body replay the stored
``Draft`` with ``Idempotent-Replayed: true`` and no second model call; a different body
is ``422 idempotency_key_mismatch``; a key still in flight is ``409
idempotency_key_in_progress``; a missing key is ``400 idempotency_key_required``.
Another user's conversation is ``404 not_found``.

Mounting (integrator, in ``app/main.py``)::

    from app.api.drafts import router as drafts_router
    app.include_router(drafts_router)

Errors rely on the app-wide problem+json handlers (``register_error_handlers``).
``get_reply_generator`` builds the generator from settings; tests override it.
"""

import re
import uuid
from functools import lru_cache
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Header, Response, status
from pydantic import BaseModel, ConfigDict, Field, StrictStr
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.llm_gateway import build_gateway
from app.ai.reply_generator import ReplyGenerator
from app.api.errors import ErrorCode, ProblemException
from app.db.repositories import ConversationRepository
from app.db.repositories.idempotency import ClaimOutcome, IdempotencyRepository, fingerprint
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope

__all__ = ["DraftCreateRequest", "get_reply_generator", "router"]

ROUTE = "/v1/conversations/{conversation_id}/drafts"
_KEY_RE = re.compile(r"^[A-Za-z0-9._:-]{8,128}$")
IN_PROGRESS_RETRY_AFTER_S = 2

router = APIRouter(tags=["drafts"])


class DraftCreateRequest(BaseModel):
    """``DraftCreateRequest``: unknown fields are rejected."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    guidance: Annotated[StrictStr, Field(max_length=500)] | None = None


@lru_cache
def _default_generator() -> ReplyGenerator:
    return ReplyGenerator(build_gateway())


def get_reply_generator() -> ReplyGenerator:
    """FastAPI dependency. Built once from ``WINGMAN_LLM_*`` settings (mock by default)."""
    return _default_generator()


@router.post(
    ROUTE,
    status_code=status.HTTP_201_CREATED,
    operation_id="createDraft",
    summary="Ask the AI for a suggested reply (never sends)",
)
async def create_draft(
    conversation_id: uuid.UUID,
    response: Response,
    scope: Annotated[UserScope, Depends(get_current_user_scope)],
    session: Annotated[AsyncSession, Depends(get_session, scope="function")],
    generator: Annotated[ReplyGenerator, Depends(get_reply_generator)],
    body: DraftCreateRequest | None = None,
    idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
) -> dict[str, Any]:
    if idempotency_key is None:
        raise ProblemException(
            400, ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header is required"
        )
    if not _KEY_RE.fullmatch(idempotency_key):
        raise ProblemException(
            400,
            ErrorCode.VALIDATION_ERROR,
            "Idempotency-Key must match ^[A-Za-z0-9._:-]{8,128}$",
            errors=[{"pointer": "header:Idempotency-Key", "message": "invalid format"}],
        )
    request = body or DraftCreateRequest()
    # Ownership first: another user's conversation is a 404 before any key is stored.
    await ConversationRepository(session, scope).require(conversation_id)

    repo = IdempotencyRepository(session, scope)
    claim = await repo.claim(
        key=idempotency_key,
        method="POST",
        route=ROUTE,
        request_hash=fingerprint(
            {"conversation_id": str(conversation_id), "body": request.model_dump(mode="json")}
        ),
    )
    if claim.outcome is ClaimOutcome.MISMATCH:
        raise ProblemException(
            422,
            ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
            "This Idempotency-Key was used with a different request",
        )
    if claim.outcome is ClaimOutcome.IN_PROGRESS:
        raise ProblemException(
            409,
            ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS,
            "A request with this Idempotency-Key is still running",
            headers={"Retry-After": str(IN_PROGRESS_RETRY_AFTER_S)},
        )
    if claim.outcome is ClaimOutcome.REPLAY:
        response.headers["Idempotent-Replayed"] = "true"
        stored = claim.record.response_body
        if not isinstance(stored, dict):  # pragma: no cover - only drafts are stored here
            raise ProblemException(500, ErrorCode.INTERNAL_ERROR)
        return stored

    draft = await generator.generate_draft(
        session, scope, conversation_id, guidance=request.guidance
    )
    payload = draft.to_contract()
    await repo.complete(claim.record.id, status=201, body=payload)
    return payload
