"""Memories, audit log, automation rules and date proposals."""

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import ColumnElement, or_

from app.db.base import utc_now
from app.db.enums import Actor, AgentActionType, DateProposalState
from app.db.models import AgentAction, AutomationRule, ConversationMemory, DateProposal
from app.db.pagination import Page
from app.db.repositories.base import ScopedRepository


class MemoryRepository(ScopedRepository[ConversationMemory]):
    model = ConversationMemory

    async def add(
        self,
        *,
        contact_id: uuid.UUID,
        content: str,
        confidence: float,
        source_message_ids: list[uuid.UUID],
        kind: str = "fact",
        category: str | None = None,
        conversation_id: uuid.UUID | None = None,
        expires_at: datetime | None = None,
    ) -> ConversationMemory:
        return await self._add(
            contact_id=contact_id,
            conversation_id=conversation_id,
            kind=kind,
            category=category,
            content=content,
            confidence=confidence,
            source_message_ids=list(source_message_ids),
            expires_at=expires_at,
        )

    async def active_for_contact(
        self, contact_id: uuid.UUID, *, now: datetime | None = None, limit: int = 50
    ) -> list[ConversationMemory]:
        """Facts usable for grounding: not deleted, not superseded, not expired. Newest first."""
        at = now or utc_now()
        stmt = (
            self._select()
            .where(
                ConversationMemory.contact_id == contact_id,
                ConversationMemory.deleted_at.is_(None),
                ConversationMemory.superseded_by_id.is_(None),
                or_(ConversationMemory.expires_at.is_(None), ConversationMemory.expires_at > at),
            )
            .order_by(ConversationMemory.created_at.desc(), ConversationMemory.id.desc())
            .limit(limit)
        )
        return list((await self.session.scalars(stmt)).all())

    async def supersede(
        self, memory_id: uuid.UUID, replacement_id: uuid.UUID
    ) -> ConversationMemory:
        await self.require(replacement_id)
        return await self._update(memory_id, superseded_by_id=replacement_id, updated_at=utc_now())

    async def soft_delete(self, memory_id: uuid.UUID) -> ConversationMemory:
        now = utc_now()
        return await self._update(memory_id, deleted_at=now, updated_at=now)


class AgentActionRepository(ScopedRepository[AgentAction]):
    """Append-only: there is intentionally no update or delete method."""

    model = AgentAction

    async def record(
        self,
        *,
        type: AgentActionType,
        actor: Actor,
        details: dict[str, Any] | None = None,
        conversation_id: uuid.UUID | None = None,
        contact_id: uuid.UUID | None = None,
        message_id: uuid.UUID | None = None,
        approval_id: uuid.UUID | None = None,
        reason_code: str | None = None,
        occurred_at: datetime | None = None,
    ) -> AgentAction:
        """Append an audit entry. ``details`` must already be redacted (no message text)."""
        return await self._add(
            type=type,
            actor=actor,
            details=dict(details or {}),
            conversation_id=conversation_id,
            contact_id=contact_id,
            message_id=message_id,
            approval_id=approval_id,
            reason_code=reason_code,
            occurred_at=occurred_at or utc_now(),
        )

    async def list(
        self,
        *,
        cursor: str | None = None,
        limit: int | None = None,
        conversation_id: uuid.UUID | None = None,
        contact_id: uuid.UUID | None = None,
        type: AgentActionType | None = None,
    ) -> Page[AgentAction]:
        """Newest first: ``occurred_at`` DESC, ``id`` DESC."""
        stmt = self._select()
        if conversation_id is not None:
            stmt = stmt.where(AgentAction.conversation_id == conversation_id)
        if contact_id is not None:
            stmt = stmt.where(AgentAction.contact_id == contact_id)
        if type is not None:
            stmt = stmt.where(AgentAction.type == type)
        return await self._page(
            stmt,
            listing="audit",
            sort_column=AgentAction.occurred_at,
            descending=True,
            cursor=cursor,
            limit=limit,
        )


class AutomationRuleRepository(ScopedRepository[AutomationRule]):
    model = AutomationRule

    async def create(
        self,
        *,
        name: str,
        action: str,
        conditions: dict[str, Any] | None = None,
        contact_id: uuid.UUID | None = None,
        priority: int = 0,
        enabled: bool = True,
    ) -> AutomationRule:
        return await self._add(
            name=name,
            action=action,
            conditions=dict(conditions or {}),
            contact_id=contact_id,
            priority=priority,
            enabled=enabled,
        )

    async def applicable(self, contact_id: uuid.UUID | None) -> list[AutomationRule]:
        """Enabled rules for this contact plus user-wide rules, by ``priority`` then ``id``."""
        cond: ColumnElement[bool] = AutomationRule.contact_id.is_(None)
        if contact_id is not None:
            cond = or_(cond, AutomationRule.contact_id == contact_id)
        stmt = (
            self._select()
            .where(AutomationRule.enabled.is_(True), cond)
            .order_by(AutomationRule.priority, AutomationRule.id)
        )
        return list((await self.session.scalars(stmt)).all())

    async def set_enabled(self, rule_id: uuid.UUID, enabled: bool) -> AutomationRule:
        return await self._update(rule_id, enabled=enabled, updated_at=utc_now())


class DateProposalRepository(ScopedRepository[DateProposal]):
    model = DateProposal

    async def create(
        self,
        *,
        conversation_id: uuid.UUID,
        state: DateProposalState = DateProposalState.SUGGESTED,
        proposed_start: datetime | None = None,
        proposed_end: datetime | None = None,
        venue_text: str | None = None,
    ) -> DateProposal:
        return await self._add(
            conversation_id=conversation_id,
            state=state,
            proposed_start=proposed_start,
            proposed_end=proposed_end,
            venue_text=venue_text,
        )

    async def update(self, proposal_id: uuid.UUID, **values: Any) -> DateProposal:
        """Scoped update. The DB rejects ``confirmed`` without evidence (CHECK)."""
        return await self._update(proposal_id, updated_at=utc_now(), **values)

    async def list_for_conversation(self, conversation_id: uuid.UUID) -> list[DateProposal]:
        stmt = (
            self._select()
            .where(DateProposal.conversation_id == conversation_id)
            .order_by(DateProposal.created_at, DateProposal.id)
        )
        return list((await self.session.scalars(stmt)).all())
