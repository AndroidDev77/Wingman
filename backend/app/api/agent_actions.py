"""Action API (WING-011), exactly as in ``contracts/openapi.yaml`` v1.1.0.

* ``GET  /v1/approvals``, ``GET /v1/approvals/{id}``
* ``POST /v1/approvals/{id}/approve`` (optional ``edited_text``: what is sent, audited
  as ``edited``), ``POST /v1/approvals/{id}/reject``
* ``GET  /v1/automation``, ``POST /v1/automation/pause|resume``
* ``GET  /v1/contacts/{id}/automation``, ``POST .../pause|resume|takeover``
* ``GET  /v1/agent-actions`` (the audit timeline)

Every route is user-scoped (``get_current_user_scope``; another user's approval or
contact is ``404``), every POST requires ``Idempotency-Key`` (ADR 0004), every error is
``application/problem+json``. Approving never reports ``sent``: the response carries
the message as queued; the decision and the queued message are committed, then the
dispatcher runs in a new transaction.
"""

import uuid
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Header, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, StrictStr
from sqlalchemy.ext.asyncio import AsyncSession

from app.api._automation_common import (
    AutomationServices,
    action_out,
    approval_out,
    commit_then_dispatch,
    get_services,
    idempotent,
    message_out,
    ts,
)
from app.api.errors import ErrorCode, ProblemException
from app.db.enums import AgentActionType, ApprovalStatus, ContactAutomationStatus
from app.db.models import Contact, User
from app.db.pagination import DEFAULT_LIMIT, MAX_CURSOR_LEN, MAX_LIMIT
from app.db.repositories import (
    AgentActionRepository,
    ApprovalRepository,
    ContactRepository,
    MessageRepository,
    UserRepository,
)
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope
from app.services.automation import ApprovalNotPending, SendCapabilityUnsupported
from app.services.preferences import get_effective_preferences

__all__ = ["router"]

router = APIRouter()

Scope = Annotated[UserScope, Depends(get_current_user_scope)]
Session = Annotated[AsyncSession, Depends(get_session, scope="function")]
Services = Annotated[AutomationServices, Depends(get_services)]
IdemKey = Annotated[str | None, Header(alias="Idempotency-Key")]
Cursor = Annotated[str | None, Query(min_length=1, max_length=MAX_CURSOR_LEN)]
Limit = Annotated[int, Query(ge=1, le=MAX_LIMIT)]


class _Body(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class ApproveRequest(_Body):
    edited_text: Annotated[StrictStr, Field(min_length=1, max_length=4000)] | None = None


class RejectRequest(_Body):
    reason: Annotated[StrictStr, Field(max_length=500)] | None = None


class AutomationActionRequest(_Body):
    reason: Annotated[StrictStr, Field(max_length=500)] | None = None


# --------------------------------------------------------------------------- approvals


@router.get("/v1/approvals", operation_id="listApprovals", tags=["approvals"])
async def list_approvals(
    scope: Scope,
    session: Session,
    status: ApprovalStatus = ApprovalStatus.PENDING,
    conversation_id: uuid.UUID | None = None,
    contact_id: uuid.UUID | None = None,
    cursor: Cursor = None,
    limit: Limit = DEFAULT_LIMIT,
) -> dict[str, Any]:
    page = await ApprovalRepository(session, scope).list(
        status=status,
        conversation_id=conversation_id,
        contact_id=contact_id,
        cursor=cursor,
        limit=limit,
    )
    messages = MessageRepository(session, scope)
    items = []
    for approval in page.items:
        sent = await messages.get_by_approval(approval.id)
        items.append(await approval_out(session, scope, approval, sent.id if sent else None))
    return {"items": items, "next_cursor": page.next_cursor}


@router.get("/v1/approvals/{approval_id}", operation_id="getApproval", tags=["approvals"])
async def get_approval(approval_id: uuid.UUID, scope: Scope, session: Session) -> dict[str, Any]:
    approval = await ApprovalRepository(session, scope).require(approval_id)
    sent = await MessageRepository(session, scope).get_by_approval(approval.id)
    return await approval_out(session, scope, approval, sent.id if sent else None)


def _not_pending(status: str) -> ProblemException:
    return ProblemException(
        409, ErrorCode.APPROVAL_NOT_PENDING, f"Approval is {status}, not pending"
    )


def _unsupported() -> ProblemException:
    return ProblemException(
        422,
        ErrorCode.CAPABILITY_UNSUPPORTED,
        "Connection capability 'send' is UNSUPPORTED.",
        title="Connection does not support sending",
    )


@router.post(
    "/v1/approvals/{approval_id}/approve", operation_id="approveApproval", tags=["approvals"]
)
async def approve(
    approval_id: uuid.UUID,
    body: ApproveRequest,
    scope: Scope,
    session: Session,
    services: Services,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    await ApprovalRepository(session, scope).require(approval_id)  # 404 before the key
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/approvals/{approval_id}/approve",
        body={"approval_id": str(approval_id), "body": body.model_dump(mode="json")},
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        try:
            approval, message = await services.controller.approve(
                session, scope, approval_id, edited_text=body.edited_text
            )
        except ApprovalNotPending as exc:
            raise _not_pending(str(exc)) from None
        except SendCapabilityUnsupported:
            raise _unsupported() from None
        payload = {
            "approval": await approval_out(session, scope, approval, message.id),
            "message": message_out(message),
        }
        response = slot.complete(200, payload)
    # The decision, the queued message and the stored response are committed BEFORE
    # any transport call (D-006 replay; never roll back a real send).
    await commit_then_dispatch(session, services, scope, message.id)
    return response


@router.post(
    "/v1/approvals/{approval_id}/reject", operation_id="rejectApproval", tags=["approvals"]
)
async def reject(
    approval_id: uuid.UUID,
    body: RejectRequest,
    scope: Scope,
    session: Session,
    services: Services,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    await ApprovalRepository(session, scope).require(approval_id)
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/approvals/{approval_id}/reject",
        body={"approval_id": str(approval_id), "body": body.model_dump(mode="json")},
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        try:
            approval = await services.controller.reject(
                session, scope, approval_id, reason=body.reason
            )
        except ApprovalNotPending as exc:
            raise _not_pending(str(exc)) from None
        payload = {"approval": await approval_out(session, scope, approval, None), "message": None}
        return slot.complete(200, payload)


# --------------------------------------------------------------------------- automation


def _global_out(user: User) -> dict[str, Any]:
    return {
        "status": user.global_automation_status.value,
        "changed_at": ts(user.global_automation_changed_at),
    }


async def _contact_out(
    session: AsyncSession,
    scope: UserScope,
    contact: Contact,
    *,
    canceled_approvals: int = 0,
    canceled_messages: int = 0,
) -> dict[str, Any]:
    prefs = await get_effective_preferences(session, scope, contact.id)
    user = await UserRepository(session).get(scope)
    assert user is not None  # noqa: S101 - the scope comes from an existing user
    return {
        "contact_id": str(contact.id),
        "status": contact.automation_status.value,
        "effective_mode": prefs.automation.mode.value,
        "global_status": user.global_automation_status.value,
        "changed_at": ts(contact.automation_status_changed_at),
        "canceled_approval_count": canceled_approvals,
        "canceled_message_count": canceled_messages,
    }


@router.get("/v1/automation", operation_id="getGlobalAutomationState", tags=["automation"])
async def get_global(scope: Scope, session: Session) -> dict[str, Any]:
    user = await UserRepository(session).get(scope)
    assert user is not None  # noqa: S101
    return _global_out(user)


@router.post("/v1/automation/pause", operation_id="pauseAllAutomation", tags=["automation"])
async def pause_all(
    body: AutomationActionRequest,
    scope: Scope,
    session: Session,
    services: Services,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    async with idempotent(
        session, scope, key=idempotency_key, route="/v1/automation/pause", body=body.model_dump()
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        user, _ = await services.controller.pause_all(session, scope)
        return slot.complete(200, _global_out(user))


@router.post("/v1/automation/resume", operation_id="resumeAllAutomation", tags=["automation"])
async def resume_all(
    body: AutomationActionRequest,
    scope: Scope,
    session: Session,
    services: Services,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    async with idempotent(
        session, scope, key=idempotency_key, route="/v1/automation/resume", body=body.model_dump()
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        user = await services.controller.resume_all(session, scope)
        return slot.complete(200, _global_out(user))


@router.get(
    "/v1/contacts/{contact_id}/automation",
    operation_id="getContactAutomationState",
    tags=["automation"],
)
async def get_contact(contact_id: uuid.UUID, scope: Scope, session: Session) -> dict[str, Any]:
    contact = await ContactRepository(session, scope).require(contact_id)
    return await _contact_out(session, scope, contact)


async def _contact_action(
    contact_id: uuid.UUID,
    body: AutomationActionRequest,
    scope: UserScope,
    session: AsyncSession,
    services: AutomationServices,
    key: str | None,
    *,
    action: str,
    status: ContactAutomationStatus,
) -> JSONResponse:
    await ContactRepository(session, scope).require(contact_id)
    async with idempotent(
        session,
        scope,
        key=key,
        route=f"/v1/contacts/{{contact_id}}/automation/{action}",
        body={"contact_id": str(contact_id), "body": body.model_dump()},
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        contact, approvals, messages = await services.controller.set_contact_status(
            session, scope, contact_id, status
        )
        payload = await _contact_out(
            session, scope, contact, canceled_approvals=approvals, canceled_messages=messages
        )
        return slot.complete(200, payload)


@router.post(
    "/v1/contacts/{contact_id}/automation/pause",
    operation_id="pauseContactAutomation",
    tags=["automation"],
)
async def pause_contact(
    contact_id: uuid.UUID,
    body: AutomationActionRequest,
    scope: Scope,
    session: Session,
    services: Services,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    return await _contact_action(
        contact_id,
        body,
        scope,
        session,
        services,
        idempotency_key,
        action="pause",
        status=ContactAutomationStatus.PAUSED,
    )


@router.post(
    "/v1/contacts/{contact_id}/automation/resume",
    operation_id="resumeContactAutomation",
    tags=["automation"],
)
async def resume_contact(
    contact_id: uuid.UUID,
    body: AutomationActionRequest,
    scope: Scope,
    session: Session,
    services: Services,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    return await _contact_action(
        contact_id,
        body,
        scope,
        session,
        services,
        idempotency_key,
        action="resume",
        status=ContactAutomationStatus.ACTIVE,
    )


@router.post(
    "/v1/contacts/{contact_id}/automation/takeover",
    operation_id="takeoverContact",
    tags=["automation"],
)
async def takeover_contact(
    contact_id: uuid.UUID,
    body: AutomationActionRequest,
    scope: Scope,
    session: Session,
    services: Services,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    return await _contact_action(
        contact_id,
        body,
        scope,
        session,
        services,
        idempotency_key,
        action="takeover",
        status=ContactAutomationStatus.TAKEN_OVER,
    )


# --------------------------------------------------------------------------- audit


@router.get("/v1/agent-actions", operation_id="listAgentActions", tags=["audit"])
async def list_agent_actions(
    scope: Scope,
    session: Session,
    conversation_id: uuid.UUID | None = None,
    contact_id: uuid.UUID | None = None,
    type: AgentActionType | None = None,
    cursor: Cursor = None,
    limit: Limit = DEFAULT_LIMIT,
) -> dict[str, Any]:
    page = await AgentActionRepository(session, scope).list(
        cursor=cursor,
        limit=limit,
        conversation_id=conversation_id,
        contact_id=contact_id,
        type=type,
    )
    return {"items": [action_out(a) for a in page.items], "next_cursor": page.next_cursor}
