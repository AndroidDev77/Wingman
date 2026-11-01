"""DEV-ONLY demo API for the WING-006 MockConnector.

Not part of `contracts/openapi.yaml` and deliberately outside `/v1`: it has no auth,
uses one fixed demo user and connection, and keeps state in process memory. It
must only be mounted when `settings.env != "prod"`.

Mounting (integrator, in `app/main.py`):

    from app.api.demo_connector import create_demo_connector_router
    if settings.env != "prod":
        app.include_router(create_demo_connector_router())

Endpoints (prefix `/dev/connectors/mock`):

- `GET  /capabilities`                         connector_capabilities.v1 document
- `POST /inbound`                              inject a simulated inbound message
- `GET  /conversations`                        list simulated threads
- `GET  /conversations/{thread_id}/messages`   read a thread (oldest first)
- `POST /send`                                 simulated send; requires `Idempotency-Key`
- `POST /failures`                             inject send failures
- `DELETE /failures`                           clear injected failures
- `POST /receipts/{outbound_message_id}`       simulate a delivery receipt
- `GET  /events`                               every emitted connector_event.v1
"""

from __future__ import annotations

from typing import Annotated, Any
from uuid import NAMESPACE_URL, UUID, uuid5

from fastapi import APIRouter, Header, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from app.connectors import (
    ConnectorError,
    IdempotencyKeyConflict,
    InvalidCursor,
    MockConnector,
    SendFailureCode,
    SendRequest,
    UnsupportedCapability,
)
from app.connectors.models import DEFAULT_PAGE_LIMIT, MAX_PAGE_LIMIT

DEMO_PREFIX = "/dev/connectors/mock"
DEMO_USER_ID = UUID("00000000-0000-4000-8000-00000000d001")
DEMO_CONNECTION_ID = UUID("00000000-0000-4000-8000-00000000d0c1")

IdempotencyKeyHeader = Annotated[
    str,
    Header(alias="Idempotency-Key", pattern=r"^[A-Za-z0-9._:-]{8,128}$"),
]
Limit = Annotated[int, Query(ge=1, le=MAX_PAGE_LIMIT)]


class _Body(BaseModel):
    model_config = ConfigDict(extra="forbid")


class InjectInboundBody(_Body):
    external_thread_id: str = Field(min_length=1, max_length=256)
    sender_id: str = Field(min_length=1, max_length=256)
    text: str = Field(max_length=20000)
    display_name: str | None = Field(default=None, max_length=200)
    external_message_id: str | None = Field(default=None, min_length=1, max_length=256)


class SendBody(_Body):
    external_thread_id: str = Field(min_length=1, max_length=256)
    text: str = Field(min_length=1, max_length=20000)
    outbound_message_id: UUID | None = Field(
        default=None,
        description="Wingman message ID. Defaults to one derived from the idempotency key.",
    )


class InjectFailuresBody(_Body):
    count: int | None = Field(default=1, ge=1, description="null = fail until cleared")
    code: SendFailureCode = SendFailureCode.TRANSPORT_ERROR
    retryable: bool = True


def _problem(status: int, code: str, title: str, detail: str) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        media_type="application/problem+json",
        content={
            "type": f"https://wingman.invalid/problems/{code}",
            "title": title,
            "status": status,
            "code": code,
            "detail": detail,
        },
    )


def _error_response(exc: ConnectorError) -> JSONResponse:
    if isinstance(exc, UnsupportedCapability):
        return _problem(422, exc.code, "Connection does not support this operation", str(exc))
    if isinstance(exc, IdempotencyKeyConflict):
        return _problem(422, exc.code, "Idempotency key reused with a different request", str(exc))
    if isinstance(exc, InvalidCursor):
        return _problem(422, exc.code, "Invalid cursor", str(exc))
    return _problem(422, exc.code, "Connector error", str(exc))


def _derive_message_id(idempotency_key: str) -> UUID:
    return uuid5(NAMESPACE_URL, f"wingman-demo-send:{idempotency_key}")


def create_demo_connector_router(connector: MockConnector | None = None) -> APIRouter:
    """Build the dev-only router around one `MockConnector` (a fresh one by default)."""
    mock = (
        connector
        if connector is not None
        else MockConnector(
            user_id=DEMO_USER_ID, connection_id=DEMO_CONNECTION_ID, scenario_id="dev_demo"
        )
    )
    router = APIRouter(prefix=DEMO_PREFIX, tags=["dev: mock connector"])

    @router.get("/capabilities")
    async def get_capabilities() -> dict[str, Any]:
        return mock.capabilities().to_contract_dict()

    @router.post("/inbound", status_code=201, response_model=None)
    async def inject_inbound(body: InjectInboundBody) -> JSONResponse:
        result = await mock.inject_inbound(
            external_thread_id=body.external_thread_id,
            sender_id=body.sender_id,
            text=body.text,
            display_name=body.display_name,
            external_message_id=body.external_message_id,
        )
        return JSONResponse(
            status_code=200 if result.duplicate else 201,
            content={
                "outcome": result.outcome.value,
                "duplicate": result.duplicate,
                "event": result.event.to_contract_dict(),
            },
        )

    @router.get("/conversations", response_model=None)
    async def list_conversations(
        cursor: str | None = None, limit: Limit = DEFAULT_PAGE_LIMIT
    ) -> dict[str, Any] | JSONResponse:
        try:
            page = await mock.list_conversations(cursor=cursor, limit=limit)
        except ConnectorError as exc:
            return _error_response(exc)
        return page.model_dump(mode="json")

    @router.get("/conversations/{external_thread_id}/messages", response_model=None)
    async def read_messages(
        external_thread_id: str, cursor: str | None = None, limit: Limit = DEFAULT_PAGE_LIMIT
    ) -> dict[str, Any] | JSONResponse:
        try:
            page = await mock.read_messages(external_thread_id, cursor=cursor, limit=limit)
        except ConnectorError as exc:
            return _error_response(exc)
        return page.model_dump(mode="json")

    @router.post("/send", response_model=None)
    async def send(
        body: SendBody, idempotency_key: IdempotencyKeyHeader
    ) -> dict[str, Any] | JSONResponse:
        request = SendRequest(
            outbound_message_id=body.outbound_message_id or _derive_message_id(idempotency_key),
            idempotency_key=idempotency_key,
            external_thread_id=body.external_thread_id,
            text=body.text,
        )
        try:
            result = await mock.send_message(request)
        except ConnectorError as exc:
            return _error_response(exc)
        headers = {"Idempotent-Replayed": "true"} if result.replayed else None
        return JSONResponse(content=result.model_dump(mode="json"), headers=headers)

    @router.post("/failures", status_code=204)
    async def inject_failures(body: InjectFailuresBody) -> None:
        mock.inject_send_failures(body.count, code=body.code, retryable=body.retryable)

    @router.delete("/failures", status_code=204)
    async def clear_failures() -> None:
        mock.clear_send_failures()

    @router.post("/receipts/{outbound_message_id}", response_model=None)
    async def simulate_receipt(outbound_message_id: UUID) -> dict[str, Any] | JSONResponse:
        try:
            event = await mock.simulate_delivery_receipt(outbound_message_id)
        except ValueError as exc:
            return _problem(409, "validation_error", "Cannot simulate receipt", str(exc))
        return event.to_contract_dict()

    @router.get("/events")
    async def list_events() -> dict[str, Any]:
        return {"items": [e.to_contract_dict() for e in mock.events]}

    return router
