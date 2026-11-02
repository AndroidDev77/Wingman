"""Contract 1.3.0 routes (ADR 0008), all user-scoped (another user's resource is 404).

* ``POST /v1/conversations/{id}/read`` (``markConversationRead``, I-022): idempotent.
* ``GET  /v1/inbound-notices`` (``listInboundNotices``): events never replied to.
* ``GET  /v1/assisted-deliveries`` (``listAssistedDeliveries``), ``POST .../confirm``
  (``confirmAssistedDelivery``: ``user_reported_sent``, never ``sent``/``delivered``)
  and ``POST .../dismiss`` (``dismissAssistedDelivery``).
* ``POST /v1/identity-links`` (``createIdentityLink``), ``GET
  /v1/contacts/{id}/identity-links`` (``listContactIdentityLinks``), ``DELETE
  /v1/identity-links/{id}`` (``deleteIdentityLink``): user-made links only.
* ``POST /v1/profiles`` (``importProfile``), ``GET /v1/profiles/review-queue``
  (``listProfileReviewQueue``, ranked by WING-019 compatibility scoring),
  ``POST /v1/profiles/{id}/decision``
  (``recordProfileDecision``), ``POST /v1/profile-actions/{id}/execute``
  (``requestProfileActionExecution``: the swipe gate; ``queued`` at most, never
  executed here).

No request model accepts a platform credential or session field (``extra="forbid"``).
"""

import re
import uuid
from typing import Annotated, Any

from fastapi import APIRouter, Body, Depends, Header, Query, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.match_evaluator import MatchEvaluator, MatchSettings
from app.api._automation_common import (
    AutomationServices,
    get_services,
    idempotent,
    message_out,
    ts,
)
from app.api.errors import ErrorCode, ProblemException
from app.connectors._types import UtcTimestamp
from app.db.enums import (
    AssistedDeliveryStatus,
    ProfileDecision,
    ProfileProvider,
    ProfileSource,
)
from app.db.models import (
    AssistedDelivery,
    IdentityLink,
    InboundNotice,
    ProfileAction,
    ProfileEvaluation,
)
from app.db.pagination import DEFAULT_LIMIT, MAX_CURSOR_LEN, MAX_LIMIT
from app.db.repositories import (
    AssistedDeliveryRepository,
    ContactRepository,
    ConversationRepository,
    IdentityLinkRepository,
    InboundNoticeRepository,
    ProfileActionRepository,
    ProfileRepository,
)
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope
from app.services.conversations import ConversationReadService
from app.services.integrations import (
    AssistedDeliveryNotPending,
    AssistedDeliveryService,
    IdentityLinkService,
    InvalidRequest,
    ProfileService,
    ProfileView,
    SwipeExecutionDisabled,
)
from app.services.profiles import ProfileMatchingService

__all__ = ["router"]

router = APIRouter()

Scope = Annotated[UserScope, Depends(get_current_user_scope)]
Session = Annotated[AsyncSession, Depends(get_session, scope="function")]
Services = Annotated[AutomationServices, Depends(get_services)]
IdemKey = Annotated[str | None, Header(alias="Idempotency-Key")]
Cursor = Annotated[str | None, Query(min_length=1, max_length=MAX_CURSOR_LEN)]
Limit = Annotated[int, Query(ge=1, le=MAX_LIMIT)]


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


def _invalid(exc: InvalidRequest) -> ProblemException:
    return ProblemException(
        422,
        ErrorCode.VALIDATION_ERROR,
        exc.detail,
        title="Request validation failed",
        errors=[{"pointer": exc.pointer, "message": exc.message}],
    )


# --------------------------------------------------------------------------- serializers


def notice_out(n: InboundNotice) -> dict[str, Any]:
    return {
        "id": str(n.id),
        "connection_id": str(n.connection_id),
        "platform": n.platform,
        "kind": n.kind.value,
        "identity": n.identity.value,
        "sender_label": n.sender_label,
        "text": n.text,
        "truncated": n.truncated,
        "content_hidden": n.content_hidden,
        "external_message_id": n.external_message_id,
        "external_conversation_id": n.external_conversation_id,
        "source": n.source.value if n.source else None,
        "occurred_at": ts(n.occurred_at),
        "created_at": ts(n.created_at),
    }


async def assisted_out(
    session: AsyncSession, scope: UserScope, a: AssistedDelivery
) -> dict[str, Any]:
    conversation = await ConversationRepository(session, scope).require(a.conversation_id)
    return {
        "id": str(a.id),
        "conversation_id": str(a.conversation_id),
        "contact_id": str(a.contact_id),
        "connection_id": str(a.connection_id),
        "platform": conversation.platform,
        "external_thread_id": conversation.external_thread_id,
        "draft_id": str(a.draft_id),
        "text": a.text,
        "reasons": [r.value for r in a.reasons],
        "status": a.status.value,
        "final_text": a.final_text,
        "message_id": str(a.message_id) if a.message_id else None,
        "cancel_reason": a.cancel_reason,
        "created_at": ts(a.created_at),
        "decided_at": ts(a.decided_at),
    }


def link_out(link: IdentityLink) -> dict[str, Any]:
    return {
        "id": str(link.id),
        "contact_ids": sorted([str(link.contact_a_id), str(link.contact_b_id)]),
        "origin": link.origin,
        "created_at": ts(link.created_at),
    }


def evaluation_out(e: ProfileEvaluation) -> dict[str, Any]:
    return {
        "id": str(e.id),
        "profile_id": str(e.profile_id),
        "score": e.score,
        "reasons": e.reasons,
        "known_unknowns": list(e.known_unknowns),
        "intent_mode": e.intent_mode.value,
        "dealbreakers_hit": list(e.dealbreakers_hit),
        "confidence": e.confidence,
        "evaluator_version": e.evaluator_version,
        "created_at": ts(e.created_at),
    }


def action_out(a: ProfileAction) -> dict[str, Any]:
    return {
        "id": str(a.id),
        "profile_id": str(a.profile_id),
        "connection_id": str(a.connection_id) if a.connection_id else None,
        "decision": a.decision.value,
        "idempotency_key": a.idempotency_key,
        "execution": a.execution.value,
        "execution_detail": a.execution_detail,
        "decided_at": ts(a.decided_at),
        "execution_requested_at": ts(a.execution_requested_at),
        "executed_at": ts(a.executed_at),
        "created_at": ts(a.created_at),
    }


def profile_out(v: ProfileView) -> dict[str, Any]:
    p = v.profile
    return {
        "id": str(p.id),
        "provider": p.provider.value,
        "external_profile_id": p.external_profile_id,
        "display_name": p.display_name,
        "bio": p.bio,
        "prompts": p.prompts,
        "attributes": p.attributes,
        "source_observed_at": ts(p.source_observed_at),
        "provenance": {
            "source": p.source.value,
            "connection_id": str(p.connection_id) if p.connection_id else None,
            "imported_at": ts(p.imported_at),
        },
        "stale": v.stale,
        "latest_evaluation": evaluation_out(v.latest_evaluation) if v.latest_evaluation else None,
        "latest_action": action_out(v.latest_action) if v.latest_action else None,
        "created_at": ts(p.created_at),
        "updated_at": ts(p.updated_at),
    }


# --------------------------------------------------------------------------- mark read


class MarkReadRequest(_Strict):
    pass


@router.post(
    "/v1/conversations/{conversation_id}/read",
    operation_id="markConversationRead",
    tags=["conversations"],
)
async def mark_conversation_read(
    conversation_id: uuid.UUID,
    scope: Scope,
    session: Session,
    idempotency_key: IdemKey = None,
    body: Annotated[MarkReadRequest | None, Body()] = None,
) -> JSONResponse:
    conversations = ConversationRepository(session, scope)
    await conversations.require(conversation_id)  # 404 before any key is stored
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/conversations/{conversation_id}/read",
        body={"conversation_id": str(conversation_id)},
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        await conversations.mark_read(conversation_id)
        detail = await ConversationReadService(session, scope).get_conversation(conversation_id)
        return slot.complete(200, detail)


# --------------------------------------------------------------------------- notices


@router.get("/v1/inbound-notices", operation_id="listInboundNotices", tags=["assisted"])
async def list_inbound_notices(
    scope: Scope,
    session: Session,
    cursor: Cursor = None,
    limit: Limit = DEFAULT_LIMIT,
    connection_id: uuid.UUID | None = None,
) -> dict[str, Any]:
    page = await InboundNoticeRepository(session, scope).list(
        connection_id=connection_id, cursor=cursor, limit=limit
    )
    return {"items": [notice_out(n) for n in page.items], "next_cursor": page.next_cursor}


# --------------------------------------------------------------------------- assisted


def _assisted_service(services: Services) -> AssistedDeliveryService:
    return AssistedDeliveryService(services.dispatcher, now=services.dispatcher._now)  # noqa: SLF001


AssistedSvc = Annotated[AssistedDeliveryService, Depends(_assisted_service)]


@router.get("/v1/assisted-deliveries", operation_id="listAssistedDeliveries", tags=["assisted"])
async def list_assisted_deliveries(
    scope: Scope,
    session: Session,
    cursor: Cursor = None,
    limit: Limit = DEFAULT_LIMIT,
    status: AssistedDeliveryStatus = AssistedDeliveryStatus.PENDING,
    conversation_id: uuid.UUID | None = None,
) -> dict[str, Any]:
    page = await AssistedDeliveryRepository(session, scope).list(
        status=status, conversation_id=conversation_id, cursor=cursor, limit=limit
    )
    return {
        "items": [await assisted_out(session, scope, a) for a in page.items],
        "next_cursor": page.next_cursor,
    }


class AssistedDeliveryConfirmRequest(_Strict):
    sent_text: Annotated[str, Field(min_length=1, max_length=4000)] | None = None


class DismissRequest(_Strict):
    reason: Annotated[str, Field(max_length=500)] | None = None


def _not_pending() -> ProblemException:
    return ProblemException(
        409,
        ErrorCode.ASSISTED_DELIVERY_NOT_PENDING,
        "This draft was already confirmed, dismissed or canceled.",
    )


@router.post(
    "/v1/assisted-deliveries/{assisted_delivery_id}/confirm",
    operation_id="confirmAssistedDelivery",
    tags=["assisted"],
)
async def confirm_assisted_delivery(
    assisted_delivery_id: uuid.UUID,
    body: AssistedDeliveryConfirmRequest,
    scope: Scope,
    session: Session,
    service: AssistedSvc,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    await AssistedDeliveryRepository(session, scope).require(assisted_delivery_id)
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/assisted-deliveries/{assisted_delivery_id}/confirm",
        body={"id": str(assisted_delivery_id), "body": body.model_dump(mode="json")},
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        try:
            assisted, message = await service.confirm(
                session, scope, assisted_delivery_id, sent_text=body.sent_text
            )
        except AssistedDeliveryNotPending:
            raise _not_pending() from None
        return slot.complete(
            200,
            {
                "assisted_delivery": await assisted_out(session, scope, assisted),
                "message": message_out(message),
            },
        )


@router.post(
    "/v1/assisted-deliveries/{assisted_delivery_id}/dismiss",
    operation_id="dismissAssistedDelivery",
    tags=["assisted"],
)
async def dismiss_assisted_delivery(
    assisted_delivery_id: uuid.UUID,
    body: DismissRequest,
    scope: Scope,
    session: Session,
    service: AssistedSvc,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    await AssistedDeliveryRepository(session, scope).require(assisted_delivery_id)
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/assisted-deliveries/{assisted_delivery_id}/dismiss",
        body={"id": str(assisted_delivery_id), "body": body.model_dump(mode="json")},
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        try:
            assisted = await service.dismiss(session, scope, assisted_delivery_id)
        except AssistedDeliveryNotPending:
            raise _not_pending() from None
        return slot.complete(
            200,
            {"assisted_delivery": await assisted_out(session, scope, assisted), "message": None},
        )


# --------------------------------------------------------------------------- identity links


class IdentityLinkCreateRequest(_Strict):
    contact_id: uuid.UUID
    linked_contact_id: uuid.UUID


@router.post("/v1/identity-links", operation_id="createIdentityLink", tags=["identity"])
async def create_identity_link(
    body: IdentityLinkCreateRequest,
    scope: Scope,
    session: Session,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    contacts = ContactRepository(session, scope)
    await contacts.require(body.contact_id)  # another user's contact: 404, no key stored
    await contacts.require(body.linked_contact_id)
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/identity-links",
        body=body.model_dump(mode="json"),
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        try:
            link, created = await IdentityLinkService().link(
                session, scope, body.contact_id, body.linked_contact_id
            )
        except InvalidRequest as exc:
            raise _invalid(exc) from None
        return slot.complete(201 if created else 200, link_out(link))


@router.get(
    "/v1/contacts/{contact_id}/identity-links",
    operation_id="listContactIdentityLinks",
    tags=["identity"],
)
async def list_contact_identity_links(
    contact_id: uuid.UUID,
    scope: Scope,
    session: Session,
    cursor: Cursor = None,
    limit: Limit = DEFAULT_LIMIT,
) -> dict[str, Any]:
    await ContactRepository(session, scope).require(contact_id)
    page = await IdentityLinkRepository(session, scope).list_for_contact(
        contact_id, cursor=cursor, limit=limit
    )
    return {"items": [link_out(link) for link in page.items], "next_cursor": page.next_cursor}


@router.delete(
    "/v1/identity-links/{identity_link_id}",
    operation_id="deleteIdentityLink",
    tags=["identity"],
    status_code=204,
)
async def delete_identity_link(
    identity_link_id: uuid.UUID, scope: Scope, session: Session
) -> Response:
    await IdentityLinkService().unlink(session, scope, identity_link_id)
    return Response(status_code=204)


# --------------------------------------------------------------------------- profiles


class ProfilePrompt(_Strict):
    prompt: Annotated[str, Field(min_length=1, max_length=300)]
    answer: Annotated[str, Field(max_length=1000)]


AttrValue = (
    Annotated[str, Field(max_length=500)]
    | float
    | bool
    | Annotated[list[Annotated[str, Field(max_length=200)]], Field(max_length=50)]
)


class ProfileImportRequest(_Strict):
    provider: ProfileProvider
    external_profile_id: Annotated[str, Field(min_length=1, max_length=256)]
    source: ProfileSource
    connection_id: uuid.UUID | None = None
    display_name: Annotated[str, Field(max_length=120)] | None = None
    bio: Annotated[str, Field(max_length=5000)] | None = None
    prompts: Annotated[list[ProfilePrompt], Field(max_length=20)] = []
    attributes: Annotated[dict[str, AttrValue], Field(max_length=50)] = {}
    source_observed_at: UtcTimestamp


def _check_import(body: ProfileImportRequest) -> None:
    if body.source is ProfileSource.DEVICE and body.connection_id is None:
        raise InvalidRequest(
            "device import needs connection_id", pointer="/connection_id", message="required"
        )
    if body.provider is ProfileProvider.MOCK and body.source is not ProfileSource.FIXTURE:
        raise InvalidRequest(
            "mock profiles are fixtures only", pointer="/source", message="must be 'fixture'"
        )
    for name in body.attributes:
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", name):
            raise InvalidRequest(
                "attribute names are lowercase snake_case",
                pointer=f"/attributes/{name}",
                message="invalid name",
            )


def _profile_service(services: Services) -> ProfileService:
    return ProfileService(now=services.dispatcher._now)  # noqa: SLF001


ProfileSvc = Annotated[ProfileService, Depends(_profile_service)]


@router.post("/v1/profiles", operation_id="importProfile", tags=["profiles"])
async def import_profile(
    body: ProfileImportRequest,
    scope: Scope,
    session: Session,
    service: ProfileSvc,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/profiles",
        body=body.model_dump(mode="json"),
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        try:
            _check_import(body)
            view, created = await service.import_profile(
                session,
                scope,
                provider=body.provider,
                external_profile_id=body.external_profile_id,
                source=body.source,
                connection_id=body.connection_id,
                display_name=body.display_name,
                bio=body.bio,
                prompts=[p.model_dump() for p in body.prompts],
                attributes=dict(body.attributes),
                source_observed_at=body.source_observed_at,
            )
        except InvalidRequest as exc:
            raise _invalid(exc) from None
        return slot.complete(201 if created else 200, profile_out(view))


def get_match_evaluator(request: Request, services: Services) -> MatchEvaluator:
    """WING-019 evaluator: rules only unless ``WINGMAN_MATCH_LLM_ENABLED`` is set, then
    the app's configured gateway (the reply generator's). ``app.state.match_evaluator``
    overrides both (tests, dev)."""
    configured: MatchEvaluator | None = getattr(request.app.state, "match_evaluator", None)
    if configured is not None:
        return configured
    if not MatchSettings().llm_enabled:
        return MatchEvaluator()
    generator = getattr(services.controller, "generator", None)
    return MatchEvaluator(getattr(generator, "gateway", None))


@router.get("/v1/profiles/review-queue", operation_id="listProfileReviewQueue", tags=["profiles"])
async def list_profile_review_queue(
    scope: Scope,
    session: Session,
    services: Services,
    evaluator: Annotated[MatchEvaluator, Depends(get_match_evaluator)],
    cursor: Cursor = None,
    limit: Limit = DEFAULT_LIMIT,
    provider: ProfileProvider | None = None,
) -> dict[str, Any]:
    """Ranked by WING-019 (``app.services.profiles``): current evaluation first (lazily
    re-evaluated), fresh before stale, dealbreaker hits left out."""
    matching = ProfileMatchingService(evaluator, now=services.dispatcher._now)  # noqa: SLF001
    page = await matching.review_queue(
        session, scope, provider=provider, cursor=cursor, limit=limit
    )
    return {
        "items": [profile_out(view) for view in page.items],
        "next_cursor": page.next_cursor,
    }


class ProfileDecisionRequest(_Strict):
    decision: ProfileDecision
    connection_id: uuid.UUID | None = None


@router.post(
    "/v1/profiles/{profile_id}/decision",
    operation_id="recordProfileDecision",
    tags=["profiles"],
    status_code=201,
)
async def record_profile_decision(
    profile_id: uuid.UUID,
    body: ProfileDecisionRequest,
    scope: Scope,
    session: Session,
    service: ProfileSvc,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    await ProfileRepository(session, scope).require(profile_id)
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/profiles/{profile_id}/decision",
        body={"profile_id": str(profile_id), "body": body.model_dump(mode="json")},
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        assert idempotency_key is not None  # noqa: S101 - idempotent() required it
        try:
            action = await service.decide(
                session,
                scope,
                profile_id,
                decision=body.decision,
                connection_id=body.connection_id,
                idempotency_key=idempotency_key,
            )
        except InvalidRequest as exc:
            raise _invalid(exc) from None
        return slot.complete(201, action_out(action))


class ExecuteRequest(_Strict):
    pass


@router.post(
    "/v1/profile-actions/{profile_action_id}/execute",
    operation_id="requestProfileActionExecution",
    tags=["profiles"],
)
async def request_profile_action_execution(
    profile_action_id: uuid.UUID,
    body: ExecuteRequest,
    scope: Scope,
    session: Session,
    service: ProfileSvc,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    await ProfileActionRepository(session, scope).require(profile_action_id)
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/profile-actions/{profile_action_id}/execute",
        body={"id": str(profile_action_id)},
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        try:
            action = await service.request_execution(session, scope, profile_action_id)
        except SwipeExecutionDisabled as exc:
            raise ProblemException(
                422,
                ErrorCode.SWIPE_EXECUTION_DISABLED,
                "This decision cannot be executed on the platform.",
                errors=[{"pointer": exc.pointer, "message": exc.message}],
            ) from None
        return slot.complete(200, action_out(action))
