"""Contract 1.3.0 user actions (ADR 0008): assisted delivery, identity links, profiles.

* **Assisted delivery.** The dispatcher queues a draft-only delivery when a connection
  cannot send; :meth:`AssistedDeliveryService.confirm` records the user's own report
  ("I sent it") as an ``assisted_draft`` message in ``user_reported_sent`` (never
  ``sent``/``delivered``), :meth:`AssistedDeliveryService.dismiss` drops it.
* **Identity links** are created and deleted only by these user calls. Nothing in the
  backend infers, suggests-and-applies or auto-creates a link.
* **Profiles and swipe decisions** (WING-019/031 build on this): import, review queue,
  the user's decision, and the execution gate. The gate only moves an action to
  ``queued``; no code path here executes a swipe or sets ``executed``.

Locks follow the global order (user -> contact -> row) and use ``FOR NO KEY UPDATE``
(D-020). Audit ``details`` carry IDs and codes only, never text or profile content.
"""

import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.db.base import utc_now
from app.db.enums import (
    Actor,
    AgentActionType,
    AssistedDeliveryStatus,
    CapabilityVerification,
    GlobalAutomationStatus,
    ProfileActionExecution,
    ProfileDecision,
    ProfileProvider,
    ProfileSource,
)
from app.db.errors import NotFoundError
from app.db.models import (
    AssistedDelivery,
    ConversationProfileLink,
    IdentityLink,
    Message,
    Profile,
    ProfileAction,
)
from app.db.repositories import (
    AgentActionRepository,
    AssistedDeliveryRepository,
    ConnectionRepository,
    ContactRepository,
    ConversationProfileLinkRepository,
    ConversationRepository,
    IdentityLinkRepository,
    ProfileActionRepository,
    ProfileEvaluationRepository,
    ProfileRepository,
    UserRepository,
)
from app.db.scope import UserScope
from app.services.connections import swipe_gate_failure
from app.services.dispatcher import Dispatcher

__all__ = [
    "PROFILE_FRESHNESS",
    "AssistedDeliveryNotPending",
    "AssistedDeliveryService",
    "IdentityLinkService",
    "InvalidRequest",
    "ProfileLinkService",
    "ProfileService",
    "SwipeExecutionDisabled",
    "is_stale",
]

# A profile older than this (by what the source showed) may no longer match the
# platform: it can be decided on but never executed (contract 1.3.0 `Profile.stale`).
PROFILE_FRESHNESS = timedelta(days=14)


class InvalidRequest(Exception):  # noqa: N818 - maps to 422 validation_error
    def __init__(self, detail: str, *, pointer: str, message: str) -> None:
        super().__init__(detail)
        self.detail = detail
        self.pointer = pointer
        self.message = message


class AssistedDeliveryNotPending(Exception):  # noqa: N818 - contract error code
    """Only a ``pending`` assisted delivery can be confirmed or dismissed (409)."""


class SwipeExecutionDisabled(Exception):  # noqa: N818 - contract error code
    """The swipe execution gate refused (422 ``swipe_execution_disabled``)."""

    def __init__(self, pointer: str, message: str) -> None:
        super().__init__(message)
        self.pointer = pointer
        self.message = message


# --------------------------------------------------------------------------- assisted


class AssistedDeliveryService:
    def __init__(self, dispatcher: Dispatcher, *, now: Callable[[], datetime] = utc_now) -> None:
        self.dispatcher = dispatcher
        self._now = now

    async def _lock_pending(
        self, session: AsyncSession, scope: UserScope, assisted_id: uuid.UUID
    ) -> AssistedDelivery:
        repo = AssistedDeliveryRepository(session, scope)
        peek = await repo.require(assisted_id)
        # Lock order: user -> contact -> assisted delivery, like approve and takeover.
        await UserRepository(session).lock(scope, shared=True)
        await ContactRepository(session, scope).lock_shared(peek.contact_id)
        assisted = await repo.lock(assisted_id)
        if assisted is None:  # pragma: no cover - deleted concurrently
            raise NotFoundError("assisted delivery not found")
        if assisted.status is not AssistedDeliveryStatus.PENDING:
            raise AssistedDeliveryNotPending(assisted.status.value)
        return assisted

    async def confirm(
        self,
        session: AsyncSession,
        scope: UserScope,
        assisted_id: uuid.UUID,
        *,
        sent_text: str | None,
    ) -> tuple[AssistedDelivery, Message]:
        """``confirmAssistedDelivery``: the user says they sent it by hand."""
        assisted = await self._lock_pending(session, scope, assisted_id)
        text = sent_text if sent_text is not None else assisted.text
        message = await self.dispatcher.confirm_assisted(session, scope, assisted, sent_text=text)
        refreshed = await AssistedDeliveryRepository(session, scope).require(assisted.id)
        return refreshed, message

    async def dismiss(
        self, session: AsyncSession, scope: UserScope, assisted_id: uuid.UUID
    ) -> AssistedDelivery:
        """``dismissAssistedDelivery``: nothing is recorded as sent."""
        assisted = await self._lock_pending(session, scope, assisted_id)
        now = self._now()
        updated = await AssistedDeliveryRepository(session, scope).decide(
            assisted.id, status=AssistedDeliveryStatus.DISMISSED, at=now
        )
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.ASSISTED_DELIVERY_DISMISSED,
            actor=Actor.USER,
            conversation_id=assisted.conversation_id,
            contact_id=assisted.contact_id,
            occurred_at=now,
            details={"assisted_delivery_id": str(assisted.id)},
        )
        return updated


# --------------------------------------------------------------------------- identity


class IdentityLinkService:
    """User-made links only (WING-032, ADR 0008). There is deliberately no method that
    links contacts from names, numbers or any other inference."""

    def __init__(self, *, now: Callable[[], datetime] = utc_now) -> None:
        self._now = now

    async def link(
        self, session: AsyncSession, scope: UserScope, a: uuid.UUID, b: uuid.UUID
    ) -> tuple[IdentityLink, bool]:
        contacts = ContactRepository(session, scope)
        await contacts.require(a)  # another user's contact is 404
        await contacts.require(b)
        if a == b:
            raise InvalidRequest(
                "a contact cannot be linked to itself",
                pointer="/linked_contact_id",
                message="must differ from contact_id",
            )
        link, created = await IdentityLinkRepository(session, scope).create_once(a, b)
        if created:
            await AgentActionRepository(session, scope).record(
                type=AgentActionType.IDENTITY_LINK_CREATED,
                actor=Actor.USER,
                contact_id=a,
                occurred_at=self._now(),
                details={
                    "identity_link_id": str(link.id),
                    "contact_ids": sorted([str(a), str(b)]),
                },
            )
        return link, created

    async def unlink(self, session: AsyncSession, scope: UserScope, link_id: uuid.UUID) -> None:
        deleted = await IdentityLinkRepository(session, scope).delete(link_id)
        if deleted is None:
            raise NotFoundError("identity link not found")
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.IDENTITY_LINK_DELETED,
            actor=Actor.USER,
            contact_id=deleted.contact_a_id,
            occurred_at=self._now(),
            details={
                "identity_link_id": str(link_id),
                "contact_ids": [str(deleted.contact_a_id), str(deleted.contact_b_id)],
            },
        )


# --------------------------------------------------------------------------- profile links

PROFILE_LINK_ORIGINS = ("user", "device_match")


class ProfileLinkService:
    """Contract 1.3.2 (D-032): attach one profile to a conversation so reply drafts can
    use its stated text as grounded context.

    ``origin: user`` is the user's own choice (the "Which match is this?" picker).
    ``origin: device_match`` is accepted only when the profile and the conversation are
    on the same platform **and** the profile's ``external_profile_id`` equals the
    conversation's ``external_thread_id`` or its contact's ``external_contact_id``: a
    stable platform ID, as ADR 0008 section 4 requires for ``linked``. There is
    deliberately no path that links by display name, photo or model output.
    """

    def __init__(self, *, now: Callable[[], datetime] = utc_now) -> None:
        self._now = now

    async def link(
        self,
        session: AsyncSession,
        scope: UserScope,
        conversation_id: uuid.UUID,
        profile_id: uuid.UUID,
        *,
        origin: str = "user",
    ) -> ConversationProfileLink:
        conversation = await ConversationRepository(session, scope).require(conversation_id)
        profile = await ProfileRepository(session, scope).get(profile_id)
        if profile is None:
            raise InvalidRequest(
                "profile_id must be one of the caller's profiles",
                pointer="/profile_id",
                message="no such profile",
            )
        if origin not in PROFILE_LINK_ORIGINS:  # pragma: no cover - schema enum
            raise InvalidRequest("unknown origin", pointer="/origin", message="unknown")
        if origin == "device_match":
            contact = await ContactRepository(session, scope).require(conversation.contact_id)
            stable = {conversation.external_thread_id, contact.external_contact_id} - {None}
            if (
                profile.provider.value != conversation.platform
                or profile.external_profile_id not in stable
            ):
                raise InvalidRequest(
                    "device_match needs the same stable platform ID on the profile and the "
                    "conversation; a display name is not an identity",
                    pointer="/origin",
                    message="no stable ID match; link as 'user' instead",
                )
        link, changed = await ConversationProfileLinkRepository(session, scope).put(
            conversation_id, profile_id, origin
        )
        if changed:
            await AgentActionRepository(session, scope).record(
                type=AgentActionType.PROFILE_LINKED,
                actor=Actor.USER if origin == "user" else Actor.CONNECTOR,
                conversation_id=conversation_id,
                contact_id=conversation.contact_id,
                occurred_at=self._now(),
                details={"profile_id": str(profile_id), "origin": origin},
            )
        return link

    async def get(
        self, session: AsyncSession, scope: UserScope, conversation_id: uuid.UUID
    ) -> ConversationProfileLink:
        await ConversationRepository(session, scope).require(conversation_id)
        link = await ConversationProfileLinkRepository(session, scope).for_conversation(
            conversation_id
        )
        if link is None:
            raise NotFoundError("no profile is linked to this conversation")
        return link

    async def unlink(
        self, session: AsyncSession, scope: UserScope, conversation_id: uuid.UUID
    ) -> None:
        conversation = await ConversationRepository(session, scope).require(conversation_id)
        removed = await ConversationProfileLinkRepository(session, scope).remove(conversation_id)
        if removed is None:
            raise NotFoundError("no profile is linked to this conversation")
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.PROFILE_UNLINKED,
            actor=Actor.USER,
            conversation_id=conversation_id,
            contact_id=conversation.contact_id,
            occurred_at=self._now(),
            details={"profile_id": str(removed.profile_id), "origin": removed.origin},
        )


# --------------------------------------------------------------------------- profiles


def is_stale(profile: Profile, now: datetime) -> bool:
    return profile.invalidated_at is not None or (
        now - profile.source_observed_at > PROFILE_FRESHNESS
    )


@dataclass(frozen=True)
class ProfileView:
    profile: Profile
    stale: bool
    latest_evaluation: Any
    latest_action: ProfileAction | None


class ProfileService:
    def __init__(self, *, now: Callable[[], datetime] = utc_now) -> None:
        self._now = now

    async def view(self, session: AsyncSession, scope: UserScope, profile: Profile) -> ProfileView:
        return ProfileView(
            profile=profile,
            stale=is_stale(profile, self._now()),
            latest_evaluation=await ProfileEvaluationRepository(session, scope).latest(profile.id),
            latest_action=await ProfileActionRepository(session, scope).latest(profile.id),
        )

    async def _check_connection(
        self,
        session: AsyncSession,
        scope: UserScope,
        connection_id: uuid.UUID,
        provider: ProfileProvider,
        *,
        need_profile_read: bool,
    ) -> None:
        connection = await ConnectionRepository(session, scope).get(connection_id)
        if connection is None or connection.platform != provider.value:
            raise InvalidRequest(
                "connection_id must be the caller's connection on the profile's platform",
                pointer="/connection_id",
                message="no such connection on this platform",
            )
        if need_profile_read and (
            connection.unpaired_at is not None
            or connection.profile_read_capability is CapabilityVerification.UNSUPPORTED
        ):
            raise InvalidRequest(
                "a device import needs a paired connection whose profile_read is supported",
                pointer="/connection_id",
                message="profile_read is UNSUPPORTED or the connection is unpaired",
            )

    async def import_profile(
        self,
        session: AsyncSession,
        scope: UserScope,
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
    ) -> tuple[ProfileView, bool]:
        if connection_id is not None:
            await self._check_connection(
                session,
                scope,
                connection_id,
                provider,
                need_profile_read=source is ProfileSource.DEVICE,
            )
        elif source is ProfileSource.DEVICE:  # pragma: no cover - schema requires it
            raise InvalidRequest(
                "device import needs connection_id", pointer="/connection_id", message="required"
            )
        now = self._now()
        profile, created = await ProfileRepository(session, scope).upsert(
            provider=provider,
            external_profile_id=external_profile_id,
            source=source,
            connection_id=connection_id,
            display_name=display_name,
            bio=bio,
            prompts=prompts,
            attributes=attributes,
            source_observed_at=source_observed_at,
            imported_at=now,
        )
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.PROFILE_IMPORTED,
            actor=Actor.USER,
            occurred_at=now,
            details={
                "profile_id": str(profile.id),
                "provider": provider.value,
                "source": source.value,
                "created": created,
            },
        )
        return await self.view(session, scope, profile), created

    async def delete_profile(
        self, session: AsyncSession, scope: UserScope, profile_id: uuid.UUID
    ) -> None:
        """``deleteProfile`` (1.3.2): removes the profile with its evaluations, decisions
        and conversation links. Drafts made afterwards no longer see it. Audit carries
        IDs and counts only."""
        links = ConversationProfileLinkRepository(session, scope)
        conversation_ids = await links.conversation_ids_for_profile(profile_id)
        deleted = await ProfileRepository(session, scope).delete(profile_id)
        if deleted is None:
            raise NotFoundError("profile not found")
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.PROFILE_DELETED,
            actor=Actor.USER,
            occurred_at=self._now(),
            details={
                "profile_id": str(profile_id),
                "provider": deleted.provider.value,
                "source": deleted.source.value,
                "unlinked_conversations": len(conversation_ids),
            },
        )

    async def decide(
        self,
        session: AsyncSession,
        scope: UserScope,
        profile_id: uuid.UUID,
        *,
        decision: ProfileDecision,
        connection_id: uuid.UUID | None,
        idempotency_key: str,
    ) -> ProfileAction:
        """``recordProfileDecision``: the user's decision only; never executed here."""
        profile = await ProfileRepository(session, scope).require(profile_id)
        if connection_id is not None:
            await self._check_connection(
                session, scope, connection_id, profile.provider, need_profile_read=False
            )
        now = self._now()
        action = await ProfileActionRepository(session, scope).create(
            profile_id=profile.id,
            connection_id=connection_id,
            decision=decision,
            idempotency_key=idempotency_key,
            decided_at=now,
        )
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.PROFILE_DECISION_RECORDED,
            actor=Actor.USER,
            occurred_at=now,
            details={
                "profile_id": str(profile.id),
                "profile_action_id": str(action.id),
                "decision": decision.value,
            },
        )
        return action

    async def request_execution(
        self, session: AsyncSession, scope: UserScope, action_id: uuid.UUID
    ) -> ProfileAction:
        """``requestProfileActionExecution``: the swipe execution gate (ADR 0008).

        Passes only if every check holds; then ``execution`` becomes ``queued`` (NOT
        executed). Raises :class:`SwipeExecutionDisabled` naming the first failed check.
        """
        actions = ProfileActionRepository(session, scope)
        await actions.require(action_id)
        user = await UserRepository(session).lock(scope)  # serialize gate decisions
        if user is None:  # pragma: no cover
            raise NotFoundError("user not found")
        action = await actions.lock(action_id)
        if action is None:  # pragma: no cover
            raise NotFoundError("profile action not found")
        if action.execution is ProfileActionExecution.QUEUED:
            return action  # already passed the gate: unchanged
        if action.execution is not ProfileActionExecution.NOT_EXECUTABLE:
            raise SwipeExecutionDisabled(
                "/profile_action_id", "this action was already executed or attempted"
            )
        if action.decision is ProfileDecision.SKIP:
            raise SwipeExecutionDisabled("/decision", "skip is never executed")
        if action.connection_id is None:
            raise SwipeExecutionDisabled("/connection_id", "the decision names no connection")
        connection = await ConnectionRepository(session, scope).lock_for_update(
            action.connection_id
        )
        if connection is None:
            raise SwipeExecutionDisabled("/connection_id", "the connection no longer exists")
        failure = swipe_gate_failure(connection)
        if failure is not None:
            raise SwipeExecutionDisabled(*failure)
        if not connection.auto_swipe_enabled:
            raise SwipeExecutionDisabled(
                "/connection_id", "auto-swipe is not enabled for this connection"
            )
        if user.global_automation_status is GlobalAutomationStatus.PAUSED:
            raise SwipeExecutionDisabled("/connection_id", "automation is paused")
        profile = await ProfileRepository(session, scope).require(action.profile_id)
        now = self._now()
        if profile.provider.value != connection.platform:
            raise SwipeExecutionDisabled("/connection_id", "the profile is on another platform")
        if is_stale(profile, now):
            raise SwipeExecutionDisabled("/profile_id", "the profile is stale")
        if await actions.live_for_profile(profile.id) is not None:
            raise SwipeExecutionDisabled(
                "/profile_id", "another action for this profile is already queued or executed"
            )
        queued = await actions.mark_queued(action.id, at=now)
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.PROFILE_ACTION_QUEUED,
            actor=Actor.USER,
            occurred_at=now,
            details={
                "profile_id": str(profile.id),
                "profile_action_id": str(action.id),
                "connection_id": str(connection.id),
                "decision": action.decision.value,
            },
        )
        return queued


async def require_conversation(
    session: AsyncSession, scope: UserScope, conversation_id: uuid.UUID
) -> None:
    await ConversationRepository(session, scope).require(conversation_id)
