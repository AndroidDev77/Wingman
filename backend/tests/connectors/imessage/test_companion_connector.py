"""WING-030: the macOS iMessage companion against the real API (fixtures, no Mac).

Pairing ``imessage`` over ``companion``, inbound into the unified inbox, the D-017 claim
binding to the companion's install, takeover and global pause, unpair, dedup across a
restart, and assisted delivery when ``send`` is UNSUPPORTED (draft-only mode). The bodies
and envelopes are the Swift app's golden output (``companion.py``). Nothing here proves a
real Mac can read or send: that is the manual test in docs/integrations/imessage_companion.md.
"""

import uuid
from datetime import UTC, datetime
from typing import Any

import httpx
from jsonschema import Draft202012Validator
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.connectors.capabilities import CapabilityName
from app.db.enums import (
    ApprovalReason,
    AssistedDeliveryStatus,
    DeliveryState,
    MessageDirection,
    MessageOrigin,
    OutboxInstructionState,
)
from app.db.models import (
    Approval,
    AssistedDelivery,
    Conversation,
    Draft,
    InboundNotice,
    Message,
    OutboxInstruction,
)
from app.services.connections import ConnectionService
from tests.automation.conftest import World, assert_contract, headers, make_world, problem
from tests.connectors.imessage.companion import (
    COMPANION_INSTALL,
    DM,
    DM_THREAD,
    FOREIGN_INSTALL,
    claim,
    golden_envelopes,
    outcome,
    pair,
    pairing_request,
    post,
    rebind,
    received,
)


async def count(session: AsyncSession, model: Any, world: World, *where: Any) -> int:
    return int(
        await session.scalar(
            select(func.count())
            .select_from(model)
            .where(model.user_id == world.scope.user_id, *where)
        )
        or 0
    )


async def inbound_message(session: AsyncSession, resp: httpx.Response) -> Message:
    assert resp.status_code == 202, resp.text
    msg = await session.get(Message, uuid.UUID(resp.json()["message_id"]))
    assert msg is not None
    return msg


async def contact_of(session: AsyncSession, msg: Message) -> uuid.UUID:
    conv = await session.get(Conversation, msg.conversation_id)
    assert conv is not None
    return conv.contact_id


async def warm_up(
    session: AsyncSession, client: httpx.AsyncClient, mac: World
) -> tuple[Message, dict[str, Any]]:
    """An inbound message, then the user's own reply through the companion: leased,
    acknowledged from the (simulated) chat.db row. Gets past ``first_message``."""
    msg = await inbound_message(session, await post(client, mac, received(mac, "hi from Alice")))
    sent = await client.post(
        f"/v1/conversations/{msg.conversation_id}/messages",
        json={"text": "hey!"},
        headers=headers(mac.scope),
    )
    assert sent.status_code == 202, sent.text
    [ins] = (await claim(client, mac)).json()["instructions"]
    assert ins["authorization"]["origin"] == "user_manual"
    assert (await post(client, mac, outcome(mac, ins))).status_code == 202
    return msg, ins


# --------------------------------------------------------------------------- shapes


def test_golden_output_matches_the_contract(event_validator: Draft202012Validator) -> None:
    envelopes = golden_envelopes()
    assert len(envelopes) == 9
    for env in envelopes:
        event_validator.validate(env)
        assert env["platform"] == "imessage"
    for kind in ("default", "draft_only"):
        body = pairing_request(kind)
        assert_contract("ConnectionCreateRequest", body)
        caps = body["capabilities"]["capabilities"]
        assert {c["status"] for c in caps.values()} <= {"UNVERIFIED", "UNSUPPORTED"}
        assert caps["swipe"]["status"] == caps["draft"]["status"] == "UNSUPPORTED"
        assert caps["profile_read"]["status"] == "UNSUPPORTED"
    assert pairing_request("draft_only")["capabilities"]["capabilities"]["send"]["status"] == (
        "UNSUPPORTED"
    )
    # Inbound: 1:1 linked by chat GUID, group as an ambiguous notice, always `companion`.
    inbound = [e for e in envelopes if e["type"] == "message.received"]
    assert {e["payload"]["source"] for e in inbound} == {"companion"}
    identities = {e["external_thread_id"]: e["payload"]["identity"] for e in inbound}
    assert identities[DM_THREAD] == "linked"
    assert identities["imessage:chat:iMessage;+;chat123456789012345"] == "ambiguous"


def test_python_helper_has_the_swift_shape() -> None:
    swift = golden_envelopes()[0]
    world_like = type(
        "W",
        (),
        {
            "scope": type("S", (), {"user_id": uuid.uuid4()})(),
            "connection_id": uuid.uuid4(),
            "clock": type("C", (), {"now": datetime(2026, 11, 2, tzinfo=UTC)})(),
        },
    )()
    mine = received(world_like, "x")  # type: ignore[arg-type]
    assert mine.keys() == swift.keys()
    assert mine["payload"].keys() == swift["payload"].keys()
    assert mine["payload"]["sender"].keys() == swift["payload"]["sender"].keys()


# --------------------------------------------------------------------------- pairing + inbox


async def test_pairing_and_inbound_reach_the_unified_inbox(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="assisted")
    client = make_v13_client(world)
    mac, conn = await pair(client, world)
    assert_contract("Connection", conn)
    assert conn["platform"] == "imessage" and conn["transport"] == "companion"
    assert conn["device_install_id"] == COMPANION_INSTALL
    assert conn["capabilities"]["capabilities"]["send"]["status"] == "UNVERIFIED"
    assert conn["capabilities"]["capabilities"]["read"]["status"] == "UNVERIFIED"
    # Pairing again from the same install is the same connection (lost-response replay).
    again = await client.post(
        "/v1/connections", json=pairing_request(), headers=headers(world.scope)
    )
    assert again.status_code == 200 and again.json()["id"] == conn["id"]

    results = {}
    for env in golden_envelopes():
        if env["type"] != "message.received":
            continue
        resp = await post(client, mac, rebind(env, mac))
        assert resp.status_code == 202, resp.text
        assert_contract("ConnectorEventIngestResult", resp.json())
        results[env["external_thread_id"]] = resp.json()

    group = "imessage:chat:iMessage;+;chat123456789012345"
    assert results[group]["message_id"] is None and results[group]["notice_id"] is not None
    for thread, body in results.items():
        if thread != group:
            assert body["message_id"] is not None, thread

    page = await client.get("/v1/conversations", headers=headers(world.scope))
    assert page.status_code == 200
    assert_contract("ConversationPage", page.json())
    threads = {c["external_thread_id"] for c in page.json()["items"]}
    assert {DM_THREAD, "imessage:chat:SMS;-;+15550100004"} <= threads
    assert group not in threads  # a group is never merged into a person
    notices = await client.get("/v1/inbound-notices", headers=headers(world.scope))
    assert [n["platform"] for n in notices.json()["items"]] == ["imessage"]
    # The two Alice messages (text and attributedBody) are one conversation.
    alice = [c for c in page.json()["items"] if c["external_thread_id"] == DM_THREAD]
    assert len(alice) == 1
    thread = await client.get(
        f"/v1/conversations/{alice[0]['id']}/messages", headers=headers(world.scope)
    )
    texts = sorted(m["text"] for m in thread.json()["items"] if m["direction"] == "inbound")
    assert texts == ["Coffee after?", "Free tonight? 🍜"]


async def test_group_messages_never_draft_or_send_even_on_autopilot(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="autopilot")
    client = make_v13_client(world)
    mac, _ = await pair(client, world)
    env = received(mac, "Who's in?", chat="iMessage;+;chat99", sender="+15550100003", group=True)
    resp = await post(client, mac, env)
    assert resp.status_code == 202 and resp.json()["notice_id"] is not None
    for model in (Draft, Approval, AssistedDelivery, OutboxInstruction, Message):
        assert await count(session, model, mac) == 0, model.__name__
    assert world.llm.calls == []


async def test_capabilities_changed_from_the_mac_lowers_send(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_v13_client(world)
    mac, _ = await pair(client, world)
    [caps] = [e for e in golden_envelopes() if e["type"] == "connection.capabilities_changed"]
    assert (await post(client, mac, rebind(caps, mac))).status_code == 202
    conn = await client.get(f"/v1/connections/{mac.connection_id}", headers=headers(world.scope))
    assert conn.json()["capabilities"]["capabilities"]["send"]["status"] == "UNSUPPORTED"


# --------------------------------------------------------------------------- dedup


async def test_duplicate_envelopes_dedup_across_a_restart(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    """After a crash the companion re-reads the same rows and rebuilds byte-identical
    envelopes (same event_id). An older install may also re-send the same message with a
    new event ID. Either way there is one message."""
    world = await make_world(session, mode="assisted")
    client = make_v13_client(world)
    mac, _ = await pair(client, world)
    env = received(mac, "only once", guid="GUID-RESTART-1")
    first = await post(client, mac, env)
    assert first.status_code == 202
    replay = await post(client, mac, env)  # identical bytes, same Idempotency-Key
    assert replay.status_code == 200, replay.text  # 200 = already recorded (WING-016 rule)
    assert replay.json()["message_id"] == first.json()["message_id"]
    fresh_id = dict(env, event_id=str(uuid.uuid4()))
    dup = await post(client, mac, fresh_id)
    assert dup.status_code == 200 and dup.json()["outcome"] == "duplicate_message"
    assert dup.json()["message_id"] == first.json()["message_id"]
    assert await count(session, Message, mac, Message.direction == MessageDirection.INBOUND) == 1
    assert await count(session, Approval, mac) == 1


# --------------------------------------------------------------------------- claim binding


async def test_claim_is_bound_to_the_companion_install(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="manual")
    client = make_v13_client(world)
    mac, _ = await pair(client, world)
    msg = await inbound_message(session, await post(client, mac, received(mac, "hi")))
    sent = await client.post(
        f"/v1/conversations/{msg.conversation_id}/messages",
        json={"text": "On my way 🚗"},
        headers=headers(mac.scope),
    )
    assert sent.status_code == 202, sent.text

    foreign = problem(await claim(client, mac, device=FOREIGN_INSTALL), 422, "validation_error")
    assert foreign["errors"][0]["pointer"] == "/device_install_id"
    resp = await claim(client, mac)
    assert resp.status_code == 200
    assert_contract("OutboxClaimResult", resp.json())
    [ins] = resp.json()["instructions"]
    # What the Swift OutboxExecutor needs: an imessage:chat thread holding the chat GUID.
    assert ins["external_thread_id"] == DM_THREAD
    assert ins["external_thread_id"].removeprefix("imessage:chat:") == DM
    assert ins["text"] == "On my way 🚗" and ins["state"] == "leased"
    # The ack comes only from the observed chat.db row; here, the companion's envelope.
    ack = await post(client, mac, outcome(mac, ins))
    assert ack.status_code == 202, ack.text
    out = await session.get(Message, uuid.UUID(ins["outbound_message_id"]))
    assert out is not None and out.delivery_state is DeliveryState.SENT
    assert (await claim(client, mac)).json()["instructions"] == []


async def test_automation_denied_is_dead_lettered_never_retried(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="manual")
    client = make_v13_client(world)
    mac, _ = await pair(client, world)
    msg = await inbound_message(session, await post(client, mac, received(mac, "hi")))
    await client.post(
        f"/v1/conversations/{msg.conversation_id}/messages",
        json={"text": "hello"},
        headers=headers(mac.scope),
    )
    [ins] = (await claim(client, mac)).json()["instructions"]
    assert (await post(client, mac, outcome(mac, ins, ok=False))).status_code == 202
    out = await session.get(Message, uuid.UUID(ins["outbound_message_id"]))
    assert out is not None and out.delivery_state is DeliveryState.DEAD_LETTERED
    mac.clock.advance(hours=1)
    assert (await claim(client, mac)).json()["instructions"] == []


async def test_mac_asleep_lease_expires_unknown_never_retried(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="manual")
    client = make_v13_client(world)
    mac, _ = await pair(client, world)
    msg = await inbound_message(session, await post(client, mac, received(mac, "hi")))
    await client.post(
        f"/v1/conversations/{msg.conversation_id}/messages",
        json={"text": "hello"},
        headers=headers(mac.scope),
    )
    [ins] = (await claim(client, mac)).json()["instructions"]
    mac.clock.advance(seconds=121)  # the Mac went to sleep holding the lease
    expired = await mac.services.outbox.expire(session, mac.scope)
    assert [str(e.id) for e in expired] == [ins["id"]]
    out = await session.get(Message, uuid.UUID(ins["outbound_message_id"]))
    assert out is not None and out.delivery_state is DeliveryState.SENDING and out.sent_at is None
    assert (await claim(client, mac)).json()["instructions"] == []
    # On wake, the companion's late ack (the row did appear) is still accepted.
    assert (await post(client, mac, outcome(mac, ins))).status_code == 202
    await session.refresh(out)
    assert out.delivery_state is DeliveryState.SENT


# --------------------------------------------------------------------------- takeover + pause


async def test_takeover_cancels_pending_automated_sends_and_claims_get_nothing(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="assisted")
    client = make_v13_client(world)
    mac, _ = await pair(client, world)
    await warm_up(session, client, mac)
    assert (await post(client, mac, received(mac, "dinner Friday?"))).status_code == 202
    [item] = (await client.get("/v1/approvals", headers=headers(world.scope))).json()["items"]
    approved = await client.post(
        f"/v1/approvals/{item['id']}/approve", json={}, headers=headers(world.scope)
    )
    assert approved.status_code == 200, approved.text
    queued = await session.scalar(
        select(Message).where(
            Message.user_id == world.scope.user_id, Message.origin == MessageOrigin.APPROVED_DRAFT
        )
    )
    assert queued is not None and queued.delivery_state is DeliveryState.QUEUED

    takeover = await client.post(
        f"/v1/contacts/{item['contact_id']}/automation/takeover",
        json={},
        headers=headers(world.scope),
    )
    assert takeover.status_code == 200, takeover.text
    assert takeover.json()["canceled_message_count"] >= 1
    assert (await claim(client, mac)).json()["instructions"] == []
    await session.refresh(queued)
    assert queued.delivery_state is DeliveryState.CANCELED
    states = (
        await session.scalars(
            select(OutboxInstruction.state).where(
                OutboxInstruction.outbound_message_id == queued.id
            )
        )
    ).all()
    assert all(s is OutboxInstructionState.CANCELED for s in states)
    # No new draft for that contact while taken over.
    drafts_before = await count(session, Draft, mac)
    await post(client, mac, received(mac, "hello??"))
    assert await count(session, Draft, mac) == drafts_before


async def test_global_pause_cancels_autopilot_sends_even_with_verified_send(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    """Autopilot stays off for iMessage until the operator CLI records a real-Mac run
    (D-024). Here the operator path is used as if that run existed, to prove the global
    pause still wins over queued autopilot sends at claim time."""
    world = await make_world(session, mode="autopilot")
    client = make_v13_client(world)
    mac, _ = await pair(client, world)
    await ConnectionService(env="dev").record_verification(
        session,
        mac.scope,
        mac.connection_id,
        CapabilityName.SEND,
        evidence="TEST ONLY: simulated WING-030 manual M8/M9/M12/M14 run",
        verified_at=mac.clock.now,
    )
    await warm_up(session, client, mac)
    assert (await post(client, mac, received(mac, "what time?"))).status_code == 202
    auto = await session.scalar(
        select(Message).where(
            Message.user_id == world.scope.user_id, Message.origin == MessageOrigin.AUTOPILOT
        )
    )
    assert auto is not None and auto.delivery_state is DeliveryState.QUEUED

    pause = await client.post("/v1/automation/pause", json={}, headers=headers(world.scope))
    assert pause.status_code == 200 and pause.json()["status"] == "paused"
    mac.clock.advance(hours=1)  # past any reply delay
    assert (await claim(client, mac)).json()["instructions"] == []
    await session.refresh(auto)
    assert auto.delivery_state is DeliveryState.CANCELED


async def test_unverified_imessage_send_is_approval_only(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="autopilot")
    client = make_v13_client(world)
    mac, _ = await pair(client, world)
    await warm_up(session, client, mac)
    before = await count(session, Approval, mac)
    await post(client, mac, received(mac, "how was your day?"))
    assert (await count(session, Message, mac, Message.origin == MessageOrigin.AUTOPILOT)) == 0
    assert await count(session, Approval, mac) == before + 1
    newest = await session.scalar(
        select(Approval)
        .where(Approval.user_id == world.scope.user_id)
        .order_by(Approval.created_at.desc(), Approval.id.desc())
    )
    assert newest is not None and ApprovalReason.CAPABILITY_UNVERIFIED in newest.reasons


# --------------------------------------------------------------------------- unpair


async def test_unpair_stops_claims_and_refuses_inbound_but_takes_late_outcomes(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="manual")
    client = make_v13_client(world)
    mac, _ = await pair(client, world)
    msg = await inbound_message(session, await post(client, mac, received(mac, "hi")))
    for text in ("first", "second"):
        await client.post(
            f"/v1/conversations/{msg.conversation_id}/messages",
            json={"text": text},
            headers=headers(mac.scope),
        )
    leased = (
        await client.post(
            f"/v1/connections/{mac.connection_id}/outbox/claim",
            json={"device_install_id": COMPANION_INSTALL, "max_instructions": 1},
            headers=headers(mac.scope),
        )
    ).json()["instructions"]
    assert len(leased) == 1

    resp = await client.post(
        f"/v1/connections/{mac.connection_id}/unpair", json={}, headers=headers(world.scope)
    )
    assert resp.status_code == 200, resp.text
    body = problem(await claim(client, mac), 422, "validation_error")
    assert body["errors"][0]["pointer"] == "/device_install_id"
    # The companion maps this to "unpaired" and stops uploading inbound messages.
    refused = problem(
        await post(client, mac, received(mac, "after unpair")), 422, "validation_error"
    )
    assert refused["errors"][0]["pointer"] == "/connection_id"
    # The leased send may really have gone out: its outcome is still accepted.
    assert (await post(client, mac, outcome(mac, leased[0]))).status_code == 202
    states = sorted(
        m.delivery_state.value
        for m in (
            await session.scalars(
                select(Message).where(
                    Message.user_id == world.scope.user_id,
                    Message.direction == MessageDirection.OUTBOUND,
                )
            )
        ).all()
    )
    assert states == ["canceled", "sent"]


# --------------------------------------------------------------------------- draft-only


async def test_assisted_delivery_when_send_is_unsupported(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    """Draft-only mode (or a NO-GO for send per D-024): the companion pairs with
    ``send: UNSUPPORTED``; the reply becomes an assisted delivery the user sends by hand.
    Nothing reaches the outbox, and the user's report is never ``sent``/``delivered``."""
    world = await make_world(session, mode="autopilot")
    client = make_v13_client(world)
    mac, conn = await pair(client, world, "draft_only")
    assert conn["capabilities"]["capabilities"]["send"]["status"] == "UNSUPPORTED"
    resp = await post(client, mac, received(mac, "Free tonight?"))
    assert resp.status_code == 202, resp.text
    assert await count(session, OutboxInstruction, mac) == 0
    assert (await claim(client, mac)).json()["instructions"] == []
    page = await client.get("/v1/assisted-deliveries", headers=headers(world.scope))
    assert_contract("AssistedDeliveryPage", page.json())
    [item] = page.json()["items"]
    assert item["platform"] == "imessage" and item["external_thread_id"] == DM_THREAD
    assert item["status"] == AssistedDeliveryStatus.PENDING.value
    confirm = await client.post(
        f"/v1/assisted-deliveries/{item['id']}/confirm",
        json={"sent_text": "Yes! 8pm?"},
        headers=headers(world.scope),
    )
    assert confirm.status_code == 200, confirm.text
    msg = confirm.json()["message"]
    assert msg["delivery_state"] == "user_reported_sent" and msg["sent_at"] is None
    assert await count(session, InboundNotice, mac) == 0
