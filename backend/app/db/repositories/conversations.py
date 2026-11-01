"""Conversations and messages, including idempotent inbound ingest."""

import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from sqlalchemy import ColumnElement, func, update
from sqlalchemy.dialects.postgresql import insert

from app.db.base import utc_now
from app.db.enums import ConversationStage, DeliveryState, MessageDirection, MessageOrigin
from app.db.models import Conversation, Message
from app.db.pagination import Page
from app.db.repositories.base import ScopedRepository

PREVIEW_LEN = 160


class ConversationRepository(ScopedRepository[Conversation]):
    model = Conversation

    async def create(
        self,
        *,
        contact_id: uuid.UUID,
        connection_id: uuid.UUID,
        platform: str,
        external_thread_id: str | None = None,
        stage: ConversationStage = ConversationStage.NEW,
        last_activity_at: datetime | None = None,
    ) -> Conversation:
        return await self._add(
            contact_id=contact_id,
            connection_id=connection_id,
            platform=platform,
            external_thread_id=external_thread_id,
            stage=stage,
            last_activity_at=last_activity_at or utc_now(),
        )

    async def get_by_external_thread(
        self, connection_id: uuid.UUID, external_thread_id: str
    ) -> Conversation | None:
        row: Conversation | None = await self.session.scalar(
            self._select().where(
                Conversation.connection_id == connection_id,
                Conversation.external_thread_id == external_thread_id,
            )
        )
        return row

    async def get_or_create_for_thread(
        self,
        *,
        contact_id: uuid.UUID,
        connection_id: uuid.UUID,
        platform: str,
        external_thread_id: str | None,
        last_activity_at: datetime,
    ) -> Conversation:
        """The conversation for this thread (or, without a thread ID, for this contact),
        created on first sight. Safe under concurrent first messages on one thread."""
        if external_thread_id is None:
            existing = await self.get_for_contact(contact_id)
            if existing is not None:
                return existing
            return await self.create(
                contact_id=contact_id,
                connection_id=connection_id,
                platform=platform,
                last_activity_at=last_activity_at,
            )
        stmt = (
            insert(Conversation)
            .values(
                user_id=self.scope.user_id,
                contact_id=contact_id,
                connection_id=connection_id,
                platform=platform,
                external_thread_id=external_thread_id,
                stage=ConversationStage.NEW,
                last_activity_at=last_activity_at,
            )
            .on_conflict_do_nothing(
                index_elements=[
                    Conversation.user_id,
                    Conversation.connection_id,
                    Conversation.external_thread_id,
                ],
                index_where=Conversation.external_thread_id.is_not(None),
            )
            .returning(Conversation)
        )
        created: Conversation | None = await self.session.scalar(stmt)
        if created is not None:
            return created
        found = await self.get_by_external_thread(connection_id, external_thread_id)
        if found is None:  # pragma: no cover - only if deleted concurrently
            raise LookupError("conversation vanished during get_or_create")
        return found

    async def for_connection(self, connection_id: uuid.UUID) -> list[Conversation]:
        """Every conversation on one connection (contract 1.2.0 unpair)."""
        stmt = (
            self._select()
            .where(Conversation.connection_id == connection_id)
            .order_by(Conversation.created_at, Conversation.id)
        )
        return list((await self.session.scalars(stmt)).all())

    async def get_for_contact(self, contact_id: uuid.UUID) -> Conversation | None:
        """Oldest conversation with this contact (one per contact in v1)."""
        row: Conversation | None = await self.session.scalar(
            self._select()
            .where(Conversation.contact_id == contact_id)
            .order_by(Conversation.created_at, Conversation.id)
            .limit(1)
        )
        return row

    async def list_inbox(
        self,
        *,
        cursor: str | None = None,
        limit: int | None = None,
        platform: str | None = None,
        stage: ConversationStage | None = None,
        conditions: Sequence[ColumnElement[bool]] = (),
        filter_tag: str = "",
    ) -> Page[Conversation]:
        """Unified inbox: ``last_activity_at`` DESC, then ``id`` DESC.

        ``intent`` and ``automation_mode`` filters depend on effective preferences; the
        service layer (``app.services.conversations``) builds them as extra
        ``conditions`` on ``Conversation`` and names them in ``filter_tag``. The cursor
        is bound to every filter (ADR 0003): replaying it with other filters is an
        invalid cursor.
        """
        stmt = self._select()
        if platform is not None:
            stmt = stmt.where(Conversation.platform == platform)
        if stage is not None:
            stmt = stmt.where(Conversation.stage == stage)
        for condition in conditions:
            stmt = stmt.where(condition)
        listing = f"inbox:{platform or ''}:{stage or ''}:{filter_tag}"
        return await self._page(
            stmt,
            listing=listing,
            sort_column=Conversation.last_activity_at,
            descending=True,
            cursor=cursor,
            limit=limit,
        )

    async def set_stage(self, conversation_id: uuid.UUID, stage: ConversationStage) -> Conversation:
        return await self._update(conversation_id, stage=stage, updated_at=utc_now())

    async def set_analysis(
        self, conversation_id: uuid.UUID, analysis: dict[str, Any], stage: ConversationStage
    ) -> Conversation:
        """Store the latest advisory ``ConversationAnalysis`` (WING-009)."""
        return await self._update(
            conversation_id, analysis=analysis, stage=stage, updated_at=utc_now()
        )

    async def mark_read(self, conversation_id: uuid.UUID) -> Conversation:
        return await self._update(conversation_id, unread_count=0, updated_at=utc_now())


@dataclass(frozen=True, slots=True)
class IngestResult:
    message: Message
    created: bool


class MessageRepository(ScopedRepository[Message]):
    model = Message

    async def ingest_inbound(
        self,
        *,
        conversation_id: uuid.UUID,
        platform: str,
        external_message_id: str,
        text: str,
        sent_at_source: datetime,
    ) -> IngestResult:
        """Store an inbound message exactly once per ``(user, platform, external_message_id)``.

        A duplicate returns the existing row with ``created=False`` and changes nothing
        (no second row, no unread/preview bump). The conversation must belong to this
        user and be on ``platform`` (composite FK), else ``IntegrityError``.
        """
        stmt = (
            insert(Message)
            .values(
                user_id=self.scope.user_id,
                conversation_id=conversation_id,
                platform=platform,
                direction=MessageDirection.INBOUND,
                origin=MessageOrigin.CONTACT,
                text=text,
                delivery_state=DeliveryState.RECEIVED,
                external_message_id=external_message_id,
                created_at=sent_at_source,
            )
            .on_conflict_do_nothing(
                index_elements=[Message.user_id, Message.platform, Message.external_message_id]
            )
            .returning(Message)
        )
        created: Message | None = await self.session.scalar(stmt)
        if created is None:
            existing = await self.get_by_external_id(platform, external_message_id)
            if existing is None:  # pragma: no cover - only if deleted concurrently
                raise LookupError("message vanished during ingest")
            return IngestResult(message=existing, created=False)
        await self.session.execute(
            update(Conversation)
            .where(
                Conversation.user_id == self.scope.user_id,
                Conversation.id == conversation_id,
            )
            .values(
                unread_count=Conversation.unread_count + 1,
                last_message_preview=text[:PREVIEW_LEN],
                last_activity_at=func.greatest(Conversation.last_activity_at, sent_at_source),
                updated_at=utc_now(),
            )
        )
        return IngestResult(message=created, created=True)

    async def get_by_external_id(self, platform: str, external_message_id: str) -> Message | None:
        row: Message | None = await self.session.scalar(
            self._select().where(
                Message.platform == platform,
                Message.external_message_id == external_message_id,
            )
        )
        return row

    async def create_outbound(
        self,
        *,
        conversation_id: uuid.UUID,
        platform: str,
        origin: MessageOrigin,
        text: str,
        approval_id: uuid.UUID | None = None,
        send_after: datetime | None = None,
        created_at: datetime | None = None,
    ) -> Message:
        """Queue an outbound message (dispatcher only, WING-011)."""
        extra: dict[str, Any] = {}
        if created_at is not None:
            extra = {"created_at": created_at, "updated_at": created_at}
        return await self._add(
            conversation_id=conversation_id,
            platform=platform,
            direction=MessageDirection.OUTBOUND,
            origin=origin,
            text=text,
            delivery_state=DeliveryState.QUEUED,
            approval_id=approval_id,
            send_after=send_after,
            **extra,
        )

    async def lock(self, message_id: uuid.UUID) -> Message | None:
        """The message, row-locked (``FOR UPDATE``) and refreshed from the database.

        The dispatcher holds this lock across one transport attempt, so a concurrent
        dispatch of the same message waits and then sees the new state.
        """
        row: Message | None = await self.session.scalar(
            self._select()
            .where(Message.id == message_id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
        return row

    async def get_by_approval(self, approval_id: uuid.UUID) -> Message | None:
        row: Message | None = await self.session.scalar(
            self._select().where(Message.approval_id == approval_id)
        )
        return row

    async def queued_automated(
        self, *, contact_id: uuid.UUID | None, origins: Sequence[MessageOrigin]
    ) -> list[Message]:
        """Queued outbound messages with one of ``origins`` (optionally one contact's)."""
        stmt = self._select().where(
            Message.direction == MessageDirection.OUTBOUND,
            Message.delivery_state == DeliveryState.QUEUED,
            Message.origin.in_(list(origins)),
        )
        if contact_id is not None:
            stmt = stmt.join(
                Conversation,
                (Conversation.user_id == Message.user_id)
                & (Conversation.id == Message.conversation_id),
            ).where(Conversation.contact_id == contact_id)
        return list((await self.session.scalars(stmt.order_by(Message.created_at))).all())

    async def outbound_for_connection(
        self, connection_id: uuid.UUID, *, states: Sequence[DeliveryState]
    ) -> list[tuple[Message, uuid.UUID]]:
        """Outbound messages of one connection in ``states``, with their contact id."""
        stmt = (
            self._select()
            .add_columns(Conversation.contact_id)
            .join(
                Conversation,
                (Conversation.user_id == Message.user_id)
                & (Conversation.id == Message.conversation_id),
            )
            .where(
                Conversation.connection_id == connection_id,
                Message.direction == MessageDirection.OUTBOUND,
                Message.delivery_state.in_(list(states)),
            )
            .order_by(Message.created_at, Message.id)
        )
        rows = await self.session.execute(stmt)
        return [(r[0], r[1]) for r in rows.all()]

    async def due_outbound(
        self, *, now: datetime, limit: int = 50, connection_id: uuid.UUID | None = None
    ) -> list[Message]:
        """Queued outbound messages whose ``send_after`` has passed, oldest first."""
        stmt = self._select().where(
            Message.direction == MessageDirection.OUTBOUND,
            Message.delivery_state == DeliveryState.QUEUED,
            (Message.send_after.is_(None)) | (Message.send_after <= now),
        )
        if connection_id is not None:
            stmt = stmt.join(
                Conversation,
                (Conversation.user_id == Message.user_id)
                & (Conversation.id == Message.conversation_id),
            ).where(Conversation.connection_id == connection_id)
        stmt = stmt.order_by(Message.created_at, Message.id).limit(limit)
        return list((await self.session.scalars(stmt)).all())

    async def count_automated_since(self, since: datetime) -> int:
        """Automated sends (autopilot) queued at or after ``since`` and not canceled."""
        rows = await self.session.scalars(
            self._select().where(
                Message.direction == MessageDirection.OUTBOUND,
                Message.origin == MessageOrigin.AUTOPILOT,
                Message.created_at >= since,
                Message.delivery_state.not_in([DeliveryState.CANCELED]),
            )
        )
        return len(rows.all())

    async def latest_outbound(self, conversation_id: uuid.UUID) -> Message | None:
        row: Message | None = await self.session.scalar(
            self._select()
            .where(
                Message.conversation_id == conversation_id,
                Message.direction == MessageDirection.OUTBOUND,
                Message.delivery_state.not_in([DeliveryState.CANCELED]),
            )
            .order_by(Message.created_at.desc(), Message.id.desc())
            .limit(1)
        )
        return row

    async def update_delivery(self, message_id: uuid.UUID, **values: Any) -> Message:
        """Scoped update of delivery fields (``delivery_state``, ``sent_at``, ``failure_*``,
        ``attempt_count``, ``external_message_id``). CHECK constraints reject any
        combination the contract forbids, e.g. ``delivered`` without ``sent_at``."""
        allowed = {
            "delivery_state",
            "external_message_id",
            "attempt_count",
            "failure_code",
            "failure_detail",
            "failure_retryable",
            "sent_at",
            "delivered_at",
            "send_after",
        }
        unknown = set(values) - allowed
        if unknown:
            raise ValueError(f"not a delivery field: {sorted(unknown)}")
        return await self._update(message_id, updated_at=utc_now(), **values)

    async def list_thread(
        self,
        conversation_id: uuid.UUID,
        *,
        cursor: str | None = None,
        limit: int | None = None,
    ) -> Page[Message]:
        """Chronological: ``created_at`` ASC, then ``id`` ASC."""
        return await self._page(
            self._select().where(Message.conversation_id == conversation_id),
            listing=f"thread:{conversation_id}",
            sort_column=Message.created_at,
            descending=False,
            cursor=cursor,
            limit=limit,
        )
