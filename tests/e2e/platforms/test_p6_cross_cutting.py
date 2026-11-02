"""P6: cross-cutting scenarios across platforms (WING-032 mandatory cases).

C1 per-conversation automation override, C2 global kill switch, C3 permissions revoked and app
upgrade, C4 retries, C5 idempotency across restarts, C6 content and identity ambiguity plus
cross-platform continuity by an explicit user link only, C7 serious -> casual intent switch
mid-thread, C8 date suggestions (WING-018 not built).

The ``mock`` platform appears only where autopilot must be shown to *apply* (it is the only
connection with a VERIFIED send, and only against the simulator); every real platform here is
UNVERIFIED or UNSUPPORTED and must never send without approval.
"""

from __future__ import annotations

import signal
import time

from tests.e2e.harness import Api, assert_contract, new_key, wait_until
from tests.e2e.platforms.platform_harness import (
    ScriptedServer,
    assisted,
    connection,
    conversation_by_thread,
    notices,
    outbox,
    pair_imessage,
    pair_notification,
    pair_sms,
    record,
)


def _mock_contact(u: Api, conn: dict, name: str, text: str = "hi there") -> dict:
    r = u.ingest(conn["id"], u.inbound_event(conn, text=text, sender=name.lower(), display_name=name))
    assert r.status_code == 202, r.text
    return u.conversation_for(name)


def _first_reply_approved(u: Api, conv: dict) -> None:
    """The first reply in a thread always needs approval (``first_message``); approve it."""
    msg = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    u.wait_state(conv["id"], msg["id"], {"sent"})


# --------------------------------------------------------------------------- C1


def test_c1_per_conversation_override(make_user) -> None:
    u: Api = make_user("c1-override")
    u.put_preferences(mode="autopilot")
    sim = u.pair_mock("C1 simulator")
    a = _mock_contact(u, sim, "Avery")
    b = _mock_contact(u, sim, "Blake")
    _first_reply_approved(u, a)
    _first_reply_approved(u, b)
    # Avery's conversation is switched to manual; Blake's inherits autopilot.
    put = u.request(
        "PUT",
        f"/v1/contacts/{a['contact']['id']}/preferences",
        json_body={"intent": None, "style": None, "automation_mode": "manual"},
    )
    assert put.status_code == 200, put.text
    assert put.json()["effective"]["automation"]["mode"] == "manual"
    for name in ("Avery", "Blake"):
        r = u.ingest(sim["id"], u.inbound_event(sim, text="so what's new?", sender=name.lower(), display_name=name))
        assert r.status_code == 202

    def blake_sent() -> bool:
        return any(m["origin"] == "autopilot" and m["delivery_state"] == "sent" for m in u.outbound(b["id"]))

    wait_until(blake_sent, timeout=30, what="autopilot reply for the inheriting conversation")
    time.sleep(2)
    assert [m for m in u.outbound(a["id"]) if m["origin"] == "autopilot"] == []
    assert u.approvals(status="pending", conversation_id=a["id"]) == []  # manual: draft only
    assert u.get(f"/v1/conversations/{a['id']}")["automation"]["effective_mode"] == "manual"
    record("C1 per-conversation override: manual contact never sent, sibling on autopilot sent", "mock + all")


# --------------------------------------------------------------------------- C2


def test_c2_global_kill_switch(make_user) -> None:
    u: Api = make_user("c2-kill")
    u.put_preferences(mode="autopilot", reply_delay=(20, 20))
    sim = u.pair_mock("C2 simulator")
    tinder = pair_notification(u, "tinder")
    conv = _mock_contact(u, sim, "Casey")
    _first_reply_approved(u, conv)
    u.ingest(sim["id"], u.inbound_event(sim, text="tell me more", sender="casey", display_name="Casey"))
    queued = wait_until(
        lambda: [m for m in u.outbound(conv["id"]) if m["origin"] == "autopilot" and m["delivery_state"] == "queued"],
        timeout=15,
        what="delayed autopilot send queued",
    )
    paused = u.post("/v1/automation/pause", {"reason": "kill switch"}).json()
    assert paused["status"] == "paused"
    assert u.message(conv["id"], queued[0]["id"])["delivery_state"] == "canceled"
    # While paused: a new inbound only queues an approval with `paused`; nothing is sent.
    u.ingest(sim["id"], u.inbound_event(sim, text="hello?", sender="casey", display_name="Casey"))
    a = u.pending_approval(conv["id"])
    assert "paused" in a["reasons"], a["reasons"]
    tinder.deliver("hey", conversation="shortcut:c2", label="Drew")
    time.sleep(25)  # past the reply delay: still nothing automated went out
    auto_sent = [m for m in u.outbound(conv["id"]) if m["origin"] == "autopilot" and m["delivery_state"] == "sent"]
    assert auto_sent == []
    assert outbox(u, tinder.id) == []
    types = [x["type"] for x in u.actions()]
    assert "automation_paused" in types
    u.post("/v1/automation/resume", {"reason": None})
    record("C2 global kill switch: queued autopilot canceled, new drafts wait (paused), no send", "mock + tinder")


# --------------------------------------------------------------------------- C3


def test_c3_permission_revoked_and_app_upgrade(make_user) -> None:
    u: Api = make_user("c3-revoked")
    u.put_preferences(mode="assisted")
    phone = pair_sms(u)
    phone.deliver("hey", conversation="+15550133001")
    conv = u.conversation_for("+15550133001")
    m1 = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    # (a) The SMS role is taken away: the phone lowers send before claiming.
    assert phone.capabilities_changed(send="UNSUPPORTED").status_code == 202
    assert phone.claim() == []
    assert u.message(conv["id"], m1["id"])["delivery_state"] == "canceled"
    phone.deliver("are you there", conversation="+15550133001")
    time.sleep(2)
    assert u.approvals(status="pending") == [] and assisted(u) == []  # SMS has no assisted fallback
    assert [m for m in u.outbound(conv["id"]) if m["delivery_state"] in ("queued", "sending", "sent")] == []

    # (b) App upgrade: a new connector version claiming VERIFIED send is stored UNVERIFIED.
    upgraded = dict(phone.connection)
    caps = dict(upgraded["capabilities"])
    caps["connector_version"] = "0.2.0"
    claimed = {"status": "VERIFIED", "evidence": "client-claim", "verified_at": "2026-11-02T00:00:00Z"}
    caps["capabilities"] = {**caps["capabilities"], "send": claimed}
    phone.connection = {**upgraded, "capabilities": caps}
    assert phone.capabilities_changed().status_code == 202
    assert connection(u, phone.id)["capabilities"]["capabilities"]["send"]["status"] == "UNVERIFIED"

    # (c) Notification access revoked: the app lowers read and unpairs; later uploads are refused.
    bumble = pair_notification(u, "bumble")
    bumble.deliver("hi!", conversation="shortcut:c3", label="Emery")
    assert bumble.capabilities_changed(read="UNSUPPORTED").status_code == 202
    u.post(f"/v1/connections/{bumble.id}/unpair", {})
    assert assisted(u, status="pending") == []
    [item] = assisted(u, status="canceled")
    assert item["cancel_reason"], item
    late = bumble.upload(bumble.received("hello?", conversation="shortcut:c3", label="Emery"))
    assert late.status_code == 422, late.text
    assert bumble.claim_raw().status_code == 422
    record("C3 permission revoked (SMS role, notification access) and app upgrade: no send possible", "sms + bumble")


# --------------------------------------------------------------------------- C4


def test_c4_retry_is_a_new_attempt_sent_once(make_user) -> None:
    u: Api = make_user("c4-retry")
    u.put_preferences(mode="assisted")
    mac = pair_imessage(u)
    mac.deliver("ping", conversation="iMessage;-;+15550134001")
    conv = conversation_by_thread(u, mac.id, "imessage:chat:iMessage;-;+15550134001")
    msg = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    [ins1] = mac.claim()
    assert mac.transmit(ins1)
    assert mac.fail(ins1, retryable=True, code="timeout").status_code == 202
    [ins2] = wait_until(
        lambda: [p for p in outbox(u, mac.id) if p["attempt"] == 2], timeout=90, interval=2, what="attempt 2"
    )
    assert ins2["idempotency_key"] != ins1["idempotency_key"]
    [claimed] = mac.claim()
    assert mac.send_all([claimed]) == 1
    final = u.message(conv["id"], msg["id"])
    assert final["delivery_state"] == "sent" and final["attempt_count"] == 2
    assert mac.radio[ins1["idempotency_key"]] == 1 and mac.radio[claimed["idempotency_key"]] == 1
    record("C4 retry: retryable failure -> worker attempt 2 (new key) -> sent once", "imessage")


# --------------------------------------------------------------------------- C5


def test_c5_idempotency_across_restarts(make_user, server: ScriptedServer) -> None:
    u: Api = make_user("c5-restart")
    u.put_preferences(mode="assisted")
    phone = pair_sms(u)
    mac = pair_imessage(u)
    tinder = pair_notification(u, "tinder")
    # SMS: leased and transmitted, ack not uploaded yet.
    phone.deliver("yo", conversation="+15550135001")
    sconv = u.conversation_for("+15550135001")
    sm = u.approve(u.pending_approval(sconv["id"])["id"])["message"]
    claim_key = new_key()
    [sins] = phone.claim(key=claim_key)
    assert phone.transmit(sins)
    # iMessage: approved, instruction pending.
    mac.deliver("hey", conversation="iMessage;-;+15550135002")
    mconv = conversation_by_thread(u, mac.id, "imessage:chat:iMessage;-;+15550135002")
    mm = u.approve(u.pending_approval(mconv["id"])["id"])["message"]
    # Tinder: assisted delivery pending; the confirm response is "lost".
    tinder_event = tinder.received("hi from tinder", conversation="shortcut:c5", label="Finley")
    assert tinder.upload(tinder_event).status_code == 202
    [item] = assisted(u, status="pending")
    confirm_key = new_key()
    first = u.post(f"/v1/assisted-deliveries/{item['id']}/confirm", {"sent_text": "hey!"}, key=confirm_key).json()

    server.restart(signal.SIGKILL)
    # Every device re-uploads / replays after the crash.
    assert tinder.upload(tinder_event).json()["outcome"] == "duplicate_event"
    again = u.post(f"/v1/assisted-deliveries/{item['id']}/confirm", {"sent_text": "hey!"}, key=confirm_key)
    assert again.headers.get("Idempotent-Replayed") == "true"
    assert again.json()["message"]["id"] == first["message"]["id"]
    replay = phone.claim(key=claim_key)
    assert [i["id"] for i in replay] == [sins["id"]]
    assert phone.transmit(replay[0]) is False  # ledger: no second radio attempt
    assert phone.acknowledge(sins).status_code == 202
    server.restart(signal.SIGTERM)
    assert phone.acknowledge(sins).status_code == 200  # duplicate upload after the restart
    [mins] = mac.claim()
    assert mins["outbound_message_id"] == mm["id"]
    assert mac.send_all([mins]) == 1
    assert mac.claim() == [] and phone.claim() == []
    assert u.message(sconv["id"], sm["id"])["delivery_state"] == "sent"
    assert u.message(mconv["id"], mm["id"])["delivery_state"] == "sent"
    tconv = conversation_by_thread(u, tinder.id, "shortcut:c5")
    assert [m["delivery_state"] for m in u.outbound(tconv["id"])] == ["user_reported_sent"]
    acks = [a for a in u.actions() if a["type"] == "send_acknowledged"]
    assert len(acks) == 2 and len({a["message_id"] for a in acks}) == 2
    record(
        "C5 idempotency across SIGKILL/SIGTERM restarts: replays, no duplicate send or confirm",
        "sms + imessage + tinder",
    )


# --------------------------------------------------------------------------- C6


def test_c6_identity_ambiguity_and_explicit_link_only(make_user) -> None:
    u: Api = make_user("c6-identity")
    u.put_preferences(mode="assisted")
    tinder = pair_notification(u, "tinder")
    bumble = pair_notification(u, "bumble")
    phone = pair_sms(u)
    # Same display name "Riley" everywhere; only stable IDs make people.
    tinder.deliver("hey it's Riley", conversation="shortcut:tinder-riley", label="Riley")
    bumble.deliver("hi, Riley here", conversation="shortcut:bumble-riley", label="Riley")
    tinder.deliver("Riley again?", label="Riley")  # unlinked: a notice, never merged by name
    tinder.deliver("Riley: are you free this wee", conversation="shortcut:tinder-riley", truncated=True, label="Riley")
    phone.deliver("Riley here, this is my number", conversation="+15550136001")
    convs = u.conversations(limit=100)
    assert len(convs) == 3
    assert len({c["contact"]["id"] for c in convs}) == 3  # never auto-merged
    assert sum(1 for n in notices(u) if n["sender_label"] == "Riley") == 2
    t = conversation_by_thread(u, tinder.id, "shortcut:tinder-riley")
    s = u.conversation_for("+15550136001")
    assert u.get(f"/v1/contacts/{t['contact']['id']}/identity-links")["items"] == []

    # The user links the Tinder match and the phone contact explicitly.
    link = u.post(
        "/v1/identity-links",
        {"contact_id": t["contact"]["id"], "linked_contact_id": s["contact"]["id"]},
        expect=201,
    )
    body = assert_contract("IdentityLink", link.json())
    for cid in (t["contact"]["id"], s["contact"]["id"]):
        assert [x["id"] for x in u.get(f"/v1/contacts/{cid}/identity-links")["items"]] == [body["id"]]
    b = conversation_by_thread(u, bumble.id, "shortcut:bumble-riley")
    assert u.get(f"/v1/contacts/{b['contact']['id']}/identity-links")["items"] == []
    # Linking merges nothing: three conversations, each with its own history.
    assert len(u.conversations(limit=100)) == 3
    assert [m["direction"] for m in u.messages(t["id"])] == ["inbound"]
    assert u.request("DELETE", f"/v1/identity-links/{body['id']}").status_code == 204
    assert u.get(f"/v1/contacts/{t['contact']['id']}/identity-links")["items"] == []
    record(
        "C6 identity: same name on 3 platforms never merged; continuity only by explicit user link",
        "tinder + bumble + sms",
    )


# --------------------------------------------------------------------------- C7


def test_c7_intent_switch_serious_to_casual_mid_thread(make_user) -> None:
    u: Api = make_user("c7-intent")
    u.put_preferences(intent="serious", mode="assisted")
    hinge = pair_notification(u, "hinge")
    hinge.deliver("What are you looking for on here?", conversation="shortcut:c7", label="Jules")
    conv = conversation_by_thread(u, hinge.id, "shortcut:c7")
    [first] = assisted(u, status="pending")
    before = u.messages(conv["id"])
    d1 = u.post(f"/v1/conversations/{conv['id']}/drafts", {}, expect=201).json()
    assert d1["intent_mode"] == "serious"

    put = u.request(
        "PUT",
        f"/v1/contacts/{conv['contact']['id']}/preferences",
        json_body={
            "intent": {"mode": "casual_dating", "custom_description": None},
            "style": None,
            "automation_mode": None,
        },
    )
    assert put.status_code == 200, put.text
    assert u.get(f"/v1/conversations/{conv['id']}")["effective_intent"] == "casual_dating"
    d2 = u.post(f"/v1/conversations/{conv['id']}/drafts", {}, expect=201).json()
    assert d2["intent_mode"] == "casual_dating"
    hinge.deliver("haha fair. drinks sometime?", conversation="shortcut:c7", label="Jules")
    gen = [a for a in u.actions(conversation_id=conv["id"]) if a["type"] == "draft_generated"]
    assert len(gen) >= 2
    # History untouched: earlier messages and the earlier assisted draft are unchanged.
    after = {m["id"]: m for m in u.messages(conv["id"])}
    for m in before:
        assert after[m["id"]]["text"] == m["text"]
    every = [x for st in ("pending", "canceled") for x in assisted(u, status=st)]
    [old] = [x for x in every if x["id"] == first["id"]]
    assert old["text"] == first["text"]
    assert u.get("/v1/preferences")["intent"]["mode"] == "serious"  # the user-wide intent is unchanged
    record(
        "C7 intent switch serious -> casual mid-thread: new drafts casual, history untouched", "hinge (notification)"
    )


# --------------------------------------------------------------------------- C8


def test_c8_date_suggestions_need_approval_and_no_calendar_exists(make_user) -> None:
    u: Api = make_user("c8-dates")
    u.put_preferences(mode="autopilot")
    sim = u.pair_mock("C8 simulator")
    conv = _mock_contact(u, sim, "Harper")
    _first_reply_approved(u, conv)
    # Even on autopilot with the simulator's VERIFIED send, a proposed date waits for approval.
    u.ingest(
        sim["id"], u.inbound_event(sim, text="free soon? [e2e:propose-date]", sender="harper", display_name="Harper")
    )
    a = u.pending_approval(conv["id"])
    assert "date_or_commitment" in a["reasons"], a["reasons"]
    assert a["draft"]["text"].startswith("[e2e scripted]")
    time.sleep(3)
    assert [m for m in u.outbound(conv["id"]) if m["origin"] == "autopilot"] == []
    # Confirming a plan states the user's availability: ask the user, never a send or approval.
    u.ingest(
        sim["id"], u.inbound_event(sim, text="Sat 3pm? [e2e:confirm-date]", sender="harper", display_name="Harper")
    )
    time.sleep(2)
    pending = u.approvals(status="pending", conversation_id=conv["id"])
    assert [p["id"] for p in pending] == [a["id"]] or pending == [], pending  # newest inbound may supersede
    assert [m for m in u.outbound(conv["id"]) if m["origin"] == "autopilot"] == []
    # Notification path: the proposal is an assisted draft flagged date_or_commitment.
    tinder = pair_notification(u, "tinder")
    tinder.deliver("coffee? [e2e:propose-date]", conversation="shortcut:c8", label="Harper")
    [item] = assisted(u, status="pending")
    assert "date_or_commitment" in item["reasons"], item
    # WING-018 is not built: no calendar or date route is served, calendar_write is UNSUPPORTED.
    paths = u.request("GET", "/openapi.json").json()["paths"]
    assert not [p for p in paths if "calendar" in p or "/dates" in p], list(paths)
    for c in u.get("/v1/connections")["items"]:
        assert c["capabilities"]["capabilities"]["calendar_write"]["status"] == "UNSUPPORTED"
    record(
        "C8 date suggestions: proposal needs approval even on autopilot; confirmation asks the user; no calendar",
        "mock + tinder",
        calendar="WING-018 not built",
    )


# --------------------------------------------------------------------------- C9


def test_c9_manual_takeover_mid_flight_across_platforms(make_user) -> None:
    u: Api = make_user("c9-takeover")
    u.put_preferences(mode="assisted")
    mac = pair_imessage(u)
    bumble = pair_notification(u, "bumble")
    mac.deliver("hey", conversation="iMessage;-;+15550139001")
    mconv = conversation_by_thread(u, mac.id, "imessage:chat:iMessage;-;+15550139001")
    m1 = u.approve(u.pending_approval(mconv["id"])["id"])["message"]  # instruction pending, not claimed
    bumble.deliver("hiya", conversation="shortcut:c9", label="Kai")
    bconv = conversation_by_thread(u, bumble.id, "shortcut:c9")
    [pending] = assisted(u, status="pending")
    for conv in (mconv, bconv):
        took = u.post(f"/v1/contacts/{conv['contact']['id']}/automation/takeover", {"reason": None}).json()
        assert took["status"] == "taken_over"
    assert u.message(mconv["id"], m1["id"])["delivery_state"] == "canceled"
    assert mac.claim() == []
    assert assisted(u, status="pending") == []
    assert [x["id"] for x in assisted(u, status="canceled")] == [pending["id"]]
    # While taken over the agent stays out entirely: no new draft, approval or assisted delivery.
    mac.deliver("hello?", conversation="iMessage;-;+15550139001")
    bumble.deliver("you there?", conversation="shortcut:c9", label="Kai")
    time.sleep(2)
    assert u.approvals(status="pending") == [] and assisted(u, status="pending") == []
    record(
        "C9 manual takeover mid-flight: pending iMessage send and Bumble assisted draft canceled", "imessage + bumble"
    )
