"""Intent, writing-style and automation preferences (WING-007).

Implements the ``preferences`` part of ``contracts/openapi.yaml`` v1.0.0 on top of
:class:`app.db.repositories.PreferenceRepository`:

* **User-wide preferences** (``Preferences``): intent, dealbreakers, style, automation.
  Every write appends an immutable version (``version`` + 1) and an immutable
  ``style_profiles`` snapshot. ``If-Match`` maps to ``expected_version``.
* **Per-contact overrides** (``ContactPreferences.override``): ``intent``, ``style`` and
  ``automation_mode``, each nullable meaning "inherit". Overrides have their own version
  sequence per contact and never touch user-wide rows.
* **Effective preferences**: per-contact override field (when non-null) > user-wide
  preference > safe default. See :func:`merge_effective`.

Safe defaults (never saved): ``version 0``, intent ``open`` (the contract's documented
default, ``contracts/examples/preferences.defaults.response.json``), no dealbreakers,
every style attribute ``null`` (not configured, never inferred), automation ``manual``
with no hours/delay/limit restriction. Nothing here reads messages, other users' data or
any other source to infer a user's style or intent: only what the user explicitly wrote
through this API is stored or returned.

Consumers (WING-010 reply generator, WING-011 automation controller) call::

    prefs = await get_effective_preferences(session, scope, contact_id)

which never writes and raises :class:`app.db.errors.NotFoundError` if ``contact_id`` is
not one of the scoped user's contacts.
"""

import uuid
from collections.abc import Sequence
from datetime import datetime
from typing import Annotated, Any, Literal, Self
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictInt,
    StrictStr,
    field_validator,
    model_validator,
)
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import models as db
from app.db.enums import (
    Actor,
    AgentActionType,
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
from app.db.repositories import (
    AgentActionRepository,
    ContactRepository,
    PreferenceRepository,
    StyleProfileRepository,
)
from app.db.scope import UserScope

__all__ = [
    "ActiveHours",
    "ActiveHoursWindow",
    "AutomationSettings",
    "ContactPreferenceOverride",
    "ContactPreferenceOverrideUpdate",
    "ContactPreferences",
    "EffectivePreferences",
    "Intent",
    "Preferences",
    "PreferencesPage",
    "PreferencesService",
    "PreferencesUpdate",
    "ReplyDelay",
    "StyleProfile",
    "default_preferences",
    "get_effective_preferences",
    "merge_effective",
]

# Retries when no If-Match was sent and a concurrent writer took the next version number.
_UNCONDITIONAL_WRITE_ATTEMPTS = 3

HHMM_PATTERN = r"^([01][0-9]|2[0-3]):[0-5][0-9]$"
Weekday = Literal["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
StyleSample = Annotated[StrictStr, Field(min_length=1, max_length=1000)]
Dealbreaker = Annotated[StrictStr, Field(min_length=1, max_length=200)]
DelaySeconds = Annotated[StrictInt, Field(ge=0, le=86400)]


class _ContractModel(BaseModel):
    """Contract objects: unknown fields are rejected (``additionalProperties: false``)."""

    model_config = ConfigDict(extra="forbid", frozen=True)


# --------------------------------------------------------------------------- contract models


class Intent(_ContractModel):
    """``Intent``. ``custom`` requires a non-empty ``custom_description``; others forbid it."""

    mode: IntentMode
    custom_description: Annotated[StrictStr, Field(max_length=500)] | None = None

    @model_validator(mode="after")
    def _custom_description_rule(self) -> Self:
        if self.mode is IntentMode.CUSTOM:
            if not self.custom_description:
                raise ValueError("custom_description is required when mode is 'custom'")
        elif self.custom_description is not None:
            raise ValueError("custom_description must be null unless mode is 'custom'")
        return self


class StyleProfile(_ContractModel):
    """``StyleProfile``. ``null`` = not configured; the generator must not infer a style."""

    punctuation: Punctuation | None = None
    emoji_frequency: EmojiFrequency | None = None
    brevity: Brevity | None = None
    humor: Humor | None = None
    tone: Tone | None = None
    response_pace: ResponsePace | None = None
    style_samples: list[StyleSample] = Field(default_factory=list, max_length=20)

    def is_empty(self) -> bool:
        return all(getattr(self, name) is None for name in STYLE_ATTRIBUTES) and not (
            self.style_samples
        )


STYLE_ATTRIBUTES = ("punctuation", "emoji_frequency", "brevity", "humor", "tone", "response_pace")


class ActiveHoursWindow(_ContractModel):
    days: list[Weekday] = Field(min_length=1)
    start: Annotated[StrictStr, Field(pattern=HHMM_PATTERN)]
    end: Annotated[StrictStr, Field(pattern=HHMM_PATTERN)]

    @field_validator("days")
    @classmethod
    def _unique_days(cls, days: list[str]) -> list[str]:
        if len(set(days)) != len(days):
            raise ValueError("days must not contain duplicates")
        return days


class ActiveHours(_ContractModel):
    timezone: Annotated[StrictStr, Field(min_length=1, max_length=64)]
    windows: list[ActiveHoursWindow] = Field(min_length=1, max_length=14)

    @field_validator("timezone")
    @classmethod
    def _iana_zone(cls, name: str) -> str:
        try:
            ZoneInfo(name)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError("timezone must be a valid IANA time zone name") from exc
        return name


class ReplyDelay(_ContractModel):
    min: DelaySeconds
    max: DelaySeconds

    @model_validator(mode="after")
    def _ordered(self) -> Self:
        if self.min > self.max:
            raise ValueError("min must be less than or equal to max")
        return self


class AutomationSettings(_ContractModel):
    mode: AutomationMode
    active_hours: ActiveHours | None = None
    reply_delay_seconds: ReplyDelay | None = None
    daily_send_limit: Annotated[StrictInt, Field(ge=0, le=1000)] | None = None
    approval_required_for: list[ApprovalReason] = Field(default_factory=list)

    @field_validator("approval_required_for")
    @classmethod
    def _unique_reasons(cls, reasons: list[ApprovalReason]) -> list[ApprovalReason]:
        if len(set(reasons)) != len(reasons):
            raise ValueError("approval_required_for must not contain duplicates")
        return reasons


class PreferencesUpdate(_ContractModel):
    """``PreferencesUpdate``: full replacement body for ``PUT /v1/preferences``."""

    intent: Intent
    dealbreakers: list[Dealbreaker] = Field(default_factory=list, max_length=50)
    style: StyleProfile
    automation: AutomationSettings


class Preferences(_ContractModel):
    """``Preferences``. ``version`` 0 / ``updated_at`` null = never saved (defaults)."""

    version: int = Field(ge=0)
    intent: Intent
    dealbreakers: list[str]
    style: StyleProfile
    automation: AutomationSettings
    updated_at: datetime | None


EffectivePreferences = Preferences
"""Merged settings for one contact (or user-wide when no contact is given)."""


class PreferencesPage(_ContractModel):
    items: list[Preferences]
    next_cursor: str | None


class ContactPreferenceOverrideUpdate(_ContractModel):
    """``ContactPreferenceOverrideUpdate``: a missing or null field means "inherit"."""

    intent: Intent | None = None
    style: StyleProfile | None = None
    automation_mode: AutomationMode | None = None


class ContactPreferenceOverride(_ContractModel):
    """``ContactPreferences.override``: all-null with ``version`` 0 when never saved."""

    version: int = Field(ge=0)
    intent: Intent | None = None
    style: StyleProfile | None = None
    automation_mode: AutomationMode | None = None
    updated_at: datetime | None = None

    def is_empty(self) -> bool:
        return self.intent is None and self.style is None and self.automation_mode is None


class ContactPreferences(_ContractModel):
    contact_id: uuid.UUID
    override: ContactPreferenceOverride
    effective: Preferences


# --------------------------------------------------------------------------- pure functions


def default_preferences() -> Preferences:
    """Safe defaults for a user who never saved preferences (contract ``version: 0``)."""
    return Preferences(
        version=0,
        intent=Intent(mode=IntentMode.OPEN),
        dealbreakers=[],
        style=StyleProfile(),
        automation=AutomationSettings(mode=AutomationMode.MANUAL),
        updated_at=None,
    )


def _merge_style(base: StyleProfile, override: StyleProfile | None) -> StyleProfile:
    """Per attribute: a non-null override attribute wins, null inherits.

    ``style_samples`` is replaced only when the override supplies at least one sample.
    """
    if override is None:
        return base
    merged: dict[str, Any] = {
        name: getattr(override, name)
        if getattr(override, name) is not None
        else getattr(base, name)
        for name in STYLE_ATTRIBUTES
    }
    merged["style_samples"] = list(override.style_samples or base.style_samples)
    return StyleProfile(**merged)


def merge_effective(
    user_wide: Preferences, override: ContactPreferenceOverride | None
) -> Preferences:
    """Effective settings: override field (when non-null) > user-wide > safe default.

    ``intent`` is replaced as a whole object (mode and description belong together),
    ``style`` merges attribute by attribute, ``automation_mode`` replaces only
    ``automation.mode``. Dealbreakers and the other automation settings are user-wide
    only. ``version``/``updated_at`` stay those of the user-wide preferences (as in
    ``contracts/examples/contact_preferences.response.json``). Inputs are not modified.
    """
    if override is None or override.is_empty():
        return user_wide
    automation = user_wide.automation
    if override.automation_mode is not None:
        automation = automation.model_copy(update={"mode": override.automation_mode})
    return user_wide.model_copy(
        update={
            "intent": override.intent if override.intent is not None else user_wide.intent,
            "style": _merge_style(user_wide.style, override.style),
            "automation": automation,
        }
    )


# --------------------------------------------------------------------------- row mapping


def _style_from_row(row: db.StyleProfile | None) -> StyleProfile:
    if row is None:
        return StyleProfile()
    return StyleProfile(
        punctuation=row.punctuation,
        emoji_frequency=row.emoji_frequency,
        brevity=row.brevity,
        humor=row.humor,
        tone=row.tone,
        response_pace=row.response_pace,
        style_samples=list(row.style_samples),
    )


def _intent_from_row(row: db.UserPreference) -> Intent | None:
    if row.intent_mode is None:
        return None
    return Intent(mode=row.intent_mode, custom_description=row.intent_custom_description)


def _preferences_from_row(row: db.UserPreference, style: db.StyleProfile | None) -> Preferences:
    intent = _intent_from_row(row)
    if intent is None or row.automation_mode is None:  # pragma: no cover - DB CHECK forbids
        raise ValueError("user-wide preference row lacks required fields")
    delay = None
    if row.reply_delay_min_seconds is not None and row.reply_delay_max_seconds is not None:
        delay = ReplyDelay(min=row.reply_delay_min_seconds, max=row.reply_delay_max_seconds)
    return Preferences(
        version=row.version,
        intent=intent,
        dealbreakers=list(row.dealbreakers),
        style=_style_from_row(style),
        automation=AutomationSettings(
            mode=row.automation_mode,
            active_hours=(
                ActiveHours.model_validate(row.active_hours) if row.active_hours else None
            ),
            reply_delay_seconds=delay,
            daily_send_limit=row.daily_send_limit,
            approval_required_for=list(row.approval_required_for),
        ),
        updated_at=row.created_at,
    )


def _override_from_row(
    row: db.UserPreference | None, style: db.StyleProfile | None
) -> ContactPreferenceOverride:
    if row is None:
        return ContactPreferenceOverride(version=0)
    return ContactPreferenceOverride(
        version=row.version,
        intent=_intent_from_row(row),
        style=_style_from_row(style) if row.style_profile_id is not None else None,
        automation_mode=row.automation_mode,
        updated_at=row.created_at,
    )


# --------------------------------------------------------------------------- service


class PreferencesService:
    """User-scoped preference operations. Flushes but never commits (caller owns the txn)."""

    def __init__(self, session: AsyncSession, scope: UserScope) -> None:
        self.session = session
        self.scope = scope
        self._prefs = PreferenceRepository(session, scope)
        self._styles = StyleProfileRepository(session, scope)

    # ------------------------------------------------------------------ helpers
    async def _styles_by_id(
        self, ids: Sequence[uuid.UUID | None]
    ) -> dict[uuid.UUID, db.StyleProfile]:
        wanted = {i for i in ids if i is not None}
        if not wanted:
            return {}
        rows = await self.session.scalars(
            select(db.StyleProfile).where(
                db.StyleProfile.user_id == self.scope.user_id, db.StyleProfile.id.in_(wanted)
            )
        )
        return {r.id: r for r in rows}

    async def _style_of(self, row: db.UserPreference | None) -> db.StyleProfile | None:
        if row is None or row.style_profile_id is None:
            return None
        return (await self._styles_by_id([row.style_profile_id])).get(row.style_profile_id)

    async def _require_contact(self, contact_id: uuid.UUID) -> None:
        await ContactRepository(self.session, self.scope).require(contact_id)

    async def _new_style(self, style: StyleProfile) -> uuid.UUID:
        row = await self._styles.create(
            punctuation=style.punctuation,
            emoji_frequency=style.emoji_frequency,
            brevity=style.brevity,
            humor=style.humor,
            tone=style.tone,
            response_pace=style.response_pace,
            style_samples=list(style.style_samples),
        )
        return row.id

    async def _save(
        self, expected_version: int | None, contact_id: uuid.UUID | None, **values: Any
    ) -> db.UserPreference:
        """Append a version. Without If-Match, retry if a concurrent writer won the race."""
        attempt = 1
        while True:
            try:
                return await self._prefs.save_version(
                    expected_version=expected_version, contact_id=contact_id, **values
                )
            except VersionConflictError:
                if expected_version is not None or attempt >= _UNCONDITIONAL_WRITE_ATTEMPTS:
                    raise
                attempt += 1

    @staticmethod
    def _check_version(expected_version: int | None, current: int) -> None:
        # Fail before writing a style snapshot; save_version re-checks atomically.
        if expected_version is not None and expected_version != current:
            raise VersionConflictError(f"expected version {expected_version}, at {current}")

    async def _audit(self, scope_name: str, version: int, contact_id: uuid.UUID | None) -> None:
        await AgentActionRepository(self.session, self.scope).record(
            type=AgentActionType.PREFERENCES_UPDATED,
            actor=Actor.USER,
            contact_id=contact_id,
            details={"scope": scope_name, "version": version},
        )

    # ------------------------------------------------------------------ user-wide
    async def get_user_preferences(self) -> Preferences:
        row = await self._prefs.current()
        if row is None:
            return default_preferences()
        return _preferences_from_row(row, await self._style_of(row))

    async def put_user_preferences(
        self, update: PreferencesUpdate, *, expected_version: int | None = None
    ) -> Preferences:
        """Full replacement. Raises :class:`VersionConflictError` on a stale If-Match."""
        current = await self._prefs.current()
        self._check_version(expected_version, current.version if current else 0)
        style_id = await self._new_style(update.style)
        automation = update.automation
        delay = automation.reply_delay_seconds
        row = await self._save(
            expected_version,
            None,
            intent_mode=update.intent.mode,
            intent_custom_description=update.intent.custom_description,
            dealbreakers=list(update.dealbreakers),
            style_profile_id=style_id,
            automation_mode=automation.mode,
            active_hours=(
                automation.active_hours.model_dump(mode="json")
                if automation.active_hours is not None
                else None
            ),
            reply_delay_min_seconds=delay.min if delay else None,
            reply_delay_max_seconds=delay.max if delay else None,
            daily_send_limit=automation.daily_send_limit,
            approval_required_for=list(automation.approval_required_for),
        )
        await self._audit("user", row.version, None)
        return _preferences_from_row(row, await self._style_of(row))

    async def list_user_versions(
        self, *, cursor: str | None = None, limit: int | None = None
    ) -> PreferencesPage:
        """Newest first. Raises :class:`app.db.errors.InvalidCursorError` on a bad cursor."""
        page = await self._prefs.list_versions(cursor=cursor, limit=limit)
        styles = await self._styles_by_id([r.style_profile_id for r in page.items])
        items = [
            _preferences_from_row(r, styles.get(r.style_profile_id) if r.style_profile_id else None)
            for r in page.items
        ]
        return PreferencesPage(items=items, next_cursor=page.next_cursor)

    # ------------------------------------------------------------------ per contact
    async def _current_override(self, contact_id: uuid.UUID) -> ContactPreferenceOverride:
        row = await self._prefs.current(contact_id)
        return _override_from_row(row, await self._style_of(row))

    async def get_contact_preferences(self, contact_id: uuid.UUID) -> ContactPreferences:
        await self._require_contact(contact_id)
        override = await self._current_override(contact_id)
        user_wide = await self.get_user_preferences()
        return ContactPreferences(
            contact_id=contact_id,
            override=override,
            effective=merge_effective(user_wide, override),
        )

    async def put_contact_override(
        self,
        contact_id: uuid.UUID,
        update: ContactPreferenceOverrideUpdate,
        *,
        expected_version: int | None = None,
    ) -> ContactPreferences:
        """Replace the override. Never writes user-wide preferences."""
        await self._require_contact(contact_id)
        current = await self._prefs.current(contact_id)
        self._check_version(expected_version, current.version if current else 0)
        style_id = await self._new_style(update.style) if update.style is not None else None
        row = await self._save(
            expected_version,
            contact_id,
            intent_mode=update.intent.mode if update.intent else None,
            intent_custom_description=update.intent.custom_description if update.intent else None,
            style_profile_id=style_id,
            automation_mode=update.automation_mode,
        )
        await self._audit("contact", row.version, contact_id)
        return await self.get_contact_preferences(contact_id)

    async def clear_contact_override(self, contact_id: uuid.UUID) -> None:
        """Inherit everything. Appends an all-null version so history is kept.

        No-op (no new version) when there is no override or it is already empty.
        """
        await self._require_contact(contact_id)
        if (await self._current_override(contact_id)).is_empty():
            return
        row = await self._save(
            None, contact_id, intent_mode=None, style_profile_id=None, automation_mode=None
        )
        await self._audit("contact", row.version, contact_id)

    async def get_effective_preferences(
        self, contact_id: uuid.UUID | None = None
    ) -> EffectivePreferences:
        if contact_id is None:
            return await self.get_user_preferences()
        return (await self.get_contact_preferences(contact_id)).effective


async def get_effective_preferences(
    session: AsyncSession, scope: UserScope, contact_id: uuid.UUID | None = None
) -> EffectivePreferences:
    """Read-only entry point for WING-010/011.

    Returns the merged settings the reply generator and automation controller must obey.
    ``contact_id=None`` gives the user-wide preferences (or safe defaults). Raises
    :class:`app.db.errors.NotFoundError` if the contact is not the scoped user's.
    """
    return await PreferencesService(session, scope).get_effective_preferences(contact_id)
