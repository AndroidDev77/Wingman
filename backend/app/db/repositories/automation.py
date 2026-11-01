"""Drafts, approvals, the device outbox and the connector-event ingest record (WING-011).

Like every repository here these are bound to a :class:`UserScope`, read through
``_select`` and flush without committing. Row locks (``FOR UPDATE`` / ``SKIP LOCKED``)
are taken where two writers can race: approving an approval, claiming an instruction.

Only ``app.services.dispatcher`` may call :meth:`OutboxRepository.create_instruction`
(ADR 0006: no API operation creates an instruction). ``tests/automation`` enforces it.
"""

import uuid
from collections.abc import Sequence
from datetime import datetime
from typing import Any

from sqlalchemy import update
from sqlalchemy.dialects.postgresql import insert

from app.db.base import utc_now
from app.db.enums import (
    ApprovalReason,
    ApprovalStatus,
    DraftDecision,
    IntentMode,
    OutboxInstructionState,
)
from app.db.models import Approval, ConnectorEventIngest, Draft, OutboxInstruction
from app.db.pagination import Page
from app.db.repositories.base import ScopedRepository

LIVE_INSTRUCTION_STATES = (OutboxInstructionState.PENDING, OutboxInstructionState.LEASED)
# An instruction whose outcome a device may report: it was leased at some point.
LEASED_EVER_STATES = (
    OutboxInstructionState.LEASED,
    OutboxInstructionState.COMPLETED,
    OutboxInstructionState.EXPIRED,
)


class DraftRepository(ScopedRepository[Draft]):
    model = Draft

    async def create(
        self,
        *,
        id: uuid.UUID,
        conversation_id: uuid.UUID,
        contact_id: uuid.UUID,
        decision: DraftDecision,
        text: str | None,
        question_for_user: str | None,
        rationale: str,
        confidence: float,
        approval_reasons: Sequence[ApprovalReason],
        intent_mode: IntentMode,
        grounding_memory_ids: Sequence[uuid.UUID],
        grounding_message_ids: Sequence[uuid.UUID],
        reason: str,
        created_at: datetime,
        trigger_message_id: uuid.UUID | None = None,
    ) -> Draft | None:
        """Store a draft. With ``trigger_message_id``, at most one draft per inbound
        message: a second insert for the same message returns ``None`` (nothing stored)."""
        values: dict[str, Any] = {
            "id": id,
            "user_id": self.scope.user_id,
            "conversation_id": conversation_id,
            "contact_id": contact_id,
            "decision": decision,
            "text": text,
            "question_for_user": question_for_user,
            "rationale": rationale,
            "confidence": confidence,
            "requires_approval": bool(approval_reasons),
            "approval_reasons": list(approval_reasons),
            "intent_mode": intent_mode,
            "grounding_memory_ids": list(grounding_memory_ids),
            "grounding_message_ids": list(grounding_message_ids),
            "reason": reason,
            "trigger_message_id": trigger_message_id,
            "created_at": created_at,
            "updated_at": created_at,
        }
        stmt = insert(Draft).values(**values)
        if trigger_message_id is not None:
            stmt = stmt.on_conflict_do_nothing(
                index_elements=[Draft.user_id, Draft.trigger_message_id],
                index_where=Draft.trigger_message_id.is_not(None),
            )
        row: Draft | None = await self.session.scalar(stmt.returning(Draft))
        return row

    async def for_trigger(self, message_id: uuid.UUID) -> Draft | None:
        row: Draft | None = await self.session.scalar(
            self._select().where(Draft.trigger_message_id == message_id)
        )
        return row


class ApprovalRepository(ScopedRepository[Approval]):
    model = Approval

    async def create(
        self,
        *,
        conversation_id: uuid.UUID,
        contact_id: uuid.UUID,
        draft_id: uuid.UUID,
        reasons: Sequence[ApprovalReason],
        expires_at: datetime | None,
        created_at: datetime,
    ) -> Approval:
        if not reasons:
            raise ValueError("an approval needs at least one reason")
        return await self._add(
            conversation_id=conversation_id,
            contact_id=contact_id,
            draft_id=draft_id,
            status=ApprovalStatus.PENDING,
            reasons=list(dict.fromkeys(reasons)),
            expires_at=expires_at,
            created_at=created_at,
            updated_at=created_at,
        )

    async def lock(self, approval_id: uuid.UUID) -> Approval | None:
        """The approval, row-locked for a decision (approve/reject/cancel)."""
        row: Approval | None = await self.session.scalar(
            self._select()
            .where(Approval.id == approval_id)
            .with_for_update(key_share=True)  # FOR NO KEY UPDATE: see UserRepository.lock
            .execution_options(populate_existing=True)
        )
        return row

    async def decide(self, approval_id: uuid.UUID, **values: Any) -> Approval:
        allowed = {"status", "final_text", "rejection_reason", "decided_at"}
        unknown = set(values) - allowed
        if unknown:
            raise ValueError(f"not an approval decision field: {sorted(unknown)}")
        return await self._update(approval_id, updated_at=utc_now(), **values)

    async def cancel_pending(
        self,
        *,
        at: datetime,
        conversation_id: uuid.UUID | None = None,
        contact_id: uuid.UUID | None = None,
    ) -> list[Approval]:
        """Cancel every pending approval of a conversation or contact. Returns them."""
        if (conversation_id is None) == (contact_id is None):
            raise ValueError("give exactly one of conversation_id or contact_id")
        stmt = update(Approval).where(
            Approval.user_id == self.scope.user_id, Approval.status == ApprovalStatus.PENDING
        )
        if conversation_id is not None:
            stmt = stmt.where(Approval.conversation_id == conversation_id)
        else:
            stmt = stmt.where(Approval.contact_id == contact_id)
        rows = await self.session.scalars(
            stmt.values(status=ApprovalStatus.CANCELED, decided_at=at, updated_at=at)
            .returning(Approval)
            .execution_options(synchronize_session="fetch")
        )
        return list(rows.all())

    async def expire_due(self, *, now: datetime) -> list[Approval]:
        rows = await self.session.scalars(
            update(Approval)
            .where(
                Approval.user_id == self.scope.user_id,
                Approval.status == ApprovalStatus.PENDING,
                Approval.expires_at.is_not(None),
                Approval.expires_at <= now,
            )
            .values(status=ApprovalStatus.EXPIRED, decided_at=now, updated_at=now)
            .returning(Approval)
            .execution_options(synchronize_session="fetch")
        )
        return list(rows.all())

    async def list(
        self,
        *,
        status: ApprovalStatus = ApprovalStatus.PENDING,
        conversation_id: uuid.UUID | None = None,
        contact_id: uuid.UUID | None = None,
        cursor: str | None = None,
        limit: int | None = None,
    ) -> Page[Approval]:
        """Newest first (``created_at`` DESC, ``id`` DESC). The cursor is bound to the filters."""
        stmt = self._select().where(Approval.status == status)
        if conversation_id is not None:
            stmt = stmt.where(Approval.conversation_id == conversation_id)
        if contact_id is not None:
            stmt = stmt.where(Approval.contact_id == contact_id)
        return await self._page(
            stmt,
            listing=f"approvals:{status.value}:{conversation_id}:{contact_id}",
            sort_column=Approval.created_at,
            descending=True,
            cursor=cursor,
            limit=limit,
        )


class OutboxRepository(ScopedRepository[OutboxInstruction]):
    """Device outbox (ADR 0006). Creation is reserved to the dispatcher."""

    model = OutboxInstruction

    async def create_instruction(
        self,
        *,
        connection_id: uuid.UUID,
        outbound_message_id: uuid.UUID,
        attempt: int,
        idempotency_key: str,
        created_at: datetime,
    ) -> OutboxInstruction:
        return await self._add(
            connection_id=connection_id,
            outbound_message_id=outbound_message_id,
            attempt=attempt,
            idempotency_key=idempotency_key,
            state=OutboxInstructionState.PENDING,
            created_at=created_at,
            updated_at=created_at,
        )

    async def live_for_message(self, message_id: uuid.UUID) -> OutboxInstruction | None:
        row: OutboxInstruction | None = await self.session.scalar(
            self._select().where(
                OutboxInstruction.outbound_message_id == message_id,
                OutboxInstruction.state.in_(LIVE_INSTRUCTION_STATES),
            )
        )
        return row

    async def find_leased(
        self,
        *,
        connection_id: uuid.UUID,
        outbound_message_id: uuid.UUID,
        idempotency_key: str | None,
    ) -> OutboxInstruction | None:
        """An instruction on this connection for this message (and key, when given) that
        was leased at some point (``leased``/``completed``/``expired``), row-locked."""
        stmt = self._select().where(
            OutboxInstruction.connection_id == connection_id,
            OutboxInstruction.outbound_message_id == outbound_message_id,
            OutboxInstruction.state.in_(LEASED_EVER_STATES),
        )
        if idempotency_key is not None:
            stmt = stmt.where(OutboxInstruction.idempotency_key == idempotency_key)
        row: OutboxInstruction | None = await self.session.scalar(
            stmt.order_by(OutboxInstruction.attempt.desc())
            .limit(1)
            .with_for_update(key_share=True)  # FOR NO KEY UPDATE: see UserRepository.lock
            .execution_options(populate_existing=True)
        )
        return row

    async def lock_pending(
        self, connection_id: uuid.UUID, *, limit: int
    ) -> list[OutboxInstruction]:
        """Oldest pending instructions, locked; rows another claim holds are skipped."""
        stmt = (
            self._select()
            .where(
                OutboxInstruction.connection_id == connection_id,
                OutboxInstruction.state == OutboxInstructionState.PENDING,
            )
            .order_by(OutboxInstruction.created_at, OutboxInstruction.id)
            .limit(limit)
            .with_for_update(skip_locked=True, key_share=True)
            .execution_options(populate_existing=True)
        )
        return list((await self.session.scalars(stmt)).all())

    async def own_leases(
        self, connection_id: uuid.UUID, device_install_id: uuid.UUID, *, now: datetime
    ) -> list[OutboxInstruction]:
        stmt = (
            self._select()
            .where(
                OutboxInstruction.connection_id == connection_id,
                OutboxInstruction.state == OutboxInstructionState.LEASED,
                OutboxInstruction.device_install_id == device_install_id,
                OutboxInstruction.lease_expires_at > now,
            )
            .order_by(OutboxInstruction.created_at, OutboxInstruction.id)
        )
        return list((await self.session.scalars(stmt)).all())

    async def set_state(self, instruction_id: uuid.UUID, **values: Any) -> OutboxInstruction:
        allowed = {"state", "device_install_id", "leased_at", "lease_expires_at", "completed_at"}
        unknown = set(values) - allowed
        if unknown:
            raise ValueError(f"not an instruction state field: {sorted(unknown)}")
        return await self._update(instruction_id, updated_at=utc_now(), **values)

    async def cancel_pending_for_messages(
        self, message_ids: Sequence[uuid.UUID], *, at: datetime
    ) -> int:
        if not message_ids:
            return 0
        rows = await self.session.scalars(
            update(OutboxInstruction)
            .where(
                OutboxInstruction.user_id == self.scope.user_id,
                OutboxInstruction.outbound_message_id.in_(list(message_ids)),
                OutboxInstruction.state == OutboxInstructionState.PENDING,
            )
            .values(state=OutboxInstructionState.CANCELED, updated_at=at)
            .returning(OutboxInstruction.id)
            .execution_options(synchronize_session="fetch")
        )
        return len(rows.all())

    async def cancel_pending_for_connection(self, connection_id: uuid.UUID, *, at: datetime) -> int:
        """Unpair (contract 1.2.0): every unclaimed instruction of the connection."""
        rows = await self.session.scalars(
            update(OutboxInstruction)
            .where(
                OutboxInstruction.user_id == self.scope.user_id,
                OutboxInstruction.connection_id == connection_id,
                OutboxInstruction.state == OutboxInstructionState.PENDING,
            )
            .values(state=OutboxInstructionState.CANCELED, updated_at=at)
            .returning(OutboxInstruction.id)
            .execution_options(synchronize_session="fetch")
        )
        return len(rows.all())

    async def expire_lapsed(self, *, now: datetime) -> list[OutboxInstruction]:
        """Leases past their deadline become ``expired``. Outcome unknown: nothing else
        changes (the message stays ``sending`` and is never retried automatically)."""
        rows = await self.session.scalars(
            update(OutboxInstruction)
            .where(
                OutboxInstruction.user_id == self.scope.user_id,
                OutboxInstruction.state == OutboxInstructionState.LEASED,
                OutboxInstruction.lease_expires_at <= now,
            )
            .values(state=OutboxInstructionState.EXPIRED, updated_at=now)
            .returning(OutboxInstruction)
            .execution_options(synchronize_session="fetch")
        )
        return list(rows.all())

    async def list(
        self,
        connection_id: uuid.UUID,
        *,
        state: OutboxInstructionState = OutboxInstructionState.PENDING,
        cursor: str | None = None,
        limit: int | None = None,
    ) -> Page[OutboxInstruction]:
        """Oldest first (``created_at``, ``id``)."""
        return await self._page(
            self._select().where(
                OutboxInstruction.connection_id == connection_id,
                OutboxInstruction.state == state,
            ),
            listing=f"outbox:{connection_id}:{state.value}",
            sort_column=OutboxInstruction.created_at,
            descending=False,
            cursor=cursor,
            limit=limit,
        )


class EventIngestRepository(ScopedRepository[ConnectorEventIngest]):
    """Permanent ``(user_id, event_id)`` dedup record for ``ingestConnectorEvent``."""

    model = ConnectorEventIngest

    async def record(
        self,
        *,
        event_id: uuid.UUID,
        connection_id: uuid.UUID,
        event_type: str,
        request_hash: str,
        outcome: str,
        occurred_at: datetime,
        message_id: uuid.UUID | None = None,
        deferred: bool = False,
        at: datetime | None = None,
    ) -> ConnectorEventIngest | None:
        """Insert the record, or return ``None`` if this ``event_id`` was already seen.

        A concurrent insert of the same ``event_id`` blocks on the unique index until the
        first transaction ends, then sees the conflict.
        """
        now = at or utc_now()
        row: ConnectorEventIngest | None = await self.session.scalar(
            insert(ConnectorEventIngest)
            .values(
                user_id=self.scope.user_id,
                event_id=event_id,
                connection_id=connection_id,
                event_type=event_type,
                request_hash=request_hash,
                outcome=outcome,
                occurred_at=occurred_at,
                message_id=message_id,
                deferred=deferred,
                created_at=now,
                updated_at=now,
            )
            .on_conflict_do_nothing(
                index_elements=[ConnectorEventIngest.user_id, ConnectorEventIngest.event_id]
            )
            .returning(ConnectorEventIngest)
        )
        return row

    async def get_by_event_id(self, event_id: uuid.UUID) -> ConnectorEventIngest | None:
        row: ConnectorEventIngest | None = await self.session.scalar(
            self._select().where(ConnectorEventIngest.event_id == event_id)
        )
        return row

    async def annotate(self, record_id: uuid.UUID, **values: Any) -> ConnectorEventIngest:
        allowed = {"outcome", "message_id", "deferred"}
        unknown = set(values) - allowed
        if unknown:
            raise ValueError(f"not an ingest record field: {sorted(unknown)}")
        return await self._update(record_id, updated_at=utc_now(), **values)

    async def deferred_receipts(self, message_id: uuid.UUID) -> list[ConnectorEventIngest]:
        stmt = (
            self._select()
            .where(
                ConnectorEventIngest.message_id == message_id,
                ConnectorEventIngest.deferred.is_(True),
            )
            .order_by(ConnectorEventIngest.occurred_at, ConnectorEventIngest.id)
        )
        return list((await self.session.scalars(stmt)).all())
