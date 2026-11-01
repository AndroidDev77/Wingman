"""``POST /v1/conversations/{conversation_id}/messages`` (``sendMessage``), WING-011.

The user's own text, through the single dispatcher: ``202`` with the message as
``queued`` (never ``sent``). Allowed while the contact is paused or taken over (the user
is the author); the dispatcher still checks ``send`` (``UNSUPPORTED`` is a typed
``422 capability_unsupported`` and nothing is queued). ``Idempotency-Key`` is required:
a replay returns the same message and never queues a second send.
"""

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Header
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, StrictStr
from sqlalchemy.ext.asyncio import AsyncSession

from app.api._automation_common import (
    AutomationServices,
    commit_then_dispatch,
    get_services,
    idempotent,
    message_out,
)
from app.api.errors import ErrorCode, ProblemException
from app.db.repositories import ConversationRepository
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope
from app.services.automation import SendCapabilityUnsupported

__all__ = ["SendMessageRequest", "router"]

ROUTE = "/v1/conversations/{conversation_id}/messages"

router = APIRouter(tags=["messages"])


class SendMessageRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    text: Annotated[StrictStr, Field(min_length=1, max_length=4000)]


@router.post(ROUTE, status_code=202, operation_id="sendMessage")
async def send_message(
    conversation_id: uuid.UUID,
    body: SendMessageRequest,
    scope: Annotated[UserScope, Depends(get_current_user_scope)],
    session: Annotated[AsyncSession, Depends(get_session)],
    services: Annotated[AutomationServices, Depends(get_services)],
    idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
) -> JSONResponse:
    await ConversationRepository(session, scope).require(conversation_id)  # 404 first
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route=ROUTE,
        body={"conversation_id": str(conversation_id), "body": body.model_dump()},
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        try:
            message = await services.controller.send_manual(
                session, scope, conversation_id, body.text
            )
        except SendCapabilityUnsupported:
            raise ProblemException(
                422,
                ErrorCode.CAPABILITY_UNSUPPORTED,
                "Connection capability 'send' is UNSUPPORTED.",
                title="Connection does not support sending",
            ) from None
        response = slot.complete(202, message_out(message))
    # The decision, the queued message and the stored response are committed BEFORE
    # any transport call (D-006 replay; never roll back a real send).
    await commit_then_dispatch(session, services, scope, message.id)
    return response
