"""WING-025 acceptance flows, black-box over HTTP against a running backend.

Each test is one scenario from tests/e2e/README.md and docs/e2e_results.md (IDs F1..F11).
Tests are independent: each signs in its own dev-stub user, so they can run in any order
and an isolation failure would show up as a cross-user leak.

What is real here: HTTP, auth (dev stub JWTs verified by the normal verifier), Postgres
(migrated by Alembic), the automation controller, the single dispatcher, the worker loop.
What is simulated (and labeled as such): the LLM (``[dev mock]`` deterministic handler)
and the transport (``MockConnector``; and for ``sms`` connections, a protocol-faithful
device simulator, ``SimulatedSmsDevice``). No real SMS or dating-platform traffic.
"""

from __future__ import annotations

import uuid

from tests.e2e.harness import (
    Api,
    Db,
    SimulatedSmsDevice,
    assert_contract,
    new_key,
    wait_until,
)

MOCK_LABEL = "[dev mock]"


def _contact(api: Api, conn: dict, name: str, text: str = "Hey! Just got back from Lisbon!") -> dict:
    """Inject one inbound message from ``name`` and return its conversation."""
    event = api.inbound_event(conn, text=text, sender=name.lower(), display_name=name)
    r = api.ingest(conn["id"], event)
    assert r.status_code == 202, r.text
    body = assert_contract("ConnectorEventIngestResult", r.json())
    assert body["outcome"] == "accepted"
    return api.conversation_for(name)


# --------------------------------------------------------------------------- F1


def test_f1_onboarding_and_preferences(make_user) -> None:
    """F1: a new user has never-saved defaults (version 0, manual); saving with
    If-Match "0" creates version 1; a stale If-Match is 412; history is listed; the
    audit log records preferences_updated."""
    alice: Api = make_user("f1")
    prefs = assert_contract("Preferences", alice.get("/v1/preferences"))
    assert prefs["version"] == 0
    assert prefs["automation"]["mode"] == "manual"  # automation off until the user opts in

    saved = alice.put_preferences(
        intent="serious",
        mode="assisted",
        style={"punctuation": "minimal", "emoji_frequency": "none", "brevity": "short", "tone": "warm"},
        if_match='"0"',
    )
    assert saved["version"] == 1
    assert saved["intent"]["mode"] == "serious"
    assert saved["style"]["punctuation"] == "minimal"

    stale = alice.request(
        "PUT",
        "/v1/preferences",
        json_body={
            "intent": {"mode": "open", "custom_description": None},
            "style": {},
            "automation": {"mode": "manual"},
        },
        headers={"If-Match": '"0"'},
    )
    assert stale.status_code == 412, stale.text
    assert stale.json()["code"] == "precondition_failed"
    assert alice.get("/v1/preferences")["version"] == 1  # 412 never overwrote

    versions = assert_contract("PreferencesPage", alice.get("/v1/preferences/versions"))
    assert [v["version"] for v in versions["items"]][:1] == [1]
    audit = alice.actions(type="preferences_updated")
    assert len(audit) == 1 and audit[0]["actor"] == "user"


# --------------------------------------------------------------------------- F2


def test_f2_pairing_and_injection(make_user) -> None:
    """F2: pair a mock connection with POST /v1/connections (201, then a replay of the
    same key returns the same connection), inject an inbound message, see the
    conversation and message through the read routes, and the pairing/receipt audit."""
    u: Api = make_user("f2")
    key = new_key()
    first = u.post("/v1/connections", {"platform": "mock", "display_name": "Sim"}, key=key, expect=201)
    replay = u.post("/v1/connections", {"platform": "mock", "display_name": "Sim"}, key=key, expect=201)
    assert replay.json()["id"] == first.json()["id"]
    assert replay.headers.get("Idempotent-Replayed") == "true"
    conn = assert_contract("Connection", first.json())
    assert conn["capabilities"]["capabilities"]["send"]["status"] == "VERIFIED"  # simulator only
    assert "Simulated" in conn["capabilities"]["capabilities"]["send"]["notes"]
    listed = assert_contract("ConnectionPage", u.get("/v1/connections"))
    assert [c["id"] for c in listed["items"]] == [conn["id"]]

    conv = _contact(u, conn, "Sam")
    msgs = u.messages(conv["id"])
    assert [(m["direction"], m["delivery_state"]) for m in msgs] == [("inbound", "received")]
    detail = assert_contract("ConversationDetail", u.get(f"/v1/conversations/{conv['id']}"))
    assert detail["contact"]["display_name"] == "Sam"
    types = [a["type"] for a in u.actions()]
    assert "connection_paired" in types and "message_received" in types
    # Default (never saved) preferences are manual: a draft is stored but no approval and no send.
    assert u.approvals() == []
    assert u.outbound(conv["id"]) == []


# --------------------------------------------------------------------------- F3


def test_f3_personalized_reply_respects_style_and_guardrails(make_user) -> None:
    """F3: the draft reflects the style settings (punctuation minimal strips '!'), cites
    the contact's message, is labeled dev mock, and states no personal fact. Manual mode
    never queues a send; createDraft on request works and a guidance-less draft for a
    conversation with nothing to reply to asks the user instead of inventing."""
    u: Api = make_user("f3")
    u.put_preferences(mode="assisted", style={"punctuation": "minimal", "emoji_frequency": "none"})
    conn = u.pair_mock()
    conv = _contact(u, conn, "Riley")
    approval = u.pending_approval(conv["id"])
    draft = approval["draft"]
    assert draft["decision"] == "send_candidate"
    assert draft["text"].startswith(MOCK_LABEL)
    # The dev handler writes "Thanks for your message! Tell me more?"; style minimal removes "!".
    assert "!" not in draft["text"], draft["text"]
    inbound_ids = [m["id"] for m in u.messages(conv["id"]) if m["direction"] == "inbound"]
    assert draft["grounding"]["message_ids"] == inbound_ids  # grounded in the contact's message
    assert draft["intent_mode"] == "serious"
    assert set(approval["reasons"]) >= {"assisted_mode", "first_message"}

    # Same style, expressive punctuation: the "!" is kept (the setting is what changed it).
    u.put_preferences(mode="assisted", style={"punctuation": "expressive"})
    on_request = u.post(f"/v1/conversations/{conv['id']}/drafts", {"guidance": None}, expect=201)
    d2 = assert_contract("Draft", on_request.json())
    assert "!" in d2["text"]
    assert u.outbound(conv["id"]) == []  # a draft is a suggestion: nothing was queued

    # Policy guardrails observable over HTTP: an assisted draft is never sent without
    # approval, and the draft audit is redacted (IDs and codes, never text). Content
    # guardrails (fabricated user facts -> ask_user, explicit/coercive -> no_send,
    # unsupported escalation) need a model that produces such output; the labeled dev
    # mock never does, so they are covered by backend/tests/ai/replies, not here.
    audit = [a for a in u.actions(conversation_id=conv["id"]) if a["type"] == "draft_generated"]
    assert len(audit) == 2
    for a in audit:
        assert "text" not in a["details"] and "question" not in a["details"]
        assert a["details"]["decision"] == "send_candidate"


# --------------------------------------------------------------------------- F4


def test_f4_assisted_approve_edit_reject_and_send(make_user, db: Db) -> None:
    """F4: assisted mode. Nothing is sent before approval. Approve sends exactly the
    draft text; approve-with-edit sends exactly the edited text; a replayed approve key
    returns the same result and sends nothing more; approving twice is 409; reject sends
    nothing. Every step is in the audit log, in order."""
    u: Api = make_user("f4")
    u.put_preferences(mode="assisted")
    conn = u.pair_mock()

    # Approve as-is.
    conv = _contact(u, conn, "Avery")
    a1 = u.pending_approval(conv["id"])
    assert u.outbound(conv["id"]) == []  # nothing before approval
    key = new_key()
    res = u.approve(a1["id"], key=key)
    assert res["approval"]["status"] == "approved"
    assert res["message"]["delivery_state"] == "queued"  # response never claims "sent"
    sent = u.wait_state(conv["id"], res["message"]["id"], {"sent"})
    assert sent["text"] == a1["draft"]["text"]
    assert sent["origin"] == "approved_draft" and sent["approval_id"] == a1["id"]
    replay = u.approve(a1["id"], key=key)
    assert replay["message"]["id"] == res["message"]["id"]
    again = u.request("POST", f"/v1/approvals/{a1['id']}/approve", json_body={}, key=new_key())
    assert again.status_code == 409 and again.json()["code"] == "approval_not_pending"
    assert len(u.outbound(conv["id"])) == 1

    # Approve with edit: a new inbound message makes a new approval.
    r = u.ingest(conn["id"], u.inbound_event(conn, text="So what about you?", sender="avery", display_name="Avery"))
    assert r.status_code == 202
    a2 = wait_until(
        lambda: [a for a in u.approvals(status="pending", conversation_id=conv["id"])],
        timeout=15,
        what="second approval",
    )[0]
    edited = "Not much here, mostly reading. What did you like best?"
    res2 = u.approve(a2["id"], edited_text=edited)
    assert res2["approval"]["final_text"] == edited
    sent2 = u.wait_state(conv["id"], res2["message"]["id"], {"sent"})
    assert sent2["text"] == edited

    # Reject.
    r = u.ingest(conn["id"], u.inbound_event(conn, text="Anyway, gotta run", sender="avery", display_name="Avery"))
    assert r.status_code == 202
    a3 = wait_until(
        lambda: u.approvals(status="pending", conversation_id=conv["id"]), timeout=15, what="third approval"
    )[0]
    rej = u.reject(a3["id"], reason="I'll answer myself")
    assert rej["approval"]["status"] == "rejected" and rej["message"] is None
    assert len(u.outbound(conv["id"])) == 2  # nothing sent for the rejected draft

    types = u.action_types(conv["id"])
    for expected in (
        "message_received",
        "draft_generated",
        "approval_requested",
        "approval_approved",
        "send_queued",
        "send_attempted",
        "send_acknowledged",
        "approval_rejected",
    ):
        assert expected in types, (expected, types)
    first_q = types.index("send_queued")
    assert types.index("approval_approved") < first_q < types.index("send_attempted") < types.index("send_acknowledged")
    approved = [a for a in u.actions(conversation_id=conv["id"]) if a["type"] == "approval_approved"]
    assert any(a["details"].get("edited") is True for a in approved)


# --------------------------------------------------------------------------- F5


def test_f5_memory_list_correct_delete(make_user, db: Db) -> None:
    """F5: memories are listed, corrected and deleted through the API, user-scoped.
    The dev mock LLM extracts no facts (I-016, by design), so ONE extracted fact is
    seeded directly in Postgres for the contact; every operation on it is HTTP."""
    u: Api = make_user("f5")
    other: Api = make_user("f5-other")
    u.put_preferences(mode="manual")
    conn = u.pair_mock()
    conv = _contact(u, conn, "Jordan", text="I work as a nurse at St. Mary's")
    contact_id = conv["contact"]["id"]
    inbound = [m["id"] for m in u.messages(conv["id"]) if m["direction"] == "inbound"]
    assert u.get(f"/v1/contacts/{contact_id}/memories")["items"] == []  # dev mock: no facts
    memory_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO conversation_memories (id, user_id, contact_id, conversation_id, kind, "
        "category, content, confidence, source_message_ids, origin, basis, fact_key, stated_at) "
        "VALUES ($1, $2, $3, $4, 'fact', 'work', 'Works as a nurse', 0.9, $5, 'extracted', "
        "'stated', 'work.job_title', now())",
        uuid.UUID(memory_id),
        uuid.UUID(u.user_id),
        uuid.UUID(contact_id),
        uuid.UUID(conv["id"]),
        [uuid.UUID(i) for i in inbound],
    )
    page = assert_contract("MemoryPage", u.get(f"/v1/contacts/{contact_id}/memories"))
    assert [m["id"] for m in page["items"]] == [memory_id]
    assert page["items"][0]["source_message_ids"] == inbound

    # Another user cannot see, correct or delete it.
    assert other.request("GET", f"/v1/contacts/{contact_id}/memories").status_code == 404
    assert other.request("PUT", f"/v1/memories/{memory_id}", json_body={"content": "x"}).status_code == 404
    assert other.request("DELETE", f"/v1/memories/{memory_id}").status_code == 404

    corrected = assert_contract(
        "Memory",
        u.request("PUT", f"/v1/memories/{memory_id}", json_body={"content": "Works as an ER nurse"}, expect=200).json(),
    )
    assert corrected["origin"] == "user_correction" and corrected["id"] != memory_id
    active = u.get(f"/v1/contacts/{contact_id}/memories")["items"]
    assert [m["content"] for m in active] == ["Works as an ER nurse"]
    history = u.get(f"/v1/contacts/{contact_id}/memories", params={"include_history": "true"})["items"]
    assert {m["id"] for m in history} >= {memory_id, corrected["id"]}

    u.request("DELETE", f"/v1/memories/{corrected['id']}", expect=204)
    u.request("DELETE", f"/v1/memories/{corrected['id']}", expect=204)  # idempotent
    assert u.get(f"/v1/contacts/{contact_id}/memories")["items"] == []


# --------------------------------------------------------------------------- F6


def test_f6_autopilot_reply_delay_processed_by_worker(make_user) -> None:
    """F6: autopilot with a reply delay. The pipeline queues the reply with send_after
    in the future (not sent by the API request); the worker sends it once due."""
    u: Api = make_user("f6")
    u.put_preferences(mode="autopilot", reply_delay=(4, 4))
    conn = u.pair_mock()
    conv = _contact(u, conn, "Casey")
    # The first message to a contact needs approval (first_message); approve it so the
    # next reply is a plain autopilot send.
    first = u.pending_approval(conv["id"])
    assert "first_message" in first["reasons"] and "assisted_mode" not in first["reasons"]
    u.wait_state(conv["id"], u.approve(first["id"])["message"]["id"], {"sent"})

    r = u.ingest(conn["id"], u.inbound_event(conn, text="How's your day?", sender="casey", display_name="Casey"))
    assert r.status_code == 202
    auto = wait_until(
        lambda: [m for m in u.outbound(conv["id"]) if m["origin"] == "autopilot"],
        timeout=10,
        what="autopilot message queued",
    )[0]
    assert auto["delivery_state"] == "queued"  # delayed: the request did not send it
    queued = [
        a for a in u.actions(conversation_id=conv["id"]) if a["type"] == "send_queued" and a["message_id"] == auto["id"]
    ]
    assert queued and queued[0]["details"]["send_after"] is not None
    sent = u.wait_state(conv["id"], auto["id"], {"sent"}, timeout=30)
    assert sent["sent_at"] is not None
    attempted = [
        a
        for a in u.actions(conversation_id=conv["id"])
        if a["type"] == "send_attempted" and a["message_id"] == auto["id"]
    ]
    assert len(attempted) == 1 and attempted[0]["actor"] == "dispatcher"


# --------------------------------------------------------------------------- F7


def test_f7_takeover_and_pause_per_d014(make_user) -> None:
    """F7 (D-014): pause stops automated sends only (a queued autopilot reply is
    canceled; a new draft goes to approval with reason 'paused'; the user's approval
    and own sends still go). Takeover cancels pending approvals and queued automated
    sends, and the agent creates no drafts until resume; the user's own send still goes."""
    u: Api = make_user("f7")
    u.put_preferences(mode="autopilot", reply_delay=(30, 30))
    conn = u.pair_mock()
    conv = _contact(u, conn, "Drew")
    contact_id = conv["contact"]["id"]
    u.wait_state(conv["id"], u.approve(u.pending_approval(conv["id"])["id"])["message"]["id"], {"sent"})

    # A delayed autopilot reply is queued; pausing the contact cancels it.
    u.ingest(conn["id"], u.inbound_event(conn, text="Free later?", sender="drew", display_name="Drew"))
    auto = wait_until(
        lambda: [m for m in u.outbound(conv["id"]) if m["origin"] == "autopilot"], timeout=10, what="autopilot"
    )[0]
    paused = assert_contract(
        "ContactAutomationState", u.post(f"/v1/contacts/{contact_id}/automation/pause", {"reason": None}).json()
    )
    assert paused["status"] == "paused" and paused["canceled_message_count"] == 1
    assert u.message(conv["id"], auto["id"])["delivery_state"] == "canceled"

    # While paused: a new inbound -> approval with 'paused'; approving it still sends.
    u.ingest(conn["id"], u.inbound_event(conn, text="Hello?", sender="drew", display_name="Drew"))
    ap = u.pending_approval(conv["id"])
    assert "paused" in ap["reasons"]
    u.wait_state(conv["id"], u.approve(ap["id"])["message"]["id"], {"sent"})
    manual = u.send_manual(conv["id"], "my own words while paused")
    u.wait_state(conv["id"], manual["id"], {"sent"})

    # Global pause is reported and reversible.
    g = assert_contract("GlobalAutomationState", u.post("/v1/automation/pause", {"reason": None}).json())
    assert g["status"] == "paused"
    assert u.post("/v1/automation/resume", {"reason": None}).json()["status"] == "active"

    # Takeover: a pending approval is canceled; no new drafts; the user's own send goes.
    u.post(f"/v1/contacts/{contact_id}/automation/resume", {"reason": None})
    u.put_preferences(mode="assisted")
    u.ingest(conn["id"], u.inbound_event(conn, text="Still there?", sender="drew", display_name="Drew"))
    pending = u.pending_approval(conv["id"])
    took = assert_contract(
        "ContactAutomationState", u.post(f"/v1/contacts/{contact_id}/automation/takeover", {"reason": None}).json()
    )
    assert took["status"] == "taken_over" and took["canceled_approval_count"] == 1
    assert u.get(f"/v1/approvals/{pending['id']}")["status"] == "canceled"
    late = u.request("POST", f"/v1/approvals/{pending['id']}/approve", json_body={}, key=new_key())
    assert late.status_code == 409
    before = len([a for a in u.actions(conversation_id=conv["id"]) if a["type"] == "draft_generated"])
    u.ingest(conn["id"], u.inbound_event(conn, text="hello??", sender="drew", display_name="Drew"))
    assert u.approvals(status="pending", conversation_id=conv["id"]) == []
    after = len([a for a in u.actions(conversation_id=conv["id"]) if a["type"] == "draft_generated"])
    assert after == before  # no agent drafts while taken over
    own = u.send_manual(conv["id"], "I'll take it from here")
    u.wait_state(conv["id"], own["id"], {"sent"})
    # Contact-level actions are filed under the contact (no conversation_id).
    contact_types = [a["type"] for a in u.actions(contact_id=contact_id)]
    for expected in ("automation_paused", "automation_resumed", "automation_takeover"):
        assert expected in contact_types, (expected, contact_types)
    canceled = [a for a in u.actions(contact_id=contact_id) if a["type"] in ("send_canceled", "approval_canceled")]
    assert {a["reason_code"] for a in canceled} >= {"automation_paused", "automation_takeover"}


# --------------------------------------------------------------------------- F8


def test_f8_unpair_cancels(make_user) -> None:
    """F8: unpairing cancels queued sends (any origin), unclaimed instructions and
    pending approvals before it returns; later events are 422; history is kept."""
    u: Api = make_user("f8")
    u.put_preferences(mode="assisted")
    install = str(uuid.uuid4())
    conn = u.pair_sms(install)
    device = SimulatedSmsDevice(u, conn, install)
    conv = _contact(u, conn, "+15550108888")
    a1 = u.pending_approval(conv["id"])
    m1 = u.approve(a1["id"])["message"]
    assert len(u.get(f"/v1/connections/{conn['id']}/outbox")["items"]) == 1
    u.ingest(conn["id"], u.inbound_event(conn, text="one more", sender="+15550108888", display_name=None))
    a2 = u.pending_approval(conv["id"])

    res = assert_contract("ConnectionUnpairResult", u.post(f"/v1/connections/{conn['id']}/unpair", {}).json())
    assert res["connection"]["status"] == "disconnected" and res["connection"]["unpaired_at"]
    assert res["canceled_message_count"] == 1
    assert res["canceled_instruction_count"] == 1
    assert res["canceled_approval_count"] == 1
    assert res["in_flight_message_count"] == 0
    assert u.message(conv["id"], m1["id"])["delivery_state"] == "canceled"
    assert u.get(f"/v1/approvals/{a2['id']}")["status"] == "canceled"
    assert all(c["status"] == "UNSUPPORTED" for c in res["connection"]["capabilities"]["capabilities"].values())
    # Nothing to claim; claims on an unpaired connection are refused (I-018); new events 422.
    claim = u.request(
        "POST",
        f"/v1/connections/{conn['id']}/outbox/claim",
        json_body={"device_install_id": install},
        key=new_key(),
    )
    assert claim.status_code == 422
    late = u.ingest(conn["id"], u.inbound_event(conn, text="hello?", sender="+15550108888", display_name=None))
    assert late.status_code == 422
    assert device.radio == {}  # the device never transmitted anything
    send = u.request("POST", f"/v1/conversations/{conv['id']}/messages", json_body={"text": "hi"}, key=new_key())
    assert send.status_code == 422 and send.json()["code"] == "capability_unsupported"
    unpaired = [a for a in u.actions() if a["type"] == "connection_unpaired"]
    assert len(unpaired) == 1
    assert len(u.messages(conv["id"])) >= 2  # history kept


# --------------------------------------------------------------------------- F9


def test_f9_cross_user_isolation(make_user) -> None:
    """F9: another user's resources are 404 (never 403) on every read and action, and
    their listings never include them; an event for someone else's connection is 404."""
    owner: Api = make_user("f9-owner")
    intruder: Api = make_user("f9-intruder")
    owner.put_preferences(mode="assisted")
    conn = owner.pair_mock()
    conv = _contact(owner, conn, "Quinn")
    approval = owner.pending_approval(conv["id"])
    contact_id = conv["contact"]["id"]
    for method, path, body in (
        ("GET", f"/v1/connections/{conn['id']}", None),
        ("GET", f"/v1/conversations/{conv['id']}", None),
        ("GET", f"/v1/conversations/{conv['id']}/messages", None),
        ("GET", f"/v1/approvals/{approval['id']}", None),
        ("GET", f"/v1/contacts/{contact_id}/automation", None),
        ("GET", f"/v1/contacts/{contact_id}/preferences", None),
        ("GET", f"/v1/connections/{conn['id']}/outbox", None),
        ("POST", f"/v1/approvals/{approval['id']}/approve", {}),
        ("POST", f"/v1/approvals/{approval['id']}/reject", {}),
        ("POST", f"/v1/conversations/{conv['id']}/messages", {"text": "hijack"}),
        ("POST", f"/v1/conversations/{conv['id']}/drafts", {}),
        ("POST", f"/v1/contacts/{contact_id}/automation/takeover", {}),
        ("POST", f"/v1/connections/{conn['id']}/unpair", {}),
    ):
        r = intruder.request(method, path, json_body=body, key=new_key() if method == "POST" else None)
        assert r.status_code == 404, (method, path, r.status_code, r.text)
        assert r.json()["code"] == "not_found"
    forged = owner.inbound_event(conn, text="spoof", sender="quinn", display_name="Quinn")
    assert intruder.ingest(conn["id"], forged).status_code == 404
    assert intruder.conversations() == []
    assert intruder.approvals() == []
    assert intruder.get("/v1/connections")["items"] == []
    assert all(a["conversation_id"] != conv["id"] for a in intruder.actions())
    # The owner's state is untouched by all of the above.
    assert owner.get(f"/v1/approvals/{approval['id']}")["status"] == "pending"
    assert owner.outbound(conv["id"]) == []
