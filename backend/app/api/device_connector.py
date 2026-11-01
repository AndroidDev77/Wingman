"""Device connector routes (contract v1.1.0, ADR 0006, I-010 backend side).

* ``POST /v1/connections/{connection_id}/events`` (``ingestConnectorEvent``): one
  ``connector_event.v1`` envelope. ``202 accepted`` on first sight, ``200``
  ``duplicate_event`` / ``duplicate_message`` on repeats (``Idempotent-Replayed: true``
  for ``duplicate_event``), ``404`` for another user's connection or envelope
  ``user_id``, ``422`` for an invalid or inconsistent envelope, ``mock.*`` on a real
  connection (pointer ``/type``), or a send outcome that does not match a leased
  instruction (pointer under ``/payload``). A reused key with a different body is
  ``422 idempotency_key_mismatch``. A new inbound message is committed, then the
  automation pipeline runs in its own transaction (a pipeline failure never undoes the
  ingest and never sends).
* ``GET  /v1/connections/{connection_id}/outbox`` (``listOutboxInstructions``):
  read-only summaries, oldest first; default ``state=pending``.
* ``POST /v1/connections/{connection_id}/outbox/claim`` (``claimOutboxInstructions``):
  leases with ``FOR UPDATE SKIP LOCKED``; claim-time policy re-check; replay of the
  same ``Idempotency-Key`` returns the original instructions and leases nothing.
"""

import json
import logging
import uuid
from collections.abc import Awaitable, Callable
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Header, Query, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.api._automation_common import (
    AutomationServices,
    get_services,
    idempotent,
    instruction_summary_out,
    require_key,
    ts,
)
from app.api.errors import ErrorCode, ProblemException, error_pointer
from app.connectors.events import ConnectorEvent
from app.db.enums import OutboxInstructionState
from app.db.pagination import DEFAULT_LIMIT, MAX_CURSOR_LEN, MAX_LIMIT
from app.db.repositories import ConnectionRepository, OutboxRepository
from app.db.scope import UserScope
from app.db.session import get_session, session_scope
from app.security.deps import get_current_user_scope
from app.services.ingest import IngestError, IngestOutcome
from app.services.outbox import ClaimRejected

__all__ = ["PipelineRunner", "get_pipeline_runner", "router"]

logger = logging.getLogger(__name__)

router = APIRouter(tags=["connections"])

Scope = Annotated[UserScope, Depends(get_current_user_scope)]
Session = Annotated[AsyncSession, Depends(get_session, scope="function")]
Services = Annotated[AutomationServices, Depends(get_services)]
IdemKey = Annotated[str | None, Header(alias="Idempotency-Key")]

PipelineRunner = Callable[[UserScope, uuid.UUID], Awaitable[None]]


def get_pipeline_runner(request: Request) -> PipelineRunner:
    """Runs the automation pipeline for a newly ingested inbound message, in its own
    transaction after the ingest committed. Tests override this dependency."""
    services: AutomationServices | None = getattr(request.app.state, "automation", None)

    async def run(scope: UserScope, message_id: uuid.UUID) -> None:
        current = services or await get_services(request)
        try:
            async with session_scope() as session:
                result = await current.controller.handle_inbound_message(
                    session, scope, message_id, defer_dispatch=True
                )
            # The autopilot send is committed as queued; transmit in a new transaction.
            if result.outbound_message_id is not None:
                async with session_scope() as session:
                    await current.dispatcher.dispatch(session, scope, result.outbound_message_id)
        except Exception as exc:
            # Fail safe: the message is stored and never undone. Log the class only: a
            # database error's text can carry bound parameters (message text).
            logger.error(  # noqa: TRY400 - no traceback on purpose (redaction)
                "automation pipeline failed user=%s message=%s error_class=%s",
                scope.user_id,
                message_id,
                type(exc).__name__,
            )

    return run


# --------------------------------------------------------------------------- ingest


def _invalid(detail: str, pointer: str, message: str) -> ProblemException:
    return ProblemException(
        422,
        ErrorCode.VALIDATION_ERROR,
        detail,
        title="Request validation failed",
        errors=[{"pointer": pointer, "message": message}],
    )


@router.post(
    "/v1/connections/{connection_id}/events",
    operation_id="ingestConnectorEvent",
    status_code=202,
)
async def ingest_event(
    connection_id: uuid.UUID,
    request: Request,
    scope: Scope,
    session: Session,
    services: Services,
    runner: Annotated[PipelineRunner, Depends(get_pipeline_runner)],
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    key = require_key(idempotency_key)
    try:
        raw: Any = json.loads(await request.body())
    except ValueError:
        raise _invalid("request body is not JSON", "", "invalid JSON") from None
    # 1. Ownership: the connection and the envelope's user must be the caller's.
    connection = await ConnectionRepository(session, scope).get(connection_id)
    if connection is None or not isinstance(raw, dict) or raw.get("user_id") != str(scope.user_id):
        raise ProblemException(404, ErrorCode.NOT_FOUND, "Resource not found")
    # 2. Envelope validity and consistency.
    try:
        event = ConnectorEvent.model_validate(raw)
    except ValidationError as exc:
        errors = [
            {"pointer": error_pointer(("body", *e["loc"])), "message": e["msg"]}
            for e in exc.errors(include_input=False)
        ]
        raise ProblemException(
            422,
            ErrorCode.VALIDATION_ERROR,
            "envelope does not validate against connector_event.v1",
            title="Request validation failed",
            errors=errors[:20],
        ) from None
    if event.connection_id != connection_id:
        raise _invalid(
            "envelope connection_id does not match the path", "/connection_id", "must equal path"
        )
    if key != str(event.event_id):
        raise _invalid(
            "Idempotency-Key must equal the envelope's event_id",
            "header:Idempotency-Key",
            "must equal event_id",
        )
    try:
        result = await services.ingest.ingest(
            session,
            scope,
            connection,
            event,
            request_hash=services.ingest.request_hash(raw),
        )
    except IngestError as exc:
        if exc.mismatch:
            raise ProblemException(422, ErrorCode.IDEMPOTENCY_KEY_MISMATCH, exc.detail) from None
        raise _invalid(exc.detail, exc.pointer or "", exc.message) from None
    body = {
        "event_id": str(result.event_id),
        "outcome": result.outcome.value,
        "message_id": str(result.message_id) if result.message_id else None,
        "recorded_at": ts(result.recorded_at),
    }
    if result.outcome is IngestOutcome.ACCEPTED:
        if result.new_inbound_message_id is not None:
            await session.commit()  # the message is stored before any automation runs
            await runner(scope, result.new_inbound_message_id)
        return JSONResponse(body, status_code=202)
    headers = (
        {"Idempotent-Replayed": "true"} if result.outcome is IngestOutcome.DUPLICATE_EVENT else {}
    )
    return JSONResponse(body, status_code=200, headers=headers)


# --------------------------------------------------------------------------- outbox


@router.get("/v1/connections/{connection_id}/outbox", operation_id="listOutboxInstructions")
async def list_outbox(
    connection_id: uuid.UUID,
    scope: Scope,
    session: Session,
    state: OutboxInstructionState = OutboxInstructionState.PENDING,
    cursor: Annotated[str | None, Query(min_length=1, max_length=MAX_CURSOR_LEN)] = None,
    limit: Annotated[int, Query(ge=1, le=MAX_LIMIT)] = DEFAULT_LIMIT,
) -> dict[str, Any]:
    await ConnectionRepository(session, scope).require(connection_id)
    page = await OutboxRepository(session, scope).list(
        connection_id, state=state, cursor=cursor, limit=limit
    )
    return {
        "items": [instruction_summary_out(i) for i in page.items],
        "next_cursor": page.next_cursor,
    }


class OutboxClaimRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    device_install_id: uuid.UUID
    max_instructions: Annotated[int, Field(ge=1, le=20)] = 1


@router.post("/v1/connections/{connection_id}/outbox/claim", operation_id="claimOutboxInstructions")
async def claim_outbox(
    connection_id: uuid.UUID,
    body: OutboxClaimRequest,
    scope: Scope,
    session: Session,
    services: Services,
    idempotency_key: IdemKey = None,
) -> JSONResponse:
    await ConnectionRepository(session, scope).require(connection_id)
    async with idempotent(
        session,
        scope,
        key=idempotency_key,
        route="/v1/connections/{connection_id}/outbox/claim",
        body={"connection_id": str(connection_id), "body": body.model_dump(mode="json")},
    ) as slot:
        if slot.replay is not None:
            return slot.replay
        try:
            claimed, server_time = await services.outbox.claim(
                session,
                scope,
                connection_id,
                device_install_id=body.device_install_id,
                max_instructions=body.max_instructions,
            )
        except ClaimRejected as exc:  # I-018: only the paired install may claim
            raise _invalid(
                "This install may not claim instructions on this connection",
                exc.pointer,
                exc.message,
            ) from None
        payload = {
            "instructions": [
                instruction_summary_out(c.instruction)
                | {
                    "conversation_id": str(c.conversation_id),
                    "external_thread_id": c.external_thread_id,
                    "text": c.message.text,
                    "authorization": {
                        "origin": c.message.origin.value,
                        "approval_id": str(c.message.approval_id)
                        if c.message.approval_id
                        else None,
                    },
                }
                for c in claimed
            ],
            "server_time": ts(server_time),
        }
        return slot.complete(200, payload)
