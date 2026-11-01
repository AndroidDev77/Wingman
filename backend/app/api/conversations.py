"""Conversation read routes (I-021), exactly as in ``contracts/openapi.yaml`` v1.2.0.

* ``GET /v1/conversations`` (``listConversations``): the unified inbox, newest
  activity first (``last_activity_at`` DESC, then ``id``). Filters ``platform``,
  ``stage``, ``intent`` (effective intent) and ``automation_mode`` (effective mode).
  Keyset cursor (ADR 0003) bound to the filters: a malformed cursor, one from another
  listing, or one replayed with different filters is ``422 validation_error``.
* ``GET /v1/conversations/{conversation_id}`` (``getConversation``):
  ``ConversationDetail`` with the stored advisory ``analysis`` (or null), effective
  automation and ``pending_approval_count``.
* ``GET /v1/conversations/{conversation_id}/messages`` (``listMessages``):
  chronological (``created_at`` ASC, then ``id``); ``next_cursor`` moves forward in
  time. A cursor from another conversation is ``422``.

Every route is user-scoped (``get_current_user_scope``); another user's conversation
is ``404 not_found``. Reads never change state: the contract defines no mark-read
operation, so ``unread_count`` is reported as stored and is not reset here.
"""

import uuid
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import AutomationMode, ConversationStage, IntentMode
from app.db.pagination import DEFAULT_LIMIT, MAX_CURSOR_LEN, MAX_LIMIT
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope
from app.services.conversations import ConversationReadService

__all__ = ["router"]

router = APIRouter()

Scope = Annotated[UserScope, Depends(get_current_user_scope)]
Session = Annotated[AsyncSession, Depends(get_session, scope="function")]
Cursor = Annotated[str | None, Query(min_length=1, max_length=MAX_CURSOR_LEN)]
Limit = Annotated[int, Query(ge=1, le=MAX_LIMIT)]
PlatformParam = Annotated[str | None, Query(pattern=r"^[a-z][a-z0-9_]{1,31}$")]


@router.get("/v1/conversations", operation_id="listConversations", tags=["conversations"])
async def list_conversations(
    scope: Scope,
    session: Session,
    cursor: Cursor = None,
    limit: Limit = DEFAULT_LIMIT,
    platform: PlatformParam = None,
    intent: IntentMode | None = None,
    automation_mode: AutomationMode | None = None,
    stage: ConversationStage | None = None,
) -> dict[str, Any]:
    return await ConversationReadService(session, scope).list_conversations(
        cursor=cursor,
        limit=limit,
        platform=platform,
        stage=stage,
        intent=intent,
        automation_mode=automation_mode,
    )


@router.get(
    "/v1/conversations/{conversation_id}",
    operation_id="getConversation",
    tags=["conversations"],
)
async def get_conversation(
    conversation_id: uuid.UUID, scope: Scope, session: Session
) -> dict[str, Any]:
    return await ConversationReadService(session, scope).get_conversation(conversation_id)


@router.get(
    "/v1/conversations/{conversation_id}/messages",
    operation_id="listMessages",
    tags=["messages"],
)
async def list_messages(
    conversation_id: uuid.UUID,
    scope: Scope,
    session: Session,
    cursor: Cursor = None,
    limit: Limit = DEFAULT_LIMIT,
) -> dict[str, Any]:
    return await ConversationReadService(session, scope).list_messages(
        conversation_id, cursor=cursor, limit=limit
    )
