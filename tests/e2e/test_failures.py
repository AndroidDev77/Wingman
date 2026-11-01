"""WING-025 failure injection, black-box over HTTP (IDs X1..X6 in docs/e2e_results.md).

Device-side (``sms``) scenarios use :class:`SimulatedSmsDevice`, a protocol-faithful
stand-in for the Android connector: it claims with ``claimOutboxInstructions``, makes at
most one "radio" transmission per instruction key and reports ``connector_event.v1``
outcomes. It sends no real SMS. The Android connector itself runs on the CI emulator in
the separate ``emulator`` job.
"""

from __future__ import annotations

import signal
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

from tests.e2e.harness import Api, Server, SimulatedSmsDevice, new_key, wait_until

DEVICES: list[SimulatedSmsDevice] = []  # every simulated device of the run (zero-dup check)


def _device(api: Api) -> tuple[dict, SimulatedSmsDevice]:
    install = str(uuid.uuid4())
    conn = api.pair_sms(install)
    dev = SimulatedSmsDevice(api, conn, install)
    DEVICES.append(dev)
    return conn, dev


def _sms_contact(api: Api, conn: dict, number: str, text: str = "hey it's me") -> dict:
    r = api.ingest(conn["id"], api.inbound_event(conn, text=text, sender=number, display_name=None))
    assert r.status_code == 202, r.text
    return api.conversation_for(number)


# --------------------------------------------------------------------------- X1


def test_x1_duplicate_inbound_concurrent_and_sequential(make_user) -> None:
    """X1: the same event 12x concurrently, the same message under 12 different event
    IDs concurrently, then both again sequentially: exactly one stored message, one
    draft and one reply per distinct inbound message (autopilot, mock transport)."""
    u: Api = make_user("x1")
    u.put_preferences(mode="autopilot")
    conn = u.pair_mock()
    first = u.inbound_event(conn, text="hi!", sender="morgan", display_name="Morgan")
    assert u.ingest(conn["id"], first).status_code == 202
    conv = u.conversation_for("Morgan")
    u.wait_state(conv["id"], u.approve(u.pending_approval(conv["id"])["id"])["message"]["id"], {"sent"})

    same_event = u.inbound_event(conn, text="dup A", sender="morgan", display_name="Morgan")
    ext = f"e2e-dup-{uuid.uuid4()}"
    same_message = [
        u.inbound_event(conn, text="dup B", sender="morgan", display_name="Morgan", external_message_id=ext)
        for _ in range(12)
    ]
    with ThreadPoolExecutor(24) as pool:
        a = list(pool.map(lambda e: u.ingest(conn["id"], e, fresh_client=True), [same_event] * 12))
        b = list(pool.map(lambda e: u.ingest(conn["id"], e, fresh_client=True), same_message))
    codes_a = sorted((r.status_code, r.json()["outcome"]) for r in a)
    codes_b = sorted((r.status_code, r.json()["outcome"]) for r in b)
    assert codes_a == [(200, "duplicate_event")] * 11 + [(202, "accepted")], codes_a
    assert codes_b == [(200, "duplicate_message")] * 11 + [(202, "accepted")], codes_b
    # Sequential repeats after the fact.
    assert u.ingest(conn["id"], same_event).json()["outcome"] == "duplicate_event"
    assert u.ingest(conn["id"], same_message[0]).json()["outcome"] == "duplicate_event"
    assert u.ingest(conn["id"], same_message[5]).json()["outcome"] == "duplicate_event"
    late = u.inbound_event(conn, text="dup B", sender="morgan", display_name="Morgan", external_message_id=ext)
    assert u.ingest(conn["id"], late).json()["outcome"] == "duplicate_message"

    def settled() -> list[dict] | None:
        out = u.outbound(conv["id"])
        auto = [m for m in out if m["origin"] == "autopilot"]
        return out if len(auto) >= 1 and all(m["delivery_state"] in ("sent", "canceled") for m in out) else None

    wait_until(settled, timeout=20, what="autopilot replies settled")
    msgs = u.messages(conv["id"])
    inbound = [m for m in msgs if m["direction"] == "inbound"]
    assert len(inbound) == 3  # first + dup A + dup B, each once
    drafts = [a for a in u.actions(conversation_id=conv["id"]) if a["type"] == "draft_generated"]
    assert len(drafts) == 3  # one draft per distinct inbound message
    received = [a for a in u.actions(conversation_id=conv["id"]) if a["type"] == "message_received"]
    assert len(received) == 3
    sent = [m for m in u.outbound(conv["id"]) if m["delivery_state"] == "sent"]
    # Approved first reply + at most one autopilot reply per later inbound message. The
    # newer inbound supersedes a still-queued older reply, so 1 or 2 autopilot sends.
    autopilot_sent = [m for m in sent if m["origin"] == "autopilot"]
    assert 1 <= len(autopilot_sent) <= 2, [(m["origin"], m["delivery_state"]) for m in u.outbound(conv["id"])]


# --------------------------------------------------------------------------- X2


def test_x2_offline_device_then_lease_expiry(make_user) -> None:
    """X2: (a) an sms device that never claims: the approved reply stays queued with a
    pending instruction across many worker ticks and is never sent. (b) A device that
    claims and then goes silent: after the server-set lease (120 s) the worker records
    lease_expired (outcome unknown); the message stays 'sending' (never 'sent', never
    'failed') and is not retried automatically. A late acknowledgement is still accepted."""
    u: Api = make_user("x2")
    u.put_preferences(mode="assisted")
    conn, dev = _device(u)
    conv = _sms_contact(u, conn, "+15550102222")
    a = u.pending_approval(conv["id"])
    assert "capability_unverified" in a["reasons"]  # SMS send is never VERIFIED by a client
    msg = u.approve(a["id"])["message"]
    time.sleep(6)  # ~6 worker dispatch ticks
    assert u.message(conv["id"], msg["id"])["delivery_state"] == "queued"
    pending = u.get(f"/v1/connections/{conn['id']}/outbox")["items"]
    assert [(p["outbound_message_id"], p["state"], p["attempt"]) for p in pending] == [(msg["id"], "pending", 1)]

    # (b) Claim, transmit nothing, report nothing.
    claimed = dev.claim()
    assert [c["outbound_message_id"] for c in claimed] == [msg["id"]]
    lease_s = time.mktime(time.strptime(claimed[0]["lease_expires_at"][:19], "%Y-%m-%dT%H:%M:%S")) - time.mktime(
        time.strptime(claimed[0]["leased_at"][:19], "%Y-%m-%dT%H:%M:%S")
    )
    assert 30 <= lease_s <= 600
    assert u.message(conv["id"], msg["id"])["delivery_state"] == "sending"

    def expired() -> list[dict]:
        return [x for x in u.actions(conversation_id=conv["id"]) if x["type"] == "lease_expired"]

    rows = wait_until(expired, timeout=lease_s + 30, interval=2, what="lease_expired audit")
    assert len(rows) == 1
    assert rows[0]["reason_code"] == "outcome_unknown" and rows[0]["actor"] == "system"
    assert rows[0]["details"]["retried"] is False and rows[0]["details"]["outcome"] == "unknown"
    time.sleep(4)
    m = u.message(conv["id"], msg["id"])
    assert m["delivery_state"] == "sending" and m["sent_at"] is None and m["attempt_count"] == 1
    assert u.get(f"/v1/connections/{conn['id']}/outbox")["items"] == []  # no automatic retry
    assert dev.claim() == []  # the expired lease is never re-issued, not even to the same install
    types = u.action_types(conv["id"])
    assert "send_failed" not in types and "send_acknowledged" not in types

    # The device really did send late (results are the truth, ADR 0006): accepted.
    assert dev.transmit(claimed[0])
    r = dev.acknowledge(claimed[0])
    assert r.status_code == 202, r.text
    assert u.message(conv["id"], msg["id"])["delivery_state"] == "sent"


# --------------------------------------------------------------------------- X3


def test_x3_retry_after_retryable_failure_is_a_new_attempt_by_the_worker(make_user) -> None:
    """X3: the device reports send_failed retryable=true. The message goes back to
    queued with a backoff; the WORKER (no client call) creates attempt 2 with a new key
    once due; the device sends attempt 2 once. Attempt 1's key is never reused."""
    u: Api = make_user("x3")
    u.put_preferences(mode="assisted")
    conn, dev = _device(u)
    conv = _sms_contact(u, conn, "+15550103333")
    msg = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    [ins1] = dev.claim()
    assert ins1["attempt"] == 1
    assert dev.transmit(ins1)  # the radio attempt failed (airplane mode, say)
    r = dev.fail(ins1, retryable=True, code="not_connected")
    assert r.status_code == 202, r.text
    m = u.message(conv["id"], msg["id"])
    assert m["delivery_state"] == "queued" and m["failure"]["code"] == "not_connected"
    requeue = [
        a for a in u.actions(conversation_id=conv["id"]) if a["type"] == "send_queued" and a["details"].get("retry")
    ]
    assert len(requeue) == 1 and requeue[0]["details"]["next_attempt"] == 2

    # The worker, not a client, creates attempt 2 after the backoff (30 s base).
    def attempt2() -> list[dict]:
        return [p for p in u.get(f"/v1/connections/{conn['id']}/outbox")["items"] if p["attempt"] == 2]

    [pending2] = wait_until(attempt2, timeout=60, interval=2, what="attempt 2 instruction created by the worker")
    assert pending2["idempotency_key"] != ins1["idempotency_key"]
    assert pending2["idempotency_key"].endswith(":2")
    [ins2] = dev.claim()
    assert ins2["id"] == pending2["id"]
    assert dev.send_all([ins2]) == 1
    sent = u.message(conv["id"], msg["id"])
    assert sent["delivery_state"] == "sent" and sent["attempt_count"] == 2
    assert dev.radio[ins1["idempotency_key"]] == 1 and dev.radio[ins2["idempotency_key"]] == 1
    types = u.action_types(conv["id"])
    assert types.count("send_failed") == 1 and types.count("send_acknowledged") == 1
    assert "send_dead_lettered" not in types


def test_x3b_non_retryable_failure_is_dead_lettered(make_user) -> None:
    """X3b: send_failed retryable=false is final: failed then dead_lettered, no new
    instruction, nothing sent."""
    u: Api = make_user("x3b")
    u.put_preferences(mode="assisted")
    conn, dev = _device(u)
    conv = _sms_contact(u, conn, "+15550103334")
    msg = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    [ins] = dev.claim()
    dev.transmit(ins)
    assert dev.fail(ins, retryable=False, code="permission_denied").status_code == 202
    assert u.message(conv["id"], msg["id"])["delivery_state"] == "dead_lettered"
    time.sleep(3)
    assert u.get(f"/v1/connections/{conn['id']}/outbox")["items"] == []
    assert dev.claim() == []


# --------------------------------------------------------------------------- X4


def test_x4_takeover_mid_flight(make_user) -> None:
    """X4: two approved replies are in flight on an sms connection. One is already
    leased by the device (sending), one is not yet claimed (pending). Takeover cancels
    the unclaimed one (message and instruction canceled; the device never receives it)
    and cannot recall the leased one, whose real outcome is still recorded."""
    u: Api = make_user("x4")
    u.put_preferences(mode="assisted")
    conn, dev = _device(u)
    conv = _sms_contact(u, conn, "+15550104444")
    contact_id = conv["contact"]["id"]
    m1 = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    [leased] = dev.claim()
    assert leased["outbound_message_id"] == m1["id"]
    u.ingest(conn["id"], u.inbound_event(conn, text="and?", sender="+15550104444", display_name=None))
    m2 = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    assert [p["outbound_message_id"] for p in u.get(f"/v1/connections/{conn['id']}/outbox")["items"]] == [m2["id"]]

    took = u.post(f"/v1/contacts/{contact_id}/automation/takeover", {"reason": None}).json()
    assert took["status"] == "taken_over" and took["canceled_message_count"] == 1
    assert u.message(conv["id"], m2["id"])["delivery_state"] == "canceled"
    assert (
        u.get(f"/v1/connections/{conn['id']}/outbox", params={"state": "canceled"})["items"][0]["outbound_message_id"]
        == m2["id"]
    )
    assert u.message(conv["id"], m1["id"])["delivery_state"] == "sending"  # not recallable
    again = dev.claim()
    assert [i["outbound_message_id"] for i in again] == [m1["id"]]  # only its own live lease
    assert dev.send_all(again) == 1
    assert u.message(conv["id"], m1["id"])["delivery_state"] == "sent"
    assert m2["id"] not in [i["outbound_message_id"] for i in dev.transmitted]
    canceled = [a for a in u.actions(conversation_id=conv["id"]) if a["type"] == "send_canceled"]
    assert [a["reason_code"] for a in canceled] == ["automation_takeover"]


# --------------------------------------------------------------------------- X5


def test_x5_server_restart_mid_flow_no_lost_or_duplicate_sends(make_user, server: Server) -> None:
    """X5: with work in every state (sms: one instruction pending, one leased and
    transmitted but not yet acknowledged; mock: a conversation with a sent reply), the
    server is killed with SIGKILL and started again, then stopped with SIGTERM and
    started again. Afterwards every authorized reply is sent exactly once: the device
    replays its old claim key (same response, ledger refuses a second radio attempt),
    the late acknowledgement is accepted, the pending instruction is leased once; and a
    new mock send after the restart is acknowledged (regression for the mock outbound-ID
    collision fixed in this PR)."""
    u: Api = make_user("x5")
    u.put_preferences(mode="assisted")
    conn, dev = _device(u)
    conv = _sms_contact(u, conn, "+15550105555")
    m1 = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    claim_key = new_key()
    [ins1] = dev.claim(key=claim_key)
    assert dev.transmit(ins1)  # on the radio; the ack has not been uploaded yet
    u.ingest(conn["id"], u.inbound_event(conn, text="you there?", sender="+15550105555", display_name=None))
    m2 = u.approve(u.pending_approval(conv["id"])["id"])["message"]

    mconn = u.pair_mock("Restart sim")
    r = u.ingest(mconn["id"], u.inbound_event(mconn, text="hello", sender="robin", display_name="Robin"))
    assert r.status_code == 202
    mconv = u.conversation_for("Robin")
    first_mock = u.approve(u.pending_approval(mconv["id"])["id"])["message"]
    u.wait_state(mconv["id"], first_mock["id"], {"sent"})

    server.restart(signal.SIGKILL)
    assert u.message(conv["id"], m1["id"])["delivery_state"] == "sending"
    assert u.message(conv["id"], m2["id"])["delivery_state"] == "queued"

    # The device lost the claim response: it replays the same key.
    replayed = dev.claim(key=claim_key)
    assert [i["id"] for i in replayed] == [ins1["id"]]
    assert dev.transmit(replayed[0]) is False  # ledger: no second radio attempt
    assert dev.acknowledge(ins1).status_code == 202
    assert dev.acknowledge(ins1).status_code == 200  # duplicate upload: no state change
    server.restart(signal.SIGTERM)
    fresh = dev.claim()
    assert [i["outbound_message_id"] for i in fresh] == [m2["id"]]
    assert dev.send_all(fresh) == 1
    assert u.message(conv["id"], m1["id"])["delivery_state"] == "sent"
    assert u.message(conv["id"], m2["id"])["delivery_state"] == "sent"
    assert dev.claim() == []

    # Mock after two restarts: a fresh simulator, re-taught the thread by a new inbound.
    u.ingest(mconn["id"], u.inbound_event(mconn, text="back again", sender="robin", display_name="Robin"))
    second_mock = u.approve(u.pending_approval(mconv["id"])["id"])["message"]
    done = u.wait_state(mconv["id"], second_mock["id"], {"sent"})
    assert done["external_message_id"] != u.message(mconv["id"], first_mock["id"])["external_message_id"]
    acks = [a for a in u.actions() if a["type"] == "send_acknowledged"]
    per_message: dict[str, int] = {}
    for a in acks:
        per_message[a["message_id"]] = per_message.get(a["message_id"], 0) + 1
    assert all(n == 1 for n in per_message.values()), per_message
    assert server.starts >= 3
