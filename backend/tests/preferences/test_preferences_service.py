"""Service-level API that WING-010/011 consume, plus the pure merge rules."""

import json
import uuid

import pytest
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db.enums import AutomationMode, IntentMode, Tone
from app.db.errors import NotFoundError, VersionConflictError
from app.db.session import session_scope
from app.services.preferences import (
    AutomationSettings,
    ContactPreferenceOverride,
    ContactPreferenceOverrideUpdate,
    Intent,
    PreferencesService,
    PreferencesUpdate,
    StyleProfile,
    default_preferences,
    get_effective_preferences,
    merge_effective,
)
from tests.preferences.conftest import (
    OPENAPI,
    assert_contract,
    full_update,
    new_contact,
    new_user,
)


def test_default_preferences_match_contract_example() -> None:
    example = json.loads(
        (OPENAPI.parent / "examples" / "preferences.defaults.response.json").read_text()
    )
    assert default_preferences().model_dump(mode="json") == example


def test_merge_is_field_by_field_and_pure() -> None:
    base = PreferencesUpdate.model_validate(full_update())
    user_wide = default_preferences().model_copy(
        update={
            "version": 4,
            "intent": base.intent,
            "style": base.style,
            "automation": base.automation,
            "dealbreakers": list(base.dealbreakers),
        }
    )
    snapshot = user_wide.model_dump()
    override = ContactPreferenceOverride(
        version=1,
        style=StyleProfile(tone=Tone.RESERVED),
        automation_mode=AutomationMode.MANUAL,
    )
    eff = merge_effective(user_wide, override)
    assert eff.intent == user_wide.intent  # null override field inherits
    assert eff.style.tone is Tone.RESERVED
    assert eff.style.brevity == user_wide.style.brevity
    assert eff.style.style_samples == user_wide.style.style_samples
    assert eff.automation.mode is AutomationMode.MANUAL
    assert eff.automation.daily_send_limit == user_wide.automation.daily_send_limit
    assert eff.version == 4
    assert user_wide.model_dump() == snapshot  # inputs never modified
    assert merge_effective(user_wide, None) == user_wide
    assert merge_effective(user_wide, ContactPreferenceOverride(version=3)) == user_wide
    assert_contract("Preferences", eff.model_dump(mode="json"))


def test_models_reject_bad_values_directly() -> None:
    with pytest.raises(ValidationError):
        Intent(mode=IntentMode.CUSTOM)
    with pytest.raises(ValidationError):
        Intent.model_validate({"mode": "hookup"})
    with pytest.raises(ValidationError):
        AutomationSettings.model_validate({"mode": "manual", "extra": 1})
    with pytest.raises(ValidationError):
        StyleProfile.model_validate({"tone": "sarcastic"})
    with pytest.raises(ValidationError):
        ContactPreferenceOverrideUpdate.model_validate({"dealbreakers": []})


async def test_get_effective_preferences_for_consumers(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    scope = await new_user(session_factory)
    contact = await new_contact(session_factory, scope)
    other_contact = await new_contact(session_factory, scope)

    async with session_scope(session_factory) as s:
        eff = await get_effective_preferences(s, scope, contact)
        assert eff == default_preferences()
        assert eff.automation.mode is AutomationMode.MANUAL

    async with session_scope(session_factory) as s:
        svc = PreferencesService(s, scope)
        await svc.put_user_preferences(PreferencesUpdate.model_validate(full_update()))
        await svc.put_contact_override(
            contact,
            ContactPreferenceOverrideUpdate(
                intent=Intent(mode=IntentMode.CASUAL_PHYSICAL),
                automation_mode=AutomationMode.MANUAL,
            ),
        )

    async with session_scope(session_factory) as s:
        eff = await get_effective_preferences(s, scope, contact)
        assert eff.intent.mode is IntentMode.CASUAL_PHYSICAL
        assert eff.automation.mode is AutomationMode.MANUAL
        # Another contact gets the user-wide settings.
        other = await get_effective_preferences(s, scope, other_contact)
        assert other.intent.mode is IntentMode.SERIOUS
        assert other.automation.mode is AutomationMode.ASSISTED
        assert await get_effective_preferences(s, scope) == other
        with pytest.raises(NotFoundError):
            await get_effective_preferences(s, scope, uuid.uuid4())

    # Another user's contact behaves like a missing one.
    stranger = await new_user(session_factory)
    async with session_scope(session_factory) as s:
        with pytest.raises(NotFoundError):
            await get_effective_preferences(s, stranger, contact)


async def test_service_version_conflict(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    scope = await new_user(session_factory)
    update = PreferencesUpdate.model_validate(full_update())
    async with session_scope(session_factory) as s:
        svc = PreferencesService(s, scope)
        assert (await svc.put_user_preferences(update, expected_version=0)).version == 1
        with pytest.raises(VersionConflictError):
            await svc.put_user_preferences(update, expected_version=0)


async def test_style_snapshots_are_immutable_per_version(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    scope = await new_user(session_factory)
    async with session_scope(session_factory) as s:
        svc = PreferencesService(s, scope)
        await svc.put_user_preferences(
            PreferencesUpdate.model_validate(full_update(style={"tone": "warm"}))
        )
        await svc.put_user_preferences(
            PreferencesUpdate.model_validate(full_update(style={"tone": "formal"}))
        )
        page = await svc.list_user_versions()
    assert [(p.version, p.style.tone) for p in page.items] == [(2, Tone.FORMAL), (1, Tone.WARM)]
