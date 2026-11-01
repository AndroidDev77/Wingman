"""Conversation read model (I-021): the contract ``Conversation``, ``ConversationDetail``
and ``Message`` pages, composed from stored state. Read-only: nothing here writes,
sends, or changes delivery or automation state.

* ``effective_intent`` and ``automation.effective_mode`` follow WING-007 precedence:
  the contact's latest override version when it sets the field, else the user-wide
  preferences, else the safe defaults (``open`` / ``manual``), exactly as
  :func:`app.services.preferences.merge_effective` computes them.
* ``automation.contact_status`` / ``global_status`` are the stored pause/takeover state.
* ``pending_approval_count`` counts this conversation's ``pending`` approvals.
* ``analysis`` is the stored advisory ``ConversationAnalysis`` (WING-009), or null.
  It never authorizes anything.
* Messages report delivery exactly as stored: the dispatcher only advances state on
  real connector events, and DB CHECKs forbid ``sent_at``/``delivered_at`` on
  ``queued``/``sending``/``canceled`` (``app.db.models.Message``).

Another user's conversation is :class:`app.db.errors.NotFoundError` (``404``).
"""

import uuid
from collections.abc import Sequence
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import ColumnElement, ScalarSelect, func, literal, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import (
    ApprovalStatus,
    AutomationMode,
    ConversationStage,
    GlobalAutomationStatus,
    IntentMode,
)
from app.db.errors import NotFoundError
from app.db.models import Approval, Contact, Conversation, Message, UserPreference
from app.db.pagination import Page
from app.db.repositories import ConversationRepository, MessageRepository, UserRepository
from app.db.scope import UserScope
from app.services.preferences import PreferencesService

__all__ = ["ConversationReadService", "message_to_contract"]


def _latest_override(column: Any) -> ScalarSelect[Any]:
    """``column`` of the contact's latest preference-override version (null means
    "inherit"), correlated to the outer, already user-scoped ``conversations`` row."""
    return (
        select(column)
        .where(
            UserPreference.user_id == Conversation.user_id,
            UserPreference.contact_id == Conversation.contact_id,
        )
        .order_by(UserPreference.version.desc())
        .limit(1)
        .correlate(Conversation)
        .scalar_subquery()
    )


def _ts(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def message_to_contract(message: Message) -> dict[str, Any]:
    """Contract ``Message``. Timestamps only as stored (never inferred from state)."""
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
        "created_at": _ts(message.created_at),
        "sent_at": _ts(message.sent_at),
        "delivered_at": _ts(message.delivered_at),
    }


class ConversationReadService:
    """User-scoped reads for ``listConversations``, ``getConversation``, ``listMessages``."""

    def __init__(self, session: AsyncSession, scope: UserScope) -> None:
        self.session = session
        self.scope = scope
        self._conversations = ConversationRepository(session, scope)

    # ------------------------------------------------------------------ public

    async def list_conversations(
        self,
        *,
        cursor: str | None,
        limit: int,
        platform: str | None = None,
        stage: ConversationStage | None = None,
        intent: IntentMode | None = None,
        automation_mode: AutomationMode | None = None,
    ) -> dict[str, Any]:
        """``ConversationPage``. Raises ``InvalidCursorError`` on a bad/mismatched cursor."""
        user_wide = await PreferencesService(self.session, self.scope).get_user_preferences()
        conditions: list[ColumnElement[bool]] = []
        if intent is not None:
            effective_intent = func.coalesce(
                _latest_override(UserPreference.intent_mode),
                literal(user_wide.intent.mode, UserPreference.intent_mode.type),
            )
            conditions.append(effective_intent == literal(intent, UserPreference.intent_mode.type))
        if automation_mode is not None:
            effective_mode = func.coalesce(
                _latest_override(UserPreference.automation_mode),
                literal(user_wide.automation.mode, UserPreference.automation_mode.type),
            )
            conditions.append(
                effective_mode == literal(automation_mode, UserPreference.automation_mode.type)
            )
        page = await self._conversations.list_inbox(
            cursor=cursor,
            limit=limit,
            platform=platform,
            stage=stage,
            conditions=conditions,
            filter_tag=f"{intent or ''}:{automation_mode or ''}",
        )
        items = await self._compose(page.items, user_wide.intent.mode, user_wide.automation.mode)
        return {"items": items, "next_cursor": page.next_cursor}

    async def get_conversation(self, conversation_id: uuid.UUID) -> dict[str, Any]:
        """``ConversationDetail``. Raises ``NotFoundError`` outside the scope."""
        conversation = await self._conversations.get(conversation_id)
        if conversation is None:
            raise NotFoundError("conversation not found")
        user_wide = await PreferencesService(self.session, self.scope).get_user_preferences()
        [item] = await self._compose(
            [conversation], user_wide.intent.mode, user_wide.automation.mode
        )
        item["analysis"] = conversation.analysis
        return item

    async def list_messages(
        self, conversation_id: uuid.UUID, *, cursor: str | None, limit: int
    ) -> dict[str, Any]:
        """``MessagePage``, oldest first. ``NotFoundError`` before any cursor check, so a
        foreign conversation is always ``404``; a cursor from another conversation or
        listing raises ``InvalidCursorError``."""
        if await self._conversations.get(conversation_id) is None:
            raise NotFoundError("conversation not found")
        page: Page[Message] = await MessageRepository(self.session, self.scope).list_thread(
            conversation_id, cursor=cursor, limit=limit
        )
        return {
            "items": [message_to_contract(m) for m in page.items],
            "next_cursor": page.next_cursor,
        }

    # ------------------------------------------------------------------ composition

    async def _compose(
        self,
        conversations: Sequence[Conversation],
        default_intent: IntentMode,
        default_mode: AutomationMode,
    ) -> list[dict[str, Any]]:
        if not conversations:
            return []
        contact_ids = {c.contact_id for c in conversations}
        conversation_ids = [c.id for c in conversations]
        contacts = await self._contacts(contact_ids)
        overrides = await self._latest_overrides(contact_ids)
        pending = await self._pending_counts(conversation_ids)
        user = await UserRepository(self.session).get(self.scope)
        global_status = (
            user.global_automation_status if user is not None else GlobalAutomationStatus.ACTIVE
        )
        out = []
        for c in conversations:
            contact = contacts[c.contact_id]
            o_intent, o_mode = overrides.get(c.contact_id, (None, None))
            out.append(
                {
                    "id": str(c.id),
                    "contact": {"id": str(contact.id), "display_name": contact.display_name},
                    "connection_id": str(c.connection_id),
                    "platform": c.platform,
                    "external_thread_id": c.external_thread_id,
                    "stage": c.stage.value,
                    "effective_intent": (o_intent or default_intent).value,
                    "automation": {
                        "effective_mode": (o_mode or default_mode).value,
                        "contact_status": contact.automation_status.value,
                        "global_status": global_status.value,
                    },
                    "unread_count": c.unread_count,
                    "pending_approval_count": pending.get(c.id, 0),
                    "last_message_preview": c.last_message_preview,
                    "last_activity_at": _ts(c.last_activity_at),
                    "created_at": _ts(c.created_at),
                }
            )
        return out

    async def _contacts(self, ids: set[uuid.UUID]) -> dict[uuid.UUID, Contact]:
        rows = await self.session.scalars(
            select(Contact).where(Contact.user_id == self.scope.user_id, Contact.id.in_(ids))
        )
        return {r.id: r for r in rows}

    async def _latest_overrides(
        self, contact_ids: set[uuid.UUID]
    ) -> dict[uuid.UUID, tuple[IntentMode | None, AutomationMode | None]]:
        """Each contact's latest override version: (intent_mode, automation_mode), where
        null means "inherit the user-wide value"."""
        latest = (
            select(
                UserPreference.contact_id.label("contact_id"),
                func.max(UserPreference.version).label("version"),
            )
            .where(
                UserPreference.user_id == self.scope.user_id,
                UserPreference.contact_id.in_(contact_ids),
            )
            .group_by(UserPreference.contact_id)
            .subquery()
        )
        stmt = (
            select(
                UserPreference.contact_id,
                UserPreference.intent_mode,
                UserPreference.automation_mode,
            )
            .join(
                latest,
                (UserPreference.contact_id == latest.c.contact_id)
                & (UserPreference.version == latest.c.version),
            )
            .where(UserPreference.user_id == self.scope.user_id)
        )
        rows = await self.session.execute(stmt)
        return {r[0]: (r[1], r[2]) for r in rows.all() if r[0] is not None}

    async def _pending_counts(self, conversation_ids: list[uuid.UUID]) -> dict[uuid.UUID, int]:
        stmt = (
            select(Approval.conversation_id, func.count())
            .where(
                Approval.user_id == self.scope.user_id,
                Approval.conversation_id.in_(conversation_ids),
                Approval.status == ApprovalStatus.PENDING,
            )
            .group_by(Approval.conversation_id)
        )
        rows = await self.session.execute(stmt)
        return {r[0]: int(r[1]) for r in rows.all()}
