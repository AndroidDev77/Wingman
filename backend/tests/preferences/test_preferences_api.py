"""HTTP behaviour of the WING-007 preference router against the frozen contract."""

import copy
import json
import uuid
from typing import Any

import httpx
import pytest
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db.models import AgentAction, UserPreference
from app.db.pagination import CursorPosition, encode_cursor
from app.db.scope import UserScope
from app.db.session import create_engine, create_session_factory
from app.security.deps import get_current_user_scope
from tests.preferences.conftest import (
    OPENAPI,
    as_user,
    assert_contract,
    assert_problem,
    build_app,
    client_for,
    full_update,
    new_contact,
    new_user,
)

STYLE_KEYS = ("punctuation", "emoji_frequency", "brevity", "humor", "tone", "response_pace")


# --------------------------------------------------------------------------- defaults


async def test_defaults_are_safe_and_nothing_is_inferred(
    client: httpx.AsyncClient, user: UserScope, contact_id: uuid.UUID
) -> None:
    resp = await client.get("/v1/preferences", headers=as_user(user))
    assert resp.status_code == 200
    body = resp.json()
    assert_contract("Preferences", body)
    assert body["version"] == 0
    assert body["updated_at"] is None
    assert body["automation"]["mode"] == "manual"
    assert body["automation"]["active_hours"] is None
    assert body["automation"]["reply_delay_seconds"] is None
    assert body["automation"]["daily_send_limit"] is None
    assert body["automation"]["approval_required_for"] == []
    assert all(body["style"][k] is None for k in STYLE_KEYS)
    assert body["style"]["style_samples"] == []
    assert body["dealbreakers"] == []
    # The contract's documented default intent (examples/preferences.defaults.response.json).
    assert body["intent"] == {"mode": "open", "custom_description": None}

    resp = await client.get(f"/v1/contacts/{contact_id}/preferences", headers=as_user(user))
    assert resp.status_code == 200
    cp = resp.json()
    assert_contract("ContactPreferences", cp)
    assert cp["override"]["version"] == 0
    assert cp["override"]["intent"] is None
    assert cp["override"]["style"] is None
    assert cp["override"]["automation_mode"] is None
    assert cp["effective"] == body

    resp = await client.get("/v1/preferences/versions", headers=as_user(user))
    assert resp.json() == {"items": [], "next_cursor": None}


async def test_partial_style_stays_null_not_inferred(
    client: httpx.AsyncClient, user: UserScope
) -> None:
    body = full_update(style={"tone": "direct"})
    resp = await client.put("/v1/preferences", json=body, headers=as_user(user))
    assert resp.status_code == 200, resp.text
    style = resp.json()["style"]
    assert style["tone"] == "direct"
    assert all(style[k] is None for k in STYLE_KEYS if k != "tone")
    assert style["style_samples"] == []


async def test_other_users_style_is_never_used(
    client: httpx.AsyncClient, session_factory: async_sessionmaker[AsyncSession]
) -> None:
    a = await new_user(session_factory)
    b = await new_user(session_factory)
    assert (await client.put("/v1/preferences", json=full_update(), headers=as_user(a))).is_success
    resp = await client.get("/v1/preferences", headers=as_user(b))
    assert resp.json()["version"] == 0
    assert all(resp.json()["style"][k] is None for k in STYLE_KEYS)
    assert resp.json()["automation"]["mode"] == "manual"


# --------------------------------------------------------------------------- user-wide writes


async def test_put_round_trip_and_contract_example(
    client: httpx.AsyncClient, user: UserScope
) -> None:
    body = full_update()
    assert_contract("PreferencesUpdate", body)
    resp = await client.put("/v1/preferences", json=body, headers=as_user(user))
    assert resp.status_code == 200, resp.text
    saved = resp.json()
    assert_contract("Preferences", saved)
    assert saved["version"] == 1
    assert saved["updated_at"].endswith("Z")
    for key in ("intent", "dealbreakers", "style", "automation"):
        assert saved[key] == body[key]
    got = (await client.get("/v1/preferences", headers=as_user(user))).json()
    assert got == saved


@pytest.mark.parametrize(
    "description", ["Something slow, starting as friends.", "x" * 500], ids=["text", "max"]
)
async def test_all_five_intent_modes_accepted(
    client: httpx.AsyncClient, user: UserScope, description: str
) -> None:
    version = 0
    for mode in ("serious", "casual_dating", "casual_physical", "open", "custom"):
        intent = {"mode": mode, "custom_description": description if mode == "custom" else None}
        resp = await client.put(
            "/v1/preferences",
            json=full_update(intent=intent),
            headers=as_user(user) | {"If-Match": f'"{version}"'},
        )
        assert resp.status_code == 200, resp.text
        version += 1
        assert resp.json()["intent"] == intent
        assert resp.json()["version"] == version


async def test_audit_entry_is_redacted(
    client: httpx.AsyncClient,
    user: UserScope,
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    await client.put("/v1/preferences", json=full_update(), headers=as_user(user))
    async with session_factory() as s:
        rows = (
            await s.scalars(select(AgentAction).where(AgentAction.user_id == user.user_id))
        ).all()
    assert [(r.type.value, r.actor.value) for r in rows] == [("preferences_updated", "user")]
    assert rows[0].details == {"scope": "user", "version": 1}


# --------------------------------------------------------------------------- validation


def _mutate(path: list[str | int], value: Any) -> dict[str, Any]:
    body = copy.deepcopy(full_update())
    target: Any = body
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = value
    return body


def _drop(path: list[str]) -> dict[str, Any]:
    body = copy.deepcopy(full_update())
    target: Any = body
    for key in path[:-1]:
        target = target[key]
    del target[path[-1]]
    return body


INVALID_BODIES: dict[str, tuple[dict[str, Any], str]] = {
    "unknown_intent": (_mutate(["intent", "mode"], "hookup"), "/intent/mode"),
    "custom_without_description": (
        _mutate(["intent"], {"mode": "custom", "custom_description": None}),
        "/intent",
    ),
    "custom_empty_description": (
        _mutate(["intent"], {"mode": "custom", "custom_description": ""}),
        "/intent",
    ),
    "custom_description_too_long": (
        _mutate(["intent"], {"mode": "custom", "custom_description": "x" * 501}),
        "/intent/custom_description",
    ),
    "description_without_custom": (
        _mutate(["intent"], {"mode": "serious", "custom_description": "hi"}),
        "/intent",
    ),
    "unknown_tone": (_mutate(["style", "tone"], "sarcastic"), "/style/tone"),
    "unknown_emoji_frequency": (
        _mutate(["style", "emoji_frequency"], "always"),
        "/style/emoji_frequency",
    ),
    "unknown_automation_mode": (_mutate(["automation", "mode"], "yolo"), "/automation/mode"),
    "unknown_approval_reason": (
        _mutate(["automation", "approval_required_for"], ["because"]),
        "/automation/approval_required_for/0",
    ),
    "duplicate_approval_reason": (
        _mutate(["automation", "approval_required_for"], ["first_message", "first_message"]),
        "/automation/approval_required_for",
    ),
    "unknown_top_level_field": (_mutate(["inferred_style"], {"tone": "warm"}), "/inferred_style"),
    "unknown_style_field": (_mutate(["style", "vibe"], "chill"), "/style/vibe"),
    "unknown_automation_field": (_mutate(["automation", "send_anyway"], True), "/automation"),
    "missing_style": (_drop(["style"]), "/style"),
    "missing_automation_mode": (_drop(["automation", "mode"]), "/automation/mode"),
    "reply_delay_inverted": (
        _mutate(["automation", "reply_delay_seconds"], {"min": 600, "max": 60}),
        "/automation/reply_delay_seconds",
    ),
    "reply_delay_too_large": (
        _mutate(["automation", "reply_delay_seconds"], {"min": 0, "max": 86401}),
        "/automation/reply_delay_seconds/max",
    ),
    "reply_delay_string": (
        _mutate(["automation", "reply_delay_seconds"], {"min": "1", "max": 5}),
        "/automation/reply_delay_seconds/min",
    ),
    "daily_limit_negative": (
        _mutate(["automation", "daily_send_limit"], -1),
        "/automation/daily_send_limit",
    ),
    "bad_timezone": (
        _mutate(["automation", "active_hours", "timezone"], "Mars/Olympus"),
        "/automation/active_hours/timezone",
    ),
    "bad_window_time": (
        _mutate(["automation", "active_hours", "windows", 0, "start"], "25:00"),
        "/automation/active_hours/windows/0/start",
    ),
    "bad_weekday": (
        _mutate(["automation", "active_hours", "windows", 0, "days"], ["funday"]),
        "/automation/active_hours/windows/0/days/0",
    ),
    "duplicate_weekday": (
        _mutate(["automation", "active_hours", "windows", 0, "days"], ["mon", "mon"]),
        "/automation/active_hours/windows/0/days",
    ),
    "empty_windows": (
        _mutate(["automation", "active_hours", "windows"], []),
        "/automation/active_hours/windows",
    ),
    "too_many_style_samples": (
        _mutate(["style", "style_samples"], ["hi"] * 21),
        "/style/style_samples",
    ),
    "empty_dealbreaker": (_mutate(["dealbreakers"], [""]), "/dealbreakers/0"),
    "too_many_dealbreakers": (_mutate(["dealbreakers"], ["x"] * 51), "/dealbreakers"),
    "intent_not_object": (_mutate(["intent"], "serious"), "/intent"),
}


@pytest.mark.parametrize("case", sorted(INVALID_BODIES))
async def test_invalid_user_wide_bodies_rejected(
    client: httpx.AsyncClient, user: UserScope, case: str
) -> None:
    body, pointer = INVALID_BODIES[case]
    resp = await client.put("/v1/preferences", json=body, headers=as_user(user))
    problem = assert_problem(resp, 422, "validation_error")
    pointers = [e["pointer"] for e in problem["errors"]]
    assert any(p.startswith(pointer) for p in pointers), pointers
    # The private submitted value is never echoed back.
    assert "hookup" not in resp.text and "chill" not in resp.text
    # Nothing was stored.
    after = await client.get("/v1/preferences", headers=as_user(user))
    assert after.json()["version"] == 0


async def test_contract_invalid_examples_rejected(
    client: httpx.AsyncClient, user: UserScope
) -> None:
    invalid_dir = OPENAPI.parent / "examples" / "invalid"
    for name in ("preferences.unknown_intent.json", "preferences.custom_without_description.json"):
        body = json.loads((invalid_dir / name).read_text())
        resp = await client.put("/v1/preferences", json=body, headers=as_user(user))
        assert_problem(resp, 422, "validation_error")


async def test_malformed_json_rejected(client: httpx.AsyncClient, user: UserScope) -> None:
    resp = await client.put(
        "/v1/preferences",
        content=b'{"intent": ',
        headers=as_user(user) | {"content-type": "application/json"},
    )
    assert_problem(resp, 422, "validation_error")


@pytest.mark.parametrize("if_match", ["3", '"abc"', '"-1"', 'W/"1"', '""'])
async def test_malformed_if_match_rejected(
    client: httpx.AsyncClient, user: UserScope, if_match: str
) -> None:
    resp = await client.put(
        "/v1/preferences", json=full_update(), headers=as_user(user) | {"If-Match": if_match}
    )
    problem = assert_problem(resp, 422, "validation_error")
    assert problem["errors"][0]["pointer"] == "header:If-Match"


async def test_unauthenticated_is_problem_json(client: httpx.AsyncClient) -> None:
    resp = await client.get("/v1/preferences")
    assert_problem(resp, 401, "unauthenticated")


async def test_real_auth_stub_fails_closed(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """Without the test override, the D-007 stub rejects every call."""
    app = build_app(session_factory)
    del app.dependency_overrides[get_current_user_scope]
    async with client_for(app) as c:
        assert_problem(await c.get("/v1/preferences"), 401, "unauthenticated")
        assert_problem(await c.put("/v1/preferences", json=full_update()), 401, "unauthenticated")


# --------------------------------------------------------------------------- If-Match


async def test_if_match_optimistic_concurrency(
    client: httpx.AsyncClient,
    user: UserScope,
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    h = as_user(user)
    # "0" = never saved.
    r1 = await client.put("/v1/preferences", json=full_update(), headers=h | {"If-Match": '"0"'})
    assert r1.status_code == 200 and r1.json()["version"] == 1
    r2 = await client.put("/v1/preferences", json=full_update(), headers=h | {"If-Match": '"1"'})
    assert r2.status_code == 200 and r2.json()["version"] == 2
    # Stale writer loses.
    stale = await client.put(
        "/v1/preferences",
        json=full_update(dealbreakers=["lost update"]),
        headers=h | {"If-Match": '"1"'},
    )
    assert_problem(stale, 412, "precondition_failed")
    ahead = await client.put("/v1/preferences", json=full_update(), headers=h | {"If-Match": '"9"'})
    assert_problem(ahead, 412, "precondition_failed")
    current = (await client.get("/v1/preferences", headers=h)).json()
    assert current["version"] == 2
    assert current["dealbreakers"] == ["smoking"]
    # Without If-Match the write is unconditional.
    r3 = await client.put("/v1/preferences", json=full_update(), headers=h)
    assert r3.json()["version"] == 3
    # The 412s left no rows behind (no orphan version or audit entry).
    async with session_factory() as s:
        n = await s.scalar(
            select(func.count())
            .select_from(UserPreference)
            .where(UserPreference.user_id == user.user_id)
        )
        audits = await s.scalar(
            select(func.count()).select_from(AgentAction).where(AgentAction.user_id == user.user_id)
        )
    assert (n, audits) == (3, 3)


async def test_contact_if_match(
    client: httpx.AsyncClient, user: UserScope, contact_id: uuid.UUID
) -> None:
    url = f"/v1/contacts/{contact_id}/preferences"
    h = as_user(user)
    ok = await client.put(url, json={"automation_mode": "manual"}, headers=h | {"If-Match": '"0"'})
    assert ok.status_code == 200 and ok.json()["override"]["version"] == 1
    stale = await client.put(
        url, json={"automation_mode": "assisted"}, headers=h | {"If-Match": '"0"'}
    )
    assert_problem(stale, 412, "precondition_failed")
    # User-wide versions are independent of the contact's.
    uw = await client.put("/v1/preferences", json=full_update(), headers=h | {"If-Match": '"0"'})
    assert uw.status_code == 200


# --------------------------------------------------------------------------- versions


async def test_version_history_order_and_pagination(
    client: httpx.AsyncClient, user: UserScope
) -> None:
    h = as_user(user)
    limits = list(range(5))
    for n in limits:
        body = full_update(automation={"mode": "manual", "daily_send_limit": n})
        assert (await client.put("/v1/preferences", json=body, headers=h)).status_code == 200

    seen: list[int] = []
    cursor: str | None = None
    pages = 0
    while True:
        params: dict[str, Any] = {"limit": 2}
        if cursor:
            params["cursor"] = cursor
        resp = await client.get("/v1/preferences/versions", params=params, headers=h)
        assert resp.status_code == 200
        page = resp.json()
        assert_contract("PreferencesPage", page)
        assert len(page["items"]) <= 2
        seen.extend(item["version"] for item in page["items"])
        # Each historical version keeps its own content.
        for item in page["items"]:
            assert item["automation"]["daily_send_limit"] == item["version"] - 1
        pages += 1
        cursor = page["next_cursor"]
        if cursor is None:
            break
    assert seen == [5, 4, 3, 2, 1]
    assert pages == 3

    full = (await client.get("/v1/preferences/versions", headers=h)).json()
    assert [i["version"] for i in full["items"]] == [5, 4, 3, 2, 1]
    assert full["next_cursor"] is None


async def test_contact_overrides_do_not_appear_in_user_history(
    client: httpx.AsyncClient, user: UserScope, contact_id: uuid.UUID
) -> None:
    h = as_user(user)
    await client.put("/v1/preferences", json=full_update(), headers=h)
    await client.put(
        f"/v1/contacts/{contact_id}/preferences", json={"automation_mode": "manual"}, headers=h
    )
    items = (await client.get("/v1/preferences/versions", headers=h)).json()["items"]
    assert [i["version"] for i in items] == [1]


@pytest.mark.parametrize(
    ("params", "pointer"),
    [
        ({"cursor": "not-a-cursor"}, "query:cursor"),
        ({"cursor": "x" * 513}, "query:cursor"),
        ({"limit": 0}, "query:limit"),
        ({"limit": 101}, "query:limit"),
        ({"limit": "ten"}, "query:limit"),
    ],
)
async def test_invalid_paging_params(
    client: httpx.AsyncClient, user: UserScope, params: dict[str, Any], pointer: str
) -> None:
    resp = await client.get("/v1/preferences/versions", params=params, headers=as_user(user))
    problem = assert_problem(resp, 422, "validation_error")
    assert problem["errors"][0]["pointer"] == pointer


async def test_cursor_from_another_listing_rejected(
    client: httpx.AsyncClient, user: UserScope
) -> None:
    foreign = encode_cursor("messages", CursorPosition(1, uuid.uuid4()))
    resp = await client.get(
        "/v1/preferences/versions", params={"cursor": foreign}, headers=as_user(user)
    )
    assert_problem(resp, 422, "validation_error")


# --------------------------------------------------------------------------- per contact


async def test_contact_override_beats_user_wide_without_modifying_it(
    client: httpx.AsyncClient, user: UserScope, contact_id: uuid.UUID
) -> None:
    h = as_user(user)
    user_wide = (await client.put("/v1/preferences", json=full_update(), headers=h)).json()

    override = {
        "intent": {"mode": "custom", "custom_description": "Keep it light; we met at a gym."},
        "style": {"tone": "playful", "emoji_frequency": "frequent"},
        "automation_mode": "manual",
    }
    assert_contract("ContactPreferenceOverrideUpdate", override)
    resp = await client.put(f"/v1/contacts/{contact_id}/preferences", json=override, headers=h)
    assert resp.status_code == 200, resp.text
    cp = resp.json()
    assert_contract("ContactPreferences", cp)
    assert cp["contact_id"] == str(contact_id)
    assert cp["override"]["version"] == 1
    assert cp["override"]["updated_at"].endswith("Z")
    assert cp["override"]["intent"] == override["intent"]
    assert cp["override"]["automation_mode"] == "manual"
    # Unset override style attributes stay null in the override.
    assert cp["override"]["style"]["punctuation"] is None

    eff = cp["effective"]
    assert eff["intent"] == override["intent"]
    assert eff["automation"]["mode"] == "manual"
    # Style merges attribute by attribute.
    assert eff["style"]["tone"] == "playful"
    assert eff["style"]["emoji_frequency"] == "frequent"
    assert eff["style"]["brevity"] == user_wide["style"]["brevity"]
    assert eff["style"]["style_samples"] == user_wide["style"]["style_samples"]
    # User-wide-only settings flow through.
    assert eff["dealbreakers"] == user_wide["dealbreakers"]
    assert eff["automation"]["active_hours"] == user_wide["automation"]["active_hours"]
    assert eff["automation"]["daily_send_limit"] == 30
    assert (eff["version"], eff["updated_at"]) == (user_wide["version"], user_wide["updated_at"])

    # User-wide defaults untouched.
    again = (await client.get("/v1/preferences", headers=h)).json()
    assert again == user_wide

    # Updating user-wide later still respects the override where set.
    newer = full_update(
        intent={"mode": "casual_dating", "custom_description": None},
        style={"brevity": "long", "tone": "formal"},
    )
    await client.put("/v1/preferences", json=newer, headers=h)
    eff2 = (await client.get(f"/v1/contacts/{contact_id}/preferences", headers=h)).json()[
        "effective"
    ]
    assert eff2["intent"] == override["intent"]
    assert eff2["style"]["tone"] == "playful"
    assert eff2["style"]["brevity"] == "long"
    assert eff2["version"] == 2


async def test_override_with_nulls_inherits(
    client: httpx.AsyncClient, user: UserScope, contact_id: uuid.UUID
) -> None:
    h = as_user(user)
    user_wide = (await client.put("/v1/preferences", json=full_update(), headers=h)).json()
    resp = await client.put(
        f"/v1/contacts/{contact_id}/preferences",
        json={"intent": None, "style": None, "automation_mode": None},
        headers=h,
    )
    assert resp.status_code == 200
    cp = resp.json()
    assert cp["override"]["version"] == 1
    assert cp["effective"] == user_wide
    # Empty object is also a valid "inherit everything" body.
    resp = await client.put(f"/v1/contacts/{contact_id}/preferences", json={}, headers=h)
    assert resp.status_code == 200 and resp.json()["override"]["version"] == 2


async def test_override_on_defaults_never_enables_automation_by_itself(
    client: httpx.AsyncClient, user: UserScope, contact_id: uuid.UUID
) -> None:
    h = as_user(user)
    resp = await client.put(
        f"/v1/contacts/{contact_id}/preferences",
        json={"intent": {"mode": "serious", "custom_description": None}},
        headers=h,
    )
    eff = resp.json()["effective"]
    assert eff["automation"]["mode"] == "manual"
    assert eff["intent"]["mode"] == "serious"
    assert eff["version"] == 0
    assert (await client.get("/v1/preferences", headers=h)).json()["version"] == 0


async def test_delete_override_reverts_to_user_wide(
    client: httpx.AsyncClient, user: UserScope, contact_id: uuid.UUID
) -> None:
    h = as_user(user)
    url = f"/v1/contacts/{contact_id}/preferences"
    user_wide = (await client.put("/v1/preferences", json=full_update(), headers=h)).json()
    await client.put(
        url,
        json={"intent": {"mode": "open", "custom_description": None}, "automation_mode": "manual"},
        headers=h,
    )
    resp = await client.delete(url, headers=h)
    assert resp.status_code == 204
    assert resp.content == b""
    cp = (await client.get(url, headers=h)).json()
    assert_contract("ContactPreferences", cp)
    assert cp["override"]["intent"] is None
    assert cp["override"]["style"] is None
    assert cp["override"]["automation_mode"] is None
    assert cp["override"]["version"] == 2  # clearing is recorded as a new version
    assert cp["effective"] == user_wide
    # Idempotent: deleting again (or when nothing existed) is 204 and adds no version.
    assert (await client.delete(url, headers=h)).status_code == 204
    assert (await client.get(url, headers=h)).json()["override"]["version"] == 2


async def test_delete_when_no_override_existed(
    client: httpx.AsyncClient, user: UserScope, contact_id: uuid.UUID
) -> None:
    url = f"/v1/contacts/{contact_id}/preferences"
    assert (await client.delete(url, headers=as_user(user))).status_code == 204
    assert (await client.get(url, headers=as_user(user))).json()["override"]["version"] == 0


@pytest.mark.parametrize(
    ("body", "pointer"),
    [
        ({"intent": {"mode": "hookup"}}, "/intent/mode"),
        ({"intent": {"mode": "custom"}}, "/intent"),
        ({"intent": {"mode": "custom", "custom_description": ""}}, "/intent"),
        ({"style": {"humor": "dark"}}, "/style/humor"),
        ({"automation_mode": "always"}, "/automation_mode"),
        ({"dealbreakers": ["x"]}, "/dealbreakers"),
        ({"automation": {"mode": "autopilot"}}, "/automation"),
    ],
)
async def test_invalid_override_bodies_rejected(
    client: httpx.AsyncClient,
    user: UserScope,
    contact_id: uuid.UUID,
    body: dict[str, Any],
    pointer: str,
) -> None:
    url = f"/v1/contacts/{contact_id}/preferences"
    resp = await client.put(url, json=body, headers=as_user(user))
    problem = assert_problem(resp, 422, "validation_error")
    assert any(e["pointer"].startswith(pointer) for e in problem["errors"]), problem
    assert (await client.get(url, headers=as_user(user))).json()["override"]["version"] == 0


async def test_unknown_and_malformed_contact_ids(
    client: httpx.AsyncClient, user: UserScope
) -> None:
    h = as_user(user)
    missing = f"/v1/contacts/{uuid.uuid4()}/preferences"
    assert_problem(await client.get(missing, headers=h), 404, "not_found")
    assert_problem(await client.put(missing, json={}, headers=h), 404, "not_found")
    assert_problem(await client.delete(missing, headers=h), 404, "not_found")
    bad = await client.get("/v1/contacts/not-a-uuid/preferences", headers=h)
    problem = assert_problem(bad, 422, "validation_error")
    assert problem["errors"][0]["pointer"] == "path:contact_id"


# --------------------------------------------------------------------------- isolation


async def test_cross_user_isolation(
    client: httpx.AsyncClient, session_factory: async_sessionmaker[AsyncSession]
) -> None:
    alice = await new_user(session_factory)
    bob = await new_user(session_factory)
    alice_contact = await new_contact(session_factory, alice)
    url = f"/v1/contacts/{alice_contact}/preferences"

    await client.put("/v1/preferences", json=full_update(), headers=as_user(alice))
    await client.put(url, json={"automation_mode": "manual"}, headers=as_user(alice))

    # Bob sees none of Alice's data and cannot touch her contact.
    assert (await client.get("/v1/preferences", headers=as_user(bob))).json()["version"] == 0
    bob_versions = await client.get("/v1/preferences/versions", headers=as_user(bob))
    assert bob_versions.json()["items"] == []
    not_found = [
        await client.get(url, headers=as_user(bob)),
        await client.put(url, json={"automation_mode": "autopilot"}, headers=as_user(bob)),
        await client.delete(url, headers=as_user(bob)),
    ]
    for resp in not_found:
        body = assert_problem(resp, 404, "not_found")
        # Same body as a non-existent contact: existence is not leaked.
        assert "detail" not in body

    # Alice's override is intact after Bob's attempts.
    cp = (await client.get(url, headers=as_user(alice))).json()
    assert cp["override"]["version"] == 1
    assert cp["override"]["automation_mode"] == "manual"

    # A cursor from Alice's history does not reveal her versions to Bob.
    for _ in range(2):
        await client.put("/v1/preferences", json=full_update(), headers=as_user(alice))
    alice_page = (
        await client.get("/v1/preferences/versions", params={"limit": 1}, headers=as_user(alice))
    ).json()
    assert alice_page["next_cursor"]
    bob_page = await client.get(
        "/v1/preferences/versions",
        params={"cursor": alice_page["next_cursor"]},
        headers=as_user(bob),
    )
    assert bob_page.status_code == 200
    assert bob_page.json()["items"] == []


# --------------------------------------------------------------------------- restart


async def test_preferences_survive_restart(
    prefs_db_url: str, session_factory: async_sessionmaker[AsyncSession]
) -> None:
    scope = await new_user(session_factory)
    contact = await new_contact(session_factory, scope)
    h = as_user(scope)
    async with client_for(build_app(session_factory)) as c:
        saved = (await c.put("/v1/preferences", json=full_update(), headers=h)).json()
        override = (
            await c.put(
                f"/v1/contacts/{contact}/preferences",
                json={"automation_mode": "manual", "style": {"tone": "reserved"}},
                headers=h,
            )
        ).json()

    # "Restart": a brand-new engine, pool and app against the same database.
    engine2 = create_engine(prefs_db_url)
    try:
        async with client_for(build_app(create_session_factory(engine2))) as c2:
            assert (await c2.get("/v1/preferences", headers=h)).json() == saved
            assert (await c2.get(f"/v1/contacts/{contact}/preferences", headers=h)).json() == (
                override
            )
            versions = (await c2.get("/v1/preferences/versions", headers=h)).json()
            assert [v["version"] for v in versions["items"]] == [1]
    finally:
        await engine2.dispose()
