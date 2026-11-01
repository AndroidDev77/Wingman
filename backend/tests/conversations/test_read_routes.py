"""I-021: ``listConversations``, ``getConversation`` and ``listMessages`` over HTTP,
exactly as in ``contracts/openapi.yaml`` v1.2.0. Every 200 body is validated against the
contract schema; every error is a contract ``Problem``."""

import uuid
from datetime import timedelta
from typing import Any

import httpx
import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import (
    ContactAutomationStatus,
    ConversationStage,
    GlobalAutomationStatus,
)
from app.db.models import Conversation
from app.db.repositories import (
    ContactRepository,
    ConversationRepository,
    MessageRepository,
    UserRepository,
)
from app.services.preferences import ContactPreferenceOverrideUpdate, PreferencesService
from tests.automation.conftest import (
    NOW,
    World,
    assert_contract,
    headers,
    make_world,
    problem,
    set_mode,
)
from tests.automation.test_device_routes import claim, post_event

# --------------------------------------------------------------------------- helpers


def h(world: World) -> dict[str, str]:
    return {"X-Test-User": str(world.scope.user_id)}


async def inbox(client: httpx.AsyncClient, world: World, **params: Any) -> httpx.Response:
    resp = await client.get("/v1/conversations", params=params, headers=h(world))
    if resp.status_code == 200:
        assert_contract("ConversationPage", resp.json())
    return resp


async def detail(client: httpx.AsyncClient, world: World, conversation_id: Any) -> httpx.Response:
    resp = await client.get(f"/v1/conversations/{conversation_id}", headers=h(world))
    if resp.status_code == 200:
        assert_contract("ConversationDetail", resp.json())
    return resp


async def messages(
    client: httpx.AsyncClient, world: World, conversation_id: Any, **params: Any
) -> httpx.Response:
    resp = await client.get(
        f"/v1/conversations/{conversation_id}/messages", params=params, headers=h(world)
    )
    if resp.status_code == 200:
        assert_contract("MessagePage", resp.json())
    return resp


async def all_pages(fetch: Any, *, limit: int, between: Any = None) -> list[dict[str, Any]]:
    """Follow ``next_cursor`` to the end. ``between`` runs after each page (inserts)."""
    out: list[dict[str, Any]] = []
    cursor: str | None = None
    while True:
        params: dict[str, Any] = {"limit": limit}
        if cursor is not None:
            params["cursor"] = cursor
        resp = await fetch(**params)
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert len(body["items"]) <= limit
        out += body["items"]
        cursor = body["next_cursor"]
        if cursor is None:
            return out
        if between is not None:
            await between()


async def seed_conversation(
    session: AsyncSession,
    world: World,
    *,
    name: str,
    activity: Any,
    connection_id: uuid.UUID | None = None,
    platform: str | None = None,
    stage: ConversationStage = ConversationStage.NEW,
) -> Conversation:
    conn = connection_id or world.connection_id
    contact = await ContactRepository(session, world.scope).get_or_create(
        connection_id=conn, external_contact_id=f"ext-{name}", display_name=name
    )
    return await ConversationRepository(session, world.scope).create(
        contact_id=contact.id,
        connection_id=conn,
        platform=platform or world.platform,
        external_thread_id=f"thread-{name}",
        stage=stage,
        last_activity_at=activity,
    )


async def seed_inbound(
    session: AsyncSession, world: World, conversation: Conversation, text: str, at: Any
) -> uuid.UUID:
    result = await MessageRepository(session, world.scope).ingest_inbound(
        conversation_id=conversation.id,
        platform=conversation.platform,
        external_message_id=f"ext-{uuid.uuid4().hex}",
        text=text,
        sent_at_source=at,
    )
    return result.message.id


async def first_conversation(client: httpx.AsyncClient, world: World) -> dict[str, Any]:
    [item] = (await inbox(client, world)).json()["items"]
    return item  # type: ignore[no-any-return]


# --------------------------------------------------------------------------- contract shapes


async def test_inbox_detail_and_messages_after_an_inbound_message(
    session: AsyncSession, make_conv_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="assisted")
    client = make_conv_client(world)
    raw = world.event("Just got back from Lisbon!")
    assert (await post_event(client, world, raw)).status_code == 202

    item = await first_conversation(client, world)
    assert item["contact"]["display_name"] == "Sam"
    assert item["connection_id"] == str(world.connection_id)
    assert item["platform"] == "mock"
    assert item["external_thread_id"] == "thread-1"
    assert item["unread_count"] == 1
    assert item["pending_approval_count"] == 1  # assisted: the draft waits for approval
    assert item["effective_intent"] == "open"
    assert item["automation"] == {
        "effective_mode": "assisted",
        "contact_status": "active",
        "global_status": "active",
    }
    assert item["last_message_preview"] == "Just got back from Lisbon!"
    assert item["last_activity_at"] == raw["occurred_at"]
    assert "analysis" not in item  # the inbox row is a plain Conversation

    one = (await detail(client, world, item["id"])).json()
    analysis = one.pop("analysis")
    assert one == item
    # The stored advisory analysis (WING-009), exactly as stored.
    assert analysis is not None and analysis["stage"] == item["stage"]
    assert_contract("ConversationAnalysis", analysis)
    stored = await session.get(Conversation, uuid.UUID(item["id"]))
    assert stored is not None and analysis == stored.analysis

    page = (await messages(client, world, item["id"])).json()
    assert page["next_cursor"] is None
    [msg] = page["items"]
    assert msg["direction"] == "inbound" and msg["origin"] == "contact"
    assert msg["delivery_state"] == "received"
    assert msg["text"] == "Just got back from Lisbon!"
    assert msg["created_at"] == raw["occurred_at"]
    assert msg["sent_at"] is None and msg["delivered_at"] is None

    # Reads change nothing: the contract has no mark-read operation.
    again = await first_conversation(client, world)
    assert again["unread_count"] == 1


async def test_detail_analysis_is_null_before_any_analysis(
    session: AsyncSession, make_conv_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conv_client(world)
    conv = await seed_conversation(session, world, name="Ana", activity=NOW)
    body = (await detail(client, world, conv.id)).json()
    assert body["analysis"] is None
    assert body["stage"] == "new"
    assert body["last_message_preview"] is None
    assert body["unread_count"] == 0 and body["pending_approval_count"] == 0
    # A never-saved user gets the safe defaults (contract: manual, intent open).
    assert body["effective_intent"] == "open"
    assert body["automation"]["effective_mode"] == "manual"
    assert (await messages(client, world, conv.id)).json() == {"items": [], "next_cursor": None}


async def test_effective_automation_reflects_pause_and_takeover(
    session: AsyncSession, make_conv_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="autopilot")
    client = make_conv_client(world)
    conv = await seed_conversation(session, world, name="Bo", activity=NOW)
    contacts = ContactRepository(session, world.scope)
    await contacts.set_automation_status(conv.contact_id, ContactAutomationStatus.PAUSED)
    await UserRepository(session).set_global_automation_status(
        world.scope, GlobalAutomationStatus.PAUSED
    )
    body = (await detail(client, world, conv.id)).json()
    assert body["automation"] == {
        "effective_mode": "autopilot",
        "contact_status": "paused",
        "global_status": "paused",
    }
    await contacts.set_automation_status(conv.contact_id, ContactAutomationStatus.TAKEN_OVER)
    [row] = (await inbox(client, world)).json()["items"]
    assert row["automation"]["contact_status"] == "taken_over"
    assert row["automation"]["global_status"] == "paused"


# --------------------------------------------------------------------------- delivery honesty


async def test_delivery_states_are_reported_as_observed(
    session: AsyncSession, make_conv_client
) -> None:  # type: ignore[no-untyped-def]
    """queued -> sending -> sent -> delivered on a device connection: each state only
    after the matching event, ``sent_at``/``delivered_at`` only once true."""
    world = await make_world(session, platform="sms", device_side=True, mode="manual")
    client = make_conv_client(world)
    assert (await post_event(client, world, world.event("hi"))).status_code == 202
    conv_id = (await first_conversation(client, world))["id"]
    sent = await client.post(
        f"/v1/conversations/{conv_id}/messages", json={"text": "hey!"}, headers=headers(world.scope)
    )
    assert sent.status_code == 202

    async def outbound() -> dict[str, Any]:
        items = (await messages(client, world, conv_id)).json()["items"]
        [out] = [m for m in items if m["direction"] == "outbound"]
        return out  # type: ignore[no-any-return]

    queued = await outbound()
    assert queued["delivery_state"] == "queued" and queued["origin"] == "user_manual"
    assert queued["sent_at"] is None and queued["delivered_at"] is None
    assert queued["external_message_id"] is None

    [ins] = (await claim(client, world)).json()["instructions"]
    sending = await outbound()
    assert sending["delivery_state"] == "sending"
    assert sending["sent_at"] is None and sending["delivered_at"] is None

    world.clock.advance(seconds=5)
    ack = world.outcome("ack", message_id=uuid.UUID(queued["id"]), key=ins["idempotency_key"])
    assert (await post_event(client, world, ack)).status_code == 202
    acked = await outbound()
    assert acked["delivery_state"] == "sent"
    assert acked["sent_at"] == ack["occurred_at"] and acked["delivered_at"] is None
    assert acked["external_message_id"] == ack["external_message_id"]

    world.clock.advance(seconds=5)
    receipt = world.outcome(
        "receipt",
        message_id=uuid.UUID(queued["id"]),
        external_message_id=ack["external_message_id"],
    )
    assert (await post_event(client, world, receipt)).status_code == 202
    delivered = await outbound()
    assert delivered["delivery_state"] == "delivered"
    assert delivered["sent_at"] == ack["occurred_at"]
    assert delivered["delivered_at"] == receipt["occurred_at"]


async def test_failed_and_canceled_sends_are_reported_honestly(
    session: AsyncSession, make_conv_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="manual")
    client = make_conv_client(world)
    assert world.connector is not None
    assert (await post_event(client, world, world.event("hi"))).status_code == 202
    conv_id = (await first_conversation(client, world))["id"]
    world.connector.inject_send_failures(1, retryable=False)
    resp = await client.post(
        f"/v1/conversations/{conv_id}/messages", json={"text": "x"}, headers=headers(world.scope)
    )
    assert resp.status_code == 202
    items = (await messages(client, world, conv_id)).json()["items"]
    [out] = [m for m in items if m["direction"] == "outbound"]
    assert out["delivery_state"] == "dead_lettered"
    assert out["sent_at"] is None and out["delivered_at"] is None
    assert out["failure"] is not None and out["failure"]["retryable"] is False
    assert out["attempt_count"] == 1


# --------------------------------------------------------------------------- ordering + pages


async def test_inbox_is_newest_activity_first_with_id_tiebreak(
    session: AsyncSession, make_conv_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conv_client(world)
    same = NOW - timedelta(hours=1)
    seeded = [
        await seed_conversation(
            session, world, name=f"c{i}", activity=same if i < 4 else NOW - timedelta(minutes=i)
        )
        for i in range(9)
    ]
    expected = sorted(seeded, key=lambda c: (c.last_activity_at, c.id), reverse=True)
    got = await all_pages(lambda **p: inbox(client, world, **p), limit=2)
    assert [g["id"] for g in got] == [str(c.id) for c in expected]  # no gaps, no duplicates


async def test_inbox_pages_are_stable_under_inserts(
    session: AsyncSession, make_conv_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conv_client(world)
    seeded = [
        await seed_conversation(session, world, name=f"s{i}", activity=NOW - timedelta(minutes=i))
        for i in range(7)
    ]
    counter = iter(range(100))

    async def insert_newer() -> None:
        # New activity lands before the cursor (at the top): never re-served, never
        # shifts the rest of the walk.
        await seed_conversation(
            session, world, name=f"new{next(counter)}", activity=NOW + timedelta(hours=1)
        )

    got = await all_pages(lambda **p: inbox(client, world, **p), limit=3, between=insert_newer)
    ids = [g["id"] for g in got]
    assert ids == [str(c.id) for c in seeded]
    assert len(set(ids)) == len(ids)


async def test_messages_are_oldest_first_and_stable_under_inserts(
    session: AsyncSession, make_conv_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conv_client(world)
    conv = await seed_conversation(session, world, name="M", activity=NOW)
    base = NOW - timedelta(hours=2)
    # Two share a timestamp: the id breaks the tie.
    stamps = [
        base,
        base + timedelta(minutes=1),
        base + timedelta(minutes=1),
        base + timedelta(minutes=5),
    ]
    stamps += [base + timedelta(minutes=10 + i) for i in range(4)]
    ids = [await seed_inbound(session, world, conv, f"m{i}", at) for i, at in enumerate(stamps)]
    expected = [str(i) for _, i in sorted(zip(stamps, ids, strict=True))]
    later: list[str] = []

    async def insert_newer() -> None:
        at = NOW + timedelta(minutes=len(later))
        later.append(str(await seed_inbound(session, world, conv, "late", at)))

    got = await all_pages(
        lambda **p: messages(client, world, conv.id, **p), limit=3, between=insert_newer
    )
    got_ids = [g["id"] for g in got]
    # Every pre-existing message exactly once, in order; newer ones follow (forward in time).
    assert got_ids[: len(expected)] == expected
    assert got_ids[len(expected) :] == later[: len(got_ids) - len(expected)]
    assert len(set(got_ids)) == len(got_ids)


@pytest.mark.parametrize("limit", ["0", "101", "x"])
async def test_limit_out_of_range_is_422(
    session: AsyncSession, make_conv_client, limit: str
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conv_client(world)
    conv = await seed_conversation(session, world, name="L", activity=NOW)
    problem(await inbox(client, world, limit=limit), 422, "validation_error")
    problem(await messages(client, world, conv.id, limit=limit), 422, "validation_error")


async def test_invalid_or_mismatched_cursors_are_422(
    session: AsyncSession, make_conv_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conv_client(world)
    a = await seed_conversation(session, world, name="A", activity=NOW)
    b = await seed_conversation(session, world, name="B", activity=NOW - timedelta(minutes=1))
    await seed_conversation(session, world, name="C", activity=NOW - timedelta(minutes=2))
    for i in range(3):
        await seed_inbound(session, world, a, f"a{i}", NOW - timedelta(minutes=10 - i))
        await seed_inbound(session, world, b, f"b{i}", NOW - timedelta(minutes=10 - i))

    inbox_cursor = (await inbox(client, world, limit=1)).json()["next_cursor"]
    stage_cursor = (await inbox(client, world, limit=1, stage="new")).json()["next_cursor"]
    msg_cursor = (await messages(client, world, a.id, limit=1)).json()["next_cursor"]
    assert inbox_cursor and stage_cursor and msg_cursor

    def bad(resp: httpx.Response) -> None:
        body = problem(resp, 422, "validation_error")
        assert body["errors"][0]["pointer"] == "query:cursor"

    bad(await inbox(client, world, cursor="not-a-cursor!!"))
    bad(await messages(client, world, a.id, cursor="not-a-cursor!!"))
    bad(await inbox(client, world, cursor=msg_cursor))  # another listing
    bad(await messages(client, world, a.id, cursor=inbox_cursor))
    bad(await messages(client, world, b.id, cursor=msg_cursor))  # another conversation
    bad(await inbox(client, world, cursor=inbox_cursor, stage="new"))  # filters changed
    bad(await inbox(client, world, cursor=stage_cursor))
    problem(await inbox(client, world, cursor="x" * 513), 422, "validation_error")
    # The matching cursor still works.
    assert (await inbox(client, world, cursor=stage_cursor, stage="new")).status_code == 200


# --------------------------------------------------------------------------- filters


async def test_platform_and_stage_filters(session: AsyncSession, make_conv_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    sms = await make_world(
        session, platform="sms", device_side=True, scope=world.scope, clock=world.clock
    )
    client = make_conv_client(world)
    m1 = await seed_conversation(
        session, world, name="m1", activity=NOW, stage=ConversationStage.RAPPORT
    )
    m2 = await seed_conversation(session, world, name="m2", activity=NOW - timedelta(minutes=1))
    s1 = await seed_conversation(
        session,
        sms,
        name="s1",
        activity=NOW - timedelta(minutes=2),
        stage=ConversationStage.RAPPORT,
    )

    def ids(resp: httpx.Response) -> list[str]:
        assert resp.status_code == 200, resp.text
        return [i["id"] for i in resp.json()["items"]]

    assert ids(await inbox(client, world)) == [str(m1.id), str(m2.id), str(s1.id)]
    assert ids(await inbox(client, world, platform="mock")) == [str(m1.id), str(m2.id)]
    assert ids(await inbox(client, world, platform="sms")) == [str(s1.id)]
    assert ids(await inbox(client, world, platform="hinge")) == []
    assert ids(await inbox(client, world, stage="rapport")) == [str(m1.id), str(s1.id)]
    assert ids(await inbox(client, world, stage="rapport", platform="sms")) == [str(s1.id)]
    assert ids(await inbox(client, world, stage="confirmed")) == []
    problem(await inbox(client, world, stage="dating"), 422, "validation_error")
    problem(await inbox(client, world, platform="Not A Platform"), 422, "validation_error")


async def test_intent_and_automation_mode_filter_on_effective_values(
    session: AsyncSession, make_conv_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conv_client(world)
    plain = await seed_conversation(session, world, name="plain", activity=NOW)
    serious = await seed_conversation(
        session, world, name="serious", activity=NOW - timedelta(minutes=1)
    )
    auto = await seed_conversation(session, world, name="auto", activity=NOW - timedelta(minutes=2))
    cleared = await seed_conversation(
        session, world, name="cleared", activity=NOW - timedelta(minutes=3)
    )

    def ids(resp: httpx.Response) -> set[str]:
        assert resp.status_code == 200, resp.text
        return {i["id"] for i in resp.json()["items"]}

    everyone = {str(c.id) for c in (plain, serious, auto, cleared)}
    # Never saved: safe defaults (open, manual) for everyone.
    assert ids(await inbox(client, world, intent="open")) == everyone
    assert ids(await inbox(client, world, automation_mode="manual")) == everyone
    assert ids(await inbox(client, world, intent="serious")) == set()

    # User-wide assisted + intent open; per-contact overrides.
    await set_mode(session, world.scope, "assisted")
    prefs = PreferencesService(session, world.scope)
    await prefs.put_contact_override(
        serious.contact_id,
        ContactPreferenceOverrideUpdate.model_validate({"intent": {"mode": "serious"}}),
    )
    await prefs.put_contact_override(
        auto.contact_id,
        ContactPreferenceOverrideUpdate.model_validate({"automation_mode": "autopilot"}),
    )
    # An override that was set and then cleared inherits again (latest version wins).
    await prefs.put_contact_override(
        cleared.contact_id,
        ContactPreferenceOverrideUpdate.model_validate({"intent": {"mode": "casual_dating"}}),
    )
    await prefs.clear_contact_override(cleared.contact_id)

    assert ids(await inbox(client, world, intent="serious")) == {str(serious.id)}
    assert ids(await inbox(client, world, intent="open")) == everyone - {str(serious.id)}
    assert ids(await inbox(client, world, intent="casual_dating")) == set()
    assert ids(await inbox(client, world, automation_mode="autopilot")) == {str(auto.id)}
    assert ids(await inbox(client, world, automation_mode="assisted")) == everyone - {str(auto.id)}
    assert ids(await inbox(client, world, automation_mode="manual")) == set()
    assert ids(await inbox(client, world, intent="open", automation_mode="autopilot")) == {
        str(auto.id)
    }

    # The reported effective values agree with the filters.
    rows = {i["id"]: i for i in (await inbox(client, world)).json()["items"]}
    assert rows[str(serious.id)]["effective_intent"] == "serious"
    assert rows[str(serious.id)]["automation"]["effective_mode"] == "assisted"
    assert rows[str(auto.id)]["effective_intent"] == "open"
    assert rows[str(auto.id)]["automation"]["effective_mode"] == "autopilot"
    assert rows[str(cleared.id)]["effective_intent"] == "open"
    one = (await detail(client, world, serious.id)).json()
    assert one["effective_intent"] == "serious"

    # Filtered walks paginate too, with the filter-bound cursor.
    got = await all_pages(
        lambda **p: inbox(client, world, automation_mode="assisted", **p), limit=1
    )
    assert [g["id"] for g in got] == [str(plain.id), str(serious.id), str(cleared.id)]
    problem(await inbox(client, world, intent="friends"), 422, "validation_error")
    problem(await inbox(client, world, automation_mode="yolo"), 422, "validation_error")


# --------------------------------------------------------------------------- isolation


async def test_other_users_conversations_are_404_and_never_listed(
    session: AsyncSession, make_conv_client
) -> None:  # type: ignore[no-untyped-def]
    owner = await make_world(session, mode="assisted")
    intruder = await make_world(session)
    client = make_conv_client(owner)
    assert (await post_event(client, owner, owner.event("hi"))).status_code == 202
    conv = await first_conversation(client, owner)
    owner_cursor = (await messages(client, owner, conv["id"], limit=1)).json()

    assert (await inbox(client, intruder)).json() == {"items": [], "next_cursor": None}
    problem(await detail(client, intruder, conv["id"]), 404, "not_found")
    problem(await messages(client, intruder, conv["id"]), 404, "not_found")
    # A cursor never turns a foreign conversation into a 422: ownership is checked first.
    problem(await messages(client, intruder, conv["id"], cursor="not-a-cursor!!"), 404, "not_found")
    missing = uuid.uuid4()
    problem(await detail(client, owner, missing), 404, "not_found")
    problem(await messages(client, owner, missing), 404, "not_found")
    problem(await detail(client, owner, "not-a-uuid"), 422, "validation_error")
    assert owner_cursor["next_cursor"] is None


async def test_unauthenticated_is_401(session: AsyncSession, make_conv_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_conv_client(world)
    conv = await seed_conversation(session, world, name="U", activity=NOW)
    for path in (
        "/v1/conversations",
        f"/v1/conversations/{conv.id}",
        f"/v1/conversations/{conv.id}/messages",
    ):
        problem(await client.get(path), 401, "unauthenticated")


async def test_pending_approval_count_counts_only_pending(
    session: AsyncSession, make_conv_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="assisted")
    client = make_conv_client(world)
    assert (await post_event(client, world, world.event("hi"))).status_code == 202
    conv = await first_conversation(client, world)
    assert conv["pending_approval_count"] == 1
    [approval] = (await client.get("/v1/approvals", headers=headers(world.scope))).json()["items"]
    rejected = await client.post(
        f"/v1/approvals/{approval['id']}/reject", json={}, headers=headers(world.scope)
    )
    assert rejected.status_code == 200, rejected.text
    assert (await detail(client, world, conv["id"])).json()["pending_approval_count"] == 0
