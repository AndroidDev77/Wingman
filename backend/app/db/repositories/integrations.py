"""Contract 1.3.0 (ADR 0008): inbound notices, assisted deliveries, identity links,
profiles, evaluations and profile actions.

Scoped like every repository: reads go through ``_select``, writes stamp the scope's
``user_id`` and flush without committing. Only ``app.services.dispatcher`` creates an
assisted delivery (``tests/automation/test_architecture.py``).
"""

import uuid
from collections.abc import Sequence
from datetime import datetime
from typing import Any

from sqlalchemy import delete, or_, update
from sqlalchemy.dialects.postgresql import distinct_on, insert

from app.db.base import utc_now
from app.db.enums import (
    ApprovalReason,
    AssistedDeliveryStatus,
    ConnectionTransport,
    InboundEventKind,
    IntentMode,
    ProfileActionExecution,
    ProfileDecision,
    ProfileProvider,
    ProfileSource,
    SenderIdentity,
)
from app.db.models import (
    AssistedDelivery,
    ConversationProfileLink,
    IdentityLink,
    InboundNotice,
    Profile,
    ProfileAction,
    ProfileEvaluation,
)
from app.db.pagination import Page
from app.db.repositories.base import ScopedRepository


class InboundNoticeRepository(ScopedRepository[InboundNotice]):
    model = InboundNotice

    async def get_by_external_id(
        self, platform: str, external_message_id: str
    ) -> InboundNotice | None:
        row: InboundNotice | None = await self.session.scalar(
            self._select().where(
                InboundNotice.platform == platform,
                InboundNotice.external_message_id == external_message_id,
            )
        )
        return row

    async def create_once(
        self,
        *,
        connection_id: uuid.UUID,
        platform: str,
        event_id: uuid.UUID,
        kind: InboundEventKind,
        identity: SenderIdentity,
        sender_label: str | None,
        text: str,
        truncated: bool,
        content_hidden: bool,
        external_message_id: str,
        external_conversation_id: str | None,
        source: ConnectionTransport | None,
        occurred_at: datetime,
    ) -> tuple[InboundNotice, bool]:
        """Store a notice once per ``(user, platform, external_message_id)``."""
        stmt = (
            insert(InboundNotice)
            .values(
                user_id=self.scope.user_id,
                connection_id=connection_id,
                platform=platform,
                event_id=event_id,
                kind=kind,
                identity=identity,
                sender_label=sender_label,
                text=text,
                truncated=truncated,
                content_hidden=content_hidden,
                external_message_id=external_message_id,
                external_conversation_id=external_conversation_id,
                source=source,
                occurred_at=occurred_at,
            )
            .on_conflict_do_nothing(
                index_elements=[
                    InboundNotice.user_id,
                    InboundNotice.platform,
                    InboundNotice.external_message_id,
                ]
            )
            .returning(InboundNotice)
        )
        created: InboundNotice | None = await self.session.scalar(stmt)
        if created is not None:
            return created, True
        existing = await self.get_by_external_id(platform, external_message_id)
        if existing is None:  # pragma: no cover - deleted concurrently
            raise LookupError("notice vanished during create_once")
        return existing, False

    async def list(
        self,
        *,
        connection_id: uuid.UUID | None = None,
        cursor: str | None = None,
        limit: int | None = None,
    ) -> Page[InboundNotice]:
        stmt = self._select()
        listing = "inbound_notices"
        if connection_id is not None:
            stmt = stmt.where(InboundNotice.connection_id == connection_id)
            listing += f":{connection_id}"
        return await self._page(
            stmt,
            listing=listing,
            sort_column=InboundNotice.occurred_at,
            descending=True,
            cursor=cursor,
            limit=limit,
        )


class AssistedDeliveryRepository(ScopedRepository[AssistedDelivery]):
    model = AssistedDelivery

    async def create(
        self,
        *,
        conversation_id: uuid.UUID,
        contact_id: uuid.UUID,
        connection_id: uuid.UUID,
        draft_id: uuid.UUID,
        text: str,
        reasons: Sequence[ApprovalReason],
        created_at: datetime,
    ) -> AssistedDelivery:
        """Dispatcher only: a pending draft-only delivery for one conversation."""
        return await self._add(
            conversation_id=conversation_id,
            contact_id=contact_id,
            connection_id=connection_id,
            draft_id=draft_id,
            text=text,
            reasons=list(reasons),
            status=AssistedDeliveryStatus.PENDING,
            created_at=created_at,
            updated_at=created_at,
        )

    async def lock(self, assisted_id: uuid.UUID) -> AssistedDelivery | None:
        row: AssistedDelivery | None = await self.session.scalar(
            self._select()
            .where(AssistedDelivery.id == assisted_id)
            .with_for_update(key_share=True)  # FOR NO KEY UPDATE (D-020)
            .execution_options(populate_existing=True)
        )
        return row

    async def decide(
        self,
        assisted_id: uuid.UUID,
        *,
        status: AssistedDeliveryStatus,
        at: datetime,
        final_text: str | None = None,
        message_id: uuid.UUID | None = None,
    ) -> AssistedDelivery:
        return await self._update(
            assisted_id,
            status=status,
            decided_at=at,
            final_text=final_text,
            message_id=message_id,
            updated_at=at,
        )

    async def cancel_pending(
        self,
        *,
        at: datetime,
        reason: str,
        conversation_id: uuid.UUID | None = None,
        contact_id: uuid.UUID | None = None,
        connection_id: uuid.UUID | None = None,
    ) -> list[AssistedDelivery]:
        """Cancel the pending deliveries of one conversation, contact or connection."""
        given = [x for x in (conversation_id, contact_id, connection_id) if x is not None]
        if len(given) != 1:
            raise ValueError("give exactly one of conversation_id, contact_id, connection_id")
        stmt = update(AssistedDelivery).where(
            AssistedDelivery.user_id == self.scope.user_id,
            AssistedDelivery.status == AssistedDeliveryStatus.PENDING,
        )
        if conversation_id is not None:
            stmt = stmt.where(AssistedDelivery.conversation_id == conversation_id)
        elif contact_id is not None:
            stmt = stmt.where(AssistedDelivery.contact_id == contact_id)
        else:
            stmt = stmt.where(AssistedDelivery.connection_id == connection_id)
        rows = await self.session.scalars(
            stmt.values(
                status=AssistedDeliveryStatus.CANCELED,
                cancel_reason=reason,
                decided_at=at,
                updated_at=at,
            )
            .returning(AssistedDelivery)
            .execution_options(synchronize_session="fetch")
        )
        return list(rows.all())

    async def list(
        self,
        *,
        status: AssistedDeliveryStatus,
        conversation_id: uuid.UUID | None = None,
        cursor: str | None = None,
        limit: int | None = None,
    ) -> Page[AssistedDelivery]:
        stmt = self._select().where(AssistedDelivery.status == status)
        listing = f"assisted:{status.value}"
        if conversation_id is not None:
            stmt = stmt.where(AssistedDelivery.conversation_id == conversation_id)
            listing += f":{conversation_id}"
        return await self._page(
            stmt,
            listing=listing,
            sort_column=AssistedDelivery.created_at,
            descending=True,
            cursor=cursor,
            limit=limit,
        )


class IdentityLinkRepository(ScopedRepository[IdentityLink]):
    model = IdentityLink

    @staticmethod
    def ordered(a: uuid.UUID, b: uuid.UUID) -> tuple[uuid.UUID, uuid.UUID]:
        return (a, b) if str(a) < str(b) else (b, a)

    async def create_once(self, a: uuid.UUID, b: uuid.UUID) -> tuple[IdentityLink, bool]:
        """User action only. Unordered pair, one row per pair."""
        first, second = self.ordered(a, b)
        stmt = (
            insert(IdentityLink)
            .values(user_id=self.scope.user_id, contact_a_id=first, contact_b_id=second)
            .on_conflict_do_nothing(
                index_elements=[
                    IdentityLink.user_id,
                    IdentityLink.contact_a_id,
                    IdentityLink.contact_b_id,
                ]
            )
            .returning(IdentityLink)
        )
        created: IdentityLink | None = await self.session.scalar(stmt)
        if created is not None:
            return created, True
        existing: IdentityLink | None = await self.session.scalar(
            self._select().where(
                IdentityLink.contact_a_id == first, IdentityLink.contact_b_id == second
            )
        )
        if existing is None:  # pragma: no cover
            raise LookupError("identity link vanished during create_once")
        return existing, False

    async def delete(self, link_id: uuid.UUID) -> IdentityLink | None:
        row = await self.get(link_id)
        if row is None:
            return None
        await self.session.delete(row)
        await self.session.flush()
        return row

    async def list_for_contact(
        self, contact_id: uuid.UUID, *, cursor: str | None = None, limit: int | None = None
    ) -> Page[IdentityLink]:
        return await self._page(
            self._select().where(
                or_(
                    IdentityLink.contact_a_id == contact_id, IdentityLink.contact_b_id == contact_id
                )
            ),
            listing=f"identity_links:{contact_id}",
            sort_column=IdentityLink.created_at,
            descending=True,
            cursor=cursor,
            limit=limit,
        )


class ConversationProfileLinkRepository(ScopedRepository[ConversationProfileLink]):
    """Contract 1.3.2 (D-032): at most one linked profile per conversation."""

    model = ConversationProfileLink

    async def for_conversation(self, conversation_id: uuid.UUID) -> ConversationProfileLink | None:
        row: ConversationProfileLink | None = await self.session.scalar(
            self._select().where(ConversationProfileLink.conversation_id == conversation_id)
        )
        return row

    async def put(
        self, conversation_id: uuid.UUID, profile_id: uuid.UUID, origin: str
    ) -> tuple[ConversationProfileLink, bool]:
        """Set the conversation's link. Returns ``(link, changed)``; ``changed`` is false
        when the same profile was already linked with the same origin."""
        existing = await self.for_conversation(conversation_id)
        if existing is None:
            return (
                await self._add(
                    conversation_id=conversation_id, profile_id=profile_id, origin=origin
                ),
                True,
            )
        if existing.profile_id == profile_id and existing.origin == origin:
            return existing, False
        updated = await self._update(
            existing.id, profile_id=profile_id, origin=origin, updated_at=utc_now()
        )
        return updated, True

    async def remove(self, conversation_id: uuid.UUID) -> ConversationProfileLink | None:
        row = await self.for_conversation(conversation_id)
        if row is None:
            return None
        await self.session.delete(row)
        await self.session.flush()
        return row

    async def conversation_ids_for_profile(self, profile_id: uuid.UUID) -> list[uuid.UUID]:
        rows = await self.session.scalars(
            self._select()
            .with_only_columns(ConversationProfileLink.conversation_id)
            .where(ConversationProfileLink.profile_id == profile_id)
            .order_by(ConversationProfileLink.conversation_id)
        )
        return list(rows)


class ProfileRepository(ScopedRepository[Profile]):
    model = Profile

    async def delete(self, profile_id: uuid.UUID) -> Profile | None:
        """Delete one of the caller's profiles. Its evaluations, actions and
        conversation links go with it (FK cascade). ``None`` outside the scope."""
        row = await self.get(profile_id)
        if row is None:
            return None
        await self.session.execute(
            delete(Profile).where(Profile.user_id == self.scope.user_id, Profile.id == profile_id)
        )
        self.session.expunge(row)
        return row

    async def upsert(
        self,
        *,
        provider: ProfileProvider,
        external_profile_id: str,
        source: ProfileSource,
        connection_id: uuid.UUID | None,
        display_name: str | None,
        bio: str | None,
        prompts: list[dict[str, Any]],
        attributes: dict[str, Any],
        source_observed_at: datetime,
        imported_at: datetime,
    ) -> tuple[Profile, bool]:
        """Insert, or replace content and provenance of the same provider profile."""
        content = {
            "source": source,
            "connection_id": connection_id,
            "display_name": display_name,
            "bio": bio,
            "prompts": prompts,
            "attributes": attributes,
            "source_observed_at": source_observed_at,
            "imported_at": imported_at,
            "invalidated_at": None,
        }
        stmt = (
            insert(Profile)
            .values(
                user_id=self.scope.user_id,
                provider=provider,
                external_profile_id=external_profile_id,
                **content,
            )
            .on_conflict_do_nothing(
                index_elements=[Profile.user_id, Profile.provider, Profile.external_profile_id]
            )
            .returning(Profile)
        )
        created: Profile | None = await self.session.scalar(stmt)
        if created is not None:
            return created, True
        existing: Profile | None = await self.session.scalar(
            self._select().where(
                Profile.provider == provider, Profile.external_profile_id == external_profile_id
            )
        )
        if existing is None:  # pragma: no cover
            raise LookupError("profile vanished during upsert")
        updated = await self._update(existing.id, updated_at=imported_at, **content)
        return updated, False

    def _linked(self) -> Any:
        """Profiles attached to a conversation (1.3.2): a match's profile, not a
        discovery candidate, so the review queue leaves them out."""
        return (
            ConversationProfileLinkRepository(self.session, self.scope)
            ._select()
            .with_only_columns(ConversationProfileLink.profile_id)
        )

    def _undecided(self, provider: ProfileProvider | None) -> Any:
        decided = (
            ProfileActionRepository(self.session, self.scope)
            ._select()
            .with_only_columns(ProfileAction.profile_id)
            .where(ProfileAction.decision != ProfileDecision.SKIP)
        )
        stmt = self._select().where(Profile.id.not_in(decided), Profile.id.not_in(self._linked()))
        if provider is not None:
            stmt = stmt.where(Profile.provider == provider)
        return stmt

    async def undecided(
        self, *, provider: ProfileProvider | None = None, limit: int
    ) -> list[Profile]:
        """Every profile with no decision other than ``skip`` (newest import first, at
        most ``limit``): the candidates WING-019 ranks for the review queue."""
        rows = await self.session.scalars(
            self._undecided(provider)
            .order_by(Profile.created_at.desc(), Profile.id.desc())
            .limit(limit)
        )
        return list(rows)

    async def review_queue(
        self,
        *,
        provider: ProfileProvider | None = None,
        cursor: str | None = None,
        limit: int | None = None,
    ) -> Page[Profile]:
        """Profiles with no decision other than ``skip``. Newest
        import first. WING-019/031 may rank differently within the same shape."""
        decided = (
            ProfileActionRepository(self.session, self.scope)
            ._select()
            .with_only_columns(ProfileAction.profile_id)
            .where(ProfileAction.decision != ProfileDecision.SKIP)
        )
        stmt = self._select().where(Profile.id.not_in(decided), Profile.id.not_in(self._linked()))
        listing = "profiles:queue"
        if provider is not None:
            stmt = stmt.where(Profile.provider == provider)
            listing += f":{provider.value}"
        return await self._page(
            stmt,
            listing=listing,
            sort_column=Profile.created_at,
            descending=True,
            cursor=cursor,
            limit=limit,
        )


class ProfileEvaluationRepository(ScopedRepository[ProfileEvaluation]):
    model = ProfileEvaluation

    async def create(
        self,
        *,
        profile_id: uuid.UUID,
        score: float | None,
        reasons: list[dict[str, Any]],
        known_unknowns: Sequence[str],
        intent_mode: IntentMode,
        dealbreakers_hit: Sequence[str],
        confidence: float,
        evaluator_version: str,
        preferences_version: int = 0,
        input_fingerprint: str | None = None,
    ) -> ProfileEvaluation:
        """WING-019 stores its evaluations here (advisory only). Append-only: a new
        preferences version or profile content adds a row; earlier rows are kept."""
        return await self._add(
            profile_id=profile_id,
            score=score,
            reasons=reasons,
            known_unknowns=list(known_unknowns),
            intent_mode=intent_mode,
            dealbreakers_hit=list(dealbreakers_hit),
            confidence=confidence,
            evaluator_version=evaluator_version,
            preferences_version=preferences_version,
            input_fingerprint=input_fingerprint,
        )

    async def latest_for_profiles(
        self, profile_ids: Sequence[uuid.UUID]
    ) -> dict[uuid.UUID, ProfileEvaluation]:
        """The latest evaluation of each given profile (one query, ``DISTINCT ON``)."""
        if not profile_ids:
            return {}
        rows = await self.session.scalars(
            self._select()
            .where(ProfileEvaluation.profile_id.in_(list(profile_ids)))
            .ext(distinct_on(ProfileEvaluation.profile_id))
            .order_by(
                ProfileEvaluation.profile_id,
                ProfileEvaluation.created_at.desc(),
                ProfileEvaluation.id.desc(),
            )
        )
        return {row.profile_id: row for row in rows}

    async def history(self, profile_id: uuid.UUID) -> list[ProfileEvaluation]:
        """Every evaluation of a profile, oldest first."""
        rows = await self.session.scalars(
            self._select()
            .where(ProfileEvaluation.profile_id == profile_id)
            .order_by(ProfileEvaluation.created_at.asc(), ProfileEvaluation.id.asc())
        )
        return list(rows)

    async def latest(self, profile_id: uuid.UUID) -> ProfileEvaluation | None:
        row: ProfileEvaluation | None = await self.session.scalar(
            self._select()
            .where(ProfileEvaluation.profile_id == profile_id)
            .order_by(ProfileEvaluation.created_at.desc(), ProfileEvaluation.id.desc())
            .limit(1)
        )
        return row


class ProfileActionRepository(ScopedRepository[ProfileAction]):
    model = ProfileAction

    async def create(
        self,
        *,
        profile_id: uuid.UUID,
        connection_id: uuid.UUID | None,
        decision: ProfileDecision,
        idempotency_key: str,
        decided_at: datetime,
    ) -> ProfileAction:
        return await self._add(
            profile_id=profile_id,
            connection_id=connection_id,
            decision=decision,
            idempotency_key=idempotency_key,
            execution=ProfileActionExecution.NOT_EXECUTABLE,
            decided_at=decided_at,
            created_at=decided_at,
            updated_at=decided_at,
        )

    async def lock(self, action_id: uuid.UUID) -> ProfileAction | None:
        row: ProfileAction | None = await self.session.scalar(
            self._select()
            .where(ProfileAction.id == action_id)
            .with_for_update(key_share=True)  # FOR NO KEY UPDATE (D-020)
            .execution_options(populate_existing=True)
        )
        return row

    async def latest(self, profile_id: uuid.UUID) -> ProfileAction | None:
        row: ProfileAction | None = await self.session.scalar(
            self._select()
            .where(ProfileAction.profile_id == profile_id)
            .order_by(ProfileAction.created_at.desc(), ProfileAction.id.desc())
            .limit(1)
        )
        return row

    async def live_for_profile(self, profile_id: uuid.UUID) -> ProfileAction | None:
        """A ``queued`` or ``executed`` action for this profile (at most one, unique)."""
        row: ProfileAction | None = await self.session.scalar(
            self._select().where(
                ProfileAction.profile_id == profile_id,
                ProfileAction.execution.in_(
                    [ProfileActionExecution.QUEUED, ProfileActionExecution.EXECUTED]
                ),
            )
        )
        return row

    async def mark_queued(self, action_id: uuid.UUID, *, at: datetime) -> ProfileAction:
        return await self._update(
            action_id,
            execution=ProfileActionExecution.QUEUED,
            execution_requested_at=at,
            updated_at=at,
        )


__all__ = [
    "AssistedDeliveryRepository",
    "IdentityLinkRepository",
    "InboundNoticeRepository",
    "ProfileActionRepository",
    "ProfileEvaluationRepository",
    "ProfileRepository",
    "utc_now",
]
