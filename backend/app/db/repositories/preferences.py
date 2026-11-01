"""Versioned preferences and style profiles (WING-007 builds the service on top)."""

import uuid
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from app.db.enums import (
    ApprovalReason,
    AutomationMode,
    Brevity,
    EmojiFrequency,
    Humor,
    IntentMode,
    Punctuation,
    ResponsePace,
    Tone,
)
from app.db.errors import VersionConflictError
from app.db.models import StyleProfile, UserPreference
from app.db.pagination import Page
from app.db.repositories.base import ScopedRepository


class StyleProfileRepository(ScopedRepository[StyleProfile]):
    model = StyleProfile

    async def create(
        self,
        *,
        punctuation: Punctuation | None = None,
        emoji_frequency: EmojiFrequency | None = None,
        brevity: Brevity | None = None,
        humor: Humor | None = None,
        tone: Tone | None = None,
        response_pace: ResponsePace | None = None,
        style_samples: list[str] | None = None,
    ) -> StyleProfile:
        """Style profiles are immutable snapshots: create a new one for every change."""
        return await self._add(
            punctuation=punctuation,
            emoji_frequency=emoji_frequency,
            brevity=brevity,
            humor=humor,
            tone=tone,
            response_pace=response_pace,
            style_samples=list(style_samples or []),
        )


class PreferenceRepository(ScopedRepository[UserPreference]):
    """Immutable preference versions. ``contact_id=None`` is the user-wide scope."""

    model = UserPreference

    def _for(self, contact_id: uuid.UUID | None) -> Any:
        if contact_id is None:
            return UserPreference.contact_id.is_(None)
        return UserPreference.contact_id == contact_id

    async def current(self, contact_id: uuid.UUID | None = None) -> UserPreference | None:
        """Latest version for the scope, or ``None`` if never saved (API ``version: 0``)."""
        row: UserPreference | None = await self.session.scalar(
            self._select()
            .where(self._for(contact_id))
            .order_by(UserPreference.version.desc())
            .limit(1)
        )
        return row

    async def save_version(
        self,
        *,
        expected_version: int | None,
        contact_id: uuid.UUID | None = None,
        intent_mode: IntentMode | None,
        intent_custom_description: str | None = None,
        dealbreakers: list[str] | None = None,
        style_profile_id: uuid.UUID | None,
        automation_mode: AutomationMode | None,
        active_hours: dict[str, Any] | None = None,
        reply_delay_min_seconds: int | None = None,
        reply_delay_max_seconds: int | None = None,
        daily_send_limit: int | None = None,
        approval_required_for: list[ApprovalReason] | None = None,
    ) -> UserPreference:
        """Append version ``current + 1``.

        ``expected_version`` is the ``If-Match`` value (0 = never saved); ``None`` skips the
        check. A stale value, or a concurrent writer winning the race, raises
        :class:`VersionConflictError`.
        """
        latest = await self.session.scalar(
            select(func.coalesce(func.max(UserPreference.version), 0)).where(
                UserPreference.user_id == self.scope.user_id, self._for(contact_id)
            )
        )
        latest_version = int(latest or 0)
        if expected_version is not None and expected_version != latest_version:
            raise VersionConflictError(f"expected version {expected_version}, at {latest_version}")
        try:
            async with self.session.begin_nested():
                return await self._add(
                    contact_id=contact_id,
                    version=latest_version + 1,
                    intent_mode=intent_mode,
                    intent_custom_description=intent_custom_description,
                    dealbreakers=list(dealbreakers or []),
                    style_profile_id=style_profile_id,
                    automation_mode=automation_mode,
                    active_hours=active_hours,
                    reply_delay_min_seconds=reply_delay_min_seconds,
                    reply_delay_max_seconds=reply_delay_max_seconds,
                    daily_send_limit=daily_send_limit,
                    approval_required_for=list(approval_required_for or []),
                )
        except IntegrityError as exc:
            if "uq_user_preferences_user_id_contact_id_version" in str(exc.orig):
                raise VersionConflictError("concurrent preference update") from exc
            raise

    async def list_versions(
        self,
        contact_id: uuid.UUID | None = None,
        *,
        cursor: str | None = None,
        limit: int | None = None,
    ) -> Page[UserPreference]:
        """Newest version first."""
        return await self._page(
            self._select().where(self._for(contact_id)),
            listing="preference_versions" if contact_id is None else f"cp:{contact_id}",
            sort_column=UserPreference.version,
            descending=True,
            cursor=cursor,
            limit=limit,
        )
