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
from app.connectors.capabilities import ConnectorCapabilities
from app.db.models import PlatformConnection
from app.db.pagination import DEFAULT_LIMIT, MAX_CURSOR_LEN, MAX_LIMIT
from app.db.repositories import ConnectionRepository
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope
from app.services.connections import ConnectionService, PairingError

__all__ = ["connection_out", "get_connection_service", "router"]

router = APIRouter(tags=["connections"])

Scope = Annotated[UserScope, Depends(get_current_user_scope)]
Session = Annotated[AsyncSession, Depends(get_session)]
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


class ConnectionCreateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    platform: Literal["sms", "android_notification", "mock"]
    display_name: Annotated[str, Field(min_length=1, max_length=120)]
    device_install_id: uuid.UUID | None = None
    capabilities: ConnectorCapabilities | None = None


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
            result = await service.pair(
                session,
                scope,
                platform=body.platform,
                display_name=body.display_name,
                device_install_id=body.device_install_id,
                capabilities=body.capabilities,
            )
        except PairingError as exc:
            raise ProblemException(
                422,
                ErrorCode.VALIDATION_ERROR,
                exc.detail,
                title="Request validation failed",
                errors=[{"pointer": exc.pointer, "message": exc.message}],
            ) from None
        return slot.complete(201 if result.created else 200, connection_out(result.connection))


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
