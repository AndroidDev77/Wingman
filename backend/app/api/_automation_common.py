"""Shared plumbing for the WING-011 routers: contract serializers, the ``Idempotency-Key``
flow (ADR 0004) and the app-wide service dependencies.

Every POST in these routers runs::

    async with idempotent(session, scope, key, route, body) as slot:
        if slot.replay is not None: return slot.replay   # stored status + body
        ... do the work in the same transaction ...
        slot.complete(status, payload)

so the claim, the side effect and the stored response commit together; a 5xx
(exception) rolls all three back and the client may retry with the same key.
"""

import re
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from fastapi import Request
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import ErrorCode, ProblemException
from app.db.models import AgentAction, Approval, Message, OutboxInstruction
from app.db.repositories import DraftRepository
from app.db.repositories.idempotency import ClaimOutcome, IdempotencyRepository, fingerprint
from app.db.scope import UserScope
from app.services.automation import AutomationController, draft_to_contract
from app.services.dispatcher import ConnectorRegistry, Dispatcher
from app.services.ingest import IngestService
from app.services.outbox import OutboxService

KEY_RE = re.compile(r"^[A-Za-z0-9._:-]{8,128}$")
IN_PROGRESS_RETRY_AFTER_S = 2


def ts(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


# --------------------------------------------------------------------------- serializers


def message_out(message: Message) -> dict[str, Any]:
    """Contract ``Message``."""
    failure = None
    if message.failure_code is not None:
        failure = {
            "code": message.failure_code,
            "detail": message.failure_detail,
            "retryable": bool(message.failure_retryable),
        }
    return {
        "id": str(message.id),
        "conversation_id": str(message.conversation_id),
        "direction": message.direction.value,
        "origin": message.origin.value,
        "text": message.text,
        "delivery_state": message.delivery_state.value,
        "external_message_id": message.external_message_id,
        "approval_id": str(message.approval_id) if message.approval_id else None,
        "attempt_count": message.attempt_count,
        "failure": failure,
        "created_at": ts(message.created_at),
        "sent_at": ts(message.sent_at),
        "delivered_at": ts(message.delivered_at),
    }


async def approval_out(
    session: AsyncSession, scope: UserScope, approval: Approval, message_id: uuid.UUID | None
) -> dict[str, Any]:
    """Contract ``Approval`` (with its stored draft)."""
    draft = await DraftRepository(session, scope).require(approval.draft_id)
    return {
        "id": str(approval.id),
        "conversation_id": str(approval.conversation_id),
        "contact_id": str(approval.contact_id),
        "status": approval.status.value,
        "draft": draft_to_contract(draft),
        "reasons": [r.value for r in approval.reasons],
        "final_text": approval.final_text,
        "rejection_reason": approval.rejection_reason,
        "message_id": str(message_id) if message_id else None,
        "created_at": ts(approval.created_at),
        "expires_at": ts(approval.expires_at),
        "decided_at": ts(approval.decided_at),
    }


def action_out(action: AgentAction) -> dict[str, Any]:
    """Contract ``AgentAction``."""
    return {
        "id": str(action.id),
        "type": action.type.value,
        "actor": action.actor.value,
        "conversation_id": str(action.conversation_id) if action.conversation_id else None,
        "contact_id": str(action.contact_id) if action.contact_id else None,
        "message_id": str(action.message_id) if action.message_id else None,
        "approval_id": str(action.approval_id) if action.approval_id else None,
        "reason_code": action.reason_code,
        "occurred_at": ts(action.occurred_at),
        "details": action.details,
    }


def instruction_summary_out(instruction: OutboxInstruction) -> dict[str, Any]:
    """Contract ``OutboxInstructionSummary`` (no text, no destination)."""
    return {
        "id": str(instruction.id),
        "connection_id": str(instruction.connection_id),
        "outbound_message_id": str(instruction.outbound_message_id),
        "idempotency_key": instruction.idempotency_key,
        "attempt": instruction.attempt,
        "state": instruction.state.value,
        "created_at": ts(instruction.created_at),
        "leased_at": ts(instruction.leased_at),
        "lease_expires_at": ts(instruction.lease_expires_at),
    }


# --------------------------------------------------------------------------- idempotency


def require_key(key: str | None) -> str:
    if key is None:
        raise ProblemException(
            400, ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header is required"
        )
    if not KEY_RE.fullmatch(key):
        raise ProblemException(
            400,
            ErrorCode.VALIDATION_ERROR,
            "Idempotency-Key must match ^[A-Za-z0-9._:-]{8,128}$",
            errors=[{"pointer": "header:Idempotency-Key", "message": "invalid format"}],
        )
    return key


@dataclass
class Slot:
    replay: JSONResponse | None = None
    _status: int | None = None
    _body: Any = None
    _record_id: uuid.UUID | None = None
    headers: dict[str, str] = field(default_factory=dict)

    def complete(self, status: int, body: Any) -> JSONResponse:
        self._status = status
        self._body = body
        return JSONResponse(body, status_code=status)


@asynccontextmanager
async def idempotent(
    session: AsyncSession,
    scope: UserScope,
    *,
    key: str | None,
    route: str,
    body: Any,
) -> AsyncIterator[Slot]:
    """ADR 0004 for one POST. Problems (4xx) raised inside are stored for replay too."""
    checked = require_key(key)
    repo = IdempotencyRepository(session, scope)
    claim = await repo.claim(
        key=checked, method="POST", route=route, request_hash=fingerprint(body)
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
    slot = Slot()
    if claim.outcome is ClaimOutcome.REPLAY:
        stored = claim.record.response_body
        status = claim.record.response_status or 200
        if isinstance(stored, dict) and stored.get("__problem__"):
            problem = stored["problem"]
            raise ProblemException(
                status,
                ErrorCode(problem["code"]),
                problem.get("detail"),
                errors=problem.get("errors"),
                headers={"Idempotent-Replayed": "true"},
            )
        slot.replay = JSONResponse(
            stored, status_code=status, headers={"Idempotent-Replayed": "true"}
        )
        yield slot
        return
    try:
        yield slot
    except ProblemException as exc:
        if 400 <= exc.status < 500 and exc.code not in (
            ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS,
            ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
        ):
            # Completed 4xx responses are stored and replayed (ADR 0004).
            await repo.complete(
                claim.record.id,
                status=exc.status,
                body={
                    "__problem__": True,
                    "problem": {"code": exc.code.value, "detail": exc.detail, "errors": exc.errors},
                },
            )
            await session.commit()
        raise
    if slot._status is not None:
        await repo.complete(claim.record.id, status=slot._status, body=slot._body)


# --------------------------------------------------------------------------- services


@dataclass
class AutomationServices:
    """The app's single dispatcher and the services built on it."""

    dispatcher: Dispatcher
    controller: AutomationController
    outbox: OutboxService
    ingest: IngestService


def build_services(
    registry: ConnectorRegistry | None = None,
    *,
    controller: AutomationController | None = None,
) -> AutomationServices:
    dispatcher = controller.dispatcher if controller is not None else Dispatcher(registry)
    if controller is None:
        controller = AutomationController(dispatcher)
    return AutomationServices(
        dispatcher=dispatcher,
        controller=controller,
        outbox=OutboxService(dispatcher),
        ingest=IngestService(dispatcher),
    )


def default_pipeline_controller(registry: ConnectorRegistry | None = None) -> AutomationController:
    """Controller for the running app: mock-or-configured LLM gateway, memory, analyzer,
    reply generator. Built lazily so an LLM misconfiguration cannot stop the app."""
    from app.ai.analyzer import ConversationAnalyzer
    from app.ai.llm_gateway import build_gateway
    from app.ai.memory import MemoryService
    from app.ai.reply_generator import ReplyGenerator

    gateway = build_gateway()
    memory = MemoryService(gateway)
    return AutomationController(
        Dispatcher(registry),
        generator=ReplyGenerator(gateway, memory=memory),
        analyzer=ConversationAnalyzer(gateway, memory=memory),
        memory=memory,
    )


async def get_services(request: Request) -> AutomationServices:
    """FastAPI dependency: one ``AutomationServices`` per app (lazy, see ``main``)."""
    services: AutomationServices | None = getattr(request.app.state, "automation", None)
    if services is None:
        services = build_services(controller=default_pipeline_controller())
        request.app.state.automation = services
    return services
