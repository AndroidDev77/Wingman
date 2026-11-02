"""Connection routes (contract 1.2.0, ADR 0007, I-017).

* ``GET  /v1/connections`` (``listConnections``): oldest first, paired and unpaired.
* ``POST /v1/connections`` (``createConnection``): pair a connector. Capabilities a
  client submits are never stored as ``VERIFIED`` (:func:`trust_claimed_capabilities`);
  ``mock`` only outside ``prod``. ``201`` new, ``200`` for an install that is already
  paired for the platform. ``Idempotency-Key`` per ADR 0004.
* ``GET  /v1/connections/{connection_id}`` (``getConnection``): ``404`` for another
  user's connection.
* ``POST /v1/connections/{connection_id}/unpair`` (``unpairConnection``): cancels
  the connection's unclaimed instructions, queued sends and pending approvals before
  returning.

Contract 1.3.0 (ADR 0008): dating and iMessage platforms, ``transport``, the
experimental consent (``422 experimental_consent_required`` without it),
``POST .../experimental-consent/revoke`` (``revokeExperimentalConsent``, like unpair)
and ``PUT .../settings`` (``putConnectionSettings``, the auto-swipe switch). No request
model here has, or may ever get, a platform credential or session field.
"""

import uuid
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Depends, Header, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.api._automation_common import idempotent, ts
from app.api.errors import ErrorCode, ProblemException
from app.config import get_settings
from app.connectors._types import UtcTimestamp
from app.connectors.capabilities import ConnectorCapabilities
from app.db.enums import ConnectionTransport
from app.db.models import PlatformConnection
from app.db.pagination import DEFAULT_LIMIT, MAX_CURSOR_LEN, MAX_LIMIT
from app.db.repositories import ConnectionRepository
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope
from app.services.connections import (
    ConnectionService,
    ExperimentalConsentInput,
    PairingError,
    SwipeGateClosed,
)

__all__ = ["connection_out", "get_connection_service", "router"]

router = APIRouter(tags=["connections"])

Scope = Annotated[UserScope, Depends(get_current_user_scope)]
Session = Annotated[AsyncSession, Depends(get_session, scope="function")]
IdemKey = Annotated[str | None, Header(alias="Idempotency-Key")]


def get_connection_service() -> ConnectionService:
    """FastAPI dependency. The environment decides whether ``mock`` may be paired."""
    return ConnectionService(env=get_settings().env)


Service = Annotated[ConnectionService, Depends(get_connection_service)]


def connection_out(connection: PlatformConnection) -> dict[str, Any]:
    """Contract ``Connection`` (1.2.0 fields included)."""
    return {
        "id": str(connection.id),
        "platform": connection.platform,
        "display_name": connection.display_name,
        "status": connection.status.value,
        "capabilities": connection.capabilities,
        "created_at": ts(connection.created_at),
        "updated_at": ts(connection.updated_at),
        "device_install_id": str(connection.device_install_id)
        if connection.device_install_id
        else None,
        "paired_at": ts(connection.paired_at),
        "unpaired_at": ts(connection.unpaired_at),
        # contract 1.3.0
        "transport": connection.transport.value,
        "experimental": connection.experimental,
        "experimental_consent": (
            {
                "platform": connection.platform,
                "consent_text_version": connection.consent_text_version,
                "accepted_at": ts(connection.consent_accepted_at),
                "recorded_at": ts(connection.consent_recorded_at),
                "revoked_at": ts(connection.consent_revoked_at),
            }
            if connection.experimental
            else None
        ),
        "experimental_verified_at": ts(connection.experimental_verified_at),
        "auto_swipe_enabled": connection.auto_swipe_enabled,
    }


@router.get("/v1/connections", operation_id="listConnections")
async def list_connections(
    scope: Scope,
    session: Session,
    cursor: Annotated[str | None, Query(min_length=1, max_length=MAX_CURSOR_LEN)] = None,
    limit: Annotated[int, Query(ge=1, le=MAX_LIMIT)] = DEFAULT_LIMIT,
) -> dict[str, Any]:
    page = await ConnectionRepository(session, scope).list(cursor=cursor, limit=limit)
    return {"items": [connection_out(c) for c in page.items], "next_cursor": page.next_cursor}


class ExperimentalConsentModel(BaseModel):
    """Contract 1.3.0 ``ExperimentalConsentInput``: the user's ban-risk opt-in."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    platform: Literal["tinder", "bumble", "hinge"]
    consent_text_version: Annotated[str, Field(pattern=r"^[a-z0-9][a-z0-9._-]{0,31}$")]
    accepted_at: UtcTimestamp
    ban_risk_acknowledged: Literal[True]


class ConnectionCreateRequest(BaseModel):
    # extra="forbid": a credential or session field is rejected like any unknown field.
    model_config = ConfigDict(extra="forbid", frozen=True)

    platform: Literal[
        "sms", "android_notification", "mock", "tinder", "bumble", "hinge", "imessage"
    ]
    display_name: Annotated[str, Field(min_length=1, max_length=120)]
    device_install_id: uuid.UUID | None = None
    capabilities: ConnectorCapabilities | None = None
    transport: ConnectionTransport | None = None
    experimental: bool | None = None
    experimental_consent: ExperimentalConsentModel | None = None


@router.post("/v1/connections", operation_id="createConnection", status_code=201)
async def create_connection(
    body: ConnectionCreateRequest,
    scope: Scope,
    session: Session,
    service: Service,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/connections",
        body=body.model_dump(mode="json"),
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        try:
            consent = body.experimental_consent
            result = await service.pair(
                session,
                scope,
                platform=body.platform,
                display_name=body.display_name,
                device_install_id=body.device_install_id,
                capabilities=body.capabilities,
                transport=body.transport,
                experimental=body.experimental,
                consent=ExperimentalConsentInput(
                    platform=consent.platform,
                    consent_text_version=consent.consent_text_version,
                    accepted_at=consent.accepted_at,
                )
                if consent is not None
                else None,
            )
        except PairingError as exc:
            raise _pairing_problem(exc) from None
        return slot.complete(201 if result.created else 200, connection_out(result.connection))


def _pairing_problem(exc: PairingError) -> ProblemException:
    return ProblemException(
        422,
        ErrorCode(exc.code),
        exc.detail,
        title=None if exc.code != "validation_error" else "Request validation failed",
        errors=[{"pointer": exc.pointer, "message": exc.message}],
    )


@router.get("/v1/connections/{connection_id}", operation_id="getConnection")
async def get_connection(
    connection_id: uuid.UUID, scope: Scope, session: Session
) -> dict[str, Any]:
    return connection_out(await ConnectionRepository(session, scope).require(connection_id))


class ConnectionUnpairRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


@router.post("/v1/connections/{connection_id}/unpair", operation_id="unpairConnection")
async def unpair_connection(
    connection_id: uuid.UUID,
    body: ConnectionUnpairRequest,
    scope: Scope,
    session: Session,
    service: Service,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    # Ownership first: another user's connection is 404 before any key is stored.
    await ConnectionRepository(session, scope).require(connection_id)
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/connections/{connection_id}/unpair",
        body={"connection_id": str(connection_id), "body": body.model_dump(mode="json")},
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        result = await service.unpair(session, scope, connection_id)
        return slot.complete(
            200,
            {
                "connection": connection_out(result.connection),
                "canceled_message_count": result.canceled_message_count,
                "canceled_instruction_count": result.canceled_instruction_count,
                "canceled_approval_count": result.canceled_approval_count,
                "in_flight_message_count": result.in_flight_message_count,
            },
        )


@router.post(
    "/v1/connections/{connection_id}/experimental-consent/revoke",
    operation_id="revokeExperimentalConsent",
)
async def revoke_experimental_consent(
    connection_id: uuid.UUID,
    body: ConnectionUnpairRequest,
    scope: Scope,
    session: Session,
    service: Service,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    """Contract 1.3.0: revoke the ban-risk consent; effective like unpair (ADR 0008)."""
    await ConnectionRepository(session, scope).require(connection_id)
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/connections/{connection_id}/experimental-consent/revoke",
        body={"connection_id": str(connection_id), "body": body.model_dump(mode="json")},
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        try:
            result = await service.revoke_consent(session, scope, connection_id)
        except PairingError as exc:
            raise _pairing_problem(exc) from None
        return slot.complete(
            200,
            {
                "connection": connection_out(result.connection),
                "canceled_message_count": result.canceled_message_count,
                "canceled_instruction_count": result.canceled_instruction_count,
                "canceled_approval_count": result.canceled_approval_count,
                "in_flight_message_count": result.in_flight_message_count,
            },
        )


class ConnectionSettingsUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    auto_swipe_enabled: bool


@router.put("/v1/connections/{connection_id}/settings", operation_id="putConnectionSettings")
async def put_connection_settings(
    connection_id: uuid.UUID,
    body: ConnectionSettingsUpdate,
    scope: Scope,
    session: Session,
    service: Service,
) -> dict[str, Any]:
    """Contract 1.3.0: the auto-swipe switch. Enabling needs the swipe gate (ADR 0008)."""
    try:
        connection = await service.set_auto_swipe(
            session, scope, connection_id, body.auto_swipe_enabled
        )
    except SwipeGateClosed as exc:
        raise ProblemException(
            422,
            ErrorCode.SWIPE_EXECUTION_DISABLED,
            "Auto-swipe cannot be enabled for this connection",
            errors=[{"pointer": exc.pointer, "message": exc.message}],
        ) from None
    return connection_out(connection)
