"""P4: the macOS iMessage companion (WING-030), paired with the Swift app's own golden body.

* Companion online: inbound 1:1 -> approval (``send`` UNVERIFIED) -> approved send claimed,
  "sent" once only after the companion's acknowledgement (in the real app: the sent row in
  ``chat.db``) -> status in the thread.
* Companion offline: an approved send stays queued with a pending instruction (safely queued,
  never sent); a claim followed by silence (Mac asleep) ends as ``lease_expired`` (outcome
  unknown): never ``sent``, never ``failed``, never retried, never re-leased.
* Group message: an ``ambiguous`` notice only.
* Automation permission denied on the Mac: the device lowers ``send`` to UNSUPPORTED; the queued
  approved send is canceled at claim and later replies become assisted (draft-only) deliveries.
"""

from __future__ import annotations

import time

from tests.e2e.harness import Api, wait_until
from tests.e2e.platforms.platform_harness import (
    assisted,
    connection,
    conversation_by_thread,
    notices,
    outbox,
    pair_imessage,
    record,
)

DM = "iMessage;-;+15550100001"
THREAD = f"imessage:chat:{DM}"


def test_p4_imessage_online_offline_lease_expiry_and_group(make_user) -> None:
    u: Api = make_user("p4-imessage")
    u.put_preferences(mode="autopilot")
    mac = pair_imessage(u)
    caps = connection(u, mac.id)["capabilities"]["capabilities"]
    assert caps["send"]["status"] == "UNVERIFIED" and caps["read"]["status"] == "UNVERIFIED"

    # Online: inbound -> approval -> approved send -> ack -> sent.
    mac.deliver("Dinner was so good last night", conversation=DM)
    conv = conversation_by_thread(u, mac.id, THREAD)
    a1 = u.pending_approval(conv["id"])
    assert "capability_unverified" in a1["reasons"]
    m1 = u.approve(a1["id"])["message"]
    [ins1] = mac.claim()
    assert ins1["external_thread_id"] == THREAD
    assert u.message(conv["id"], m1["id"])["delivery_state"] == "sending"
    assert mac.send_all([ins1]) == 1
    assert u.message(conv["id"], m1["id"])["delivery_state"] == "sent"

    # Group: notice only.
    mac.deliver("who's in for Friday?", conversation="iMessage;+;chat123456", identity="ambiguous")
    group = [n for n in notices(u) if n["identity"] == "ambiguous"]
    assert len(group) == 1 and group[0]["source"] == "companion"
    assert len(u.conversations(limit=100)) == 1

    # Offline: approved send stays queued with a pending instruction over many ticks.
    mac.deliver("you around?", conversation=DM)
    m2 = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    time.sleep(4)
    assert u.message(conv["id"], m2["id"])["delivery_state"] == "queued"
    assert [(p["outbound_message_id"], p["state"]) for p in outbox(u, mac.id)] == [(m2["id"], "pending")]

    # The Mac wakes, claims, then sleeps before sending: lease expiry = outcome unknown.
    [ins2] = mac.claim()
    assert ins2["outbound_message_id"] == m2["id"]

    def expired() -> list[dict]:
        return [a for a in u.actions(conversation_id=conv["id"]) if a["type"] == "lease_expired"]

    [row] = wait_until(expired, timeout=200, interval=3, what="lease_expired for the sleeping Mac")
    assert row["reason_code"] == "outcome_unknown" and row["details"]["retried"] is False
    time.sleep(3)
    m2_now = u.message(conv["id"], m2["id"])
    assert m2_now["delivery_state"] == "sending" and m2_now["sent_at"] is None and m2_now["attempt_count"] == 1
    assert outbox(u, mac.id) == [] and mac.claim() == []  # never retried or re-leased
    assert mac.radio[ins2["idempotency_key"]] == 0
    types = u.action_types(conv["id"])
    assert types.count("send_acknowledged") == 1 and "send_failed" not in types
    record("P4 iMessage: companion online -> approved send -> sent (ack from companion)", "imessage")
    record("P4 iMessage: companion offline -> safely queued; asleep after claim -> lease_expired", "imessage")
    record("P4 iMessage: group -> ambiguous notice only", "imessage")


def test_p4_imessage_automation_denied_downgrades_to_assisted(make_user) -> None:
    u: Api = make_user("p4-denied")
    u.put_preferences(mode="assisted")
    mac = pair_imessage(u)
    mac.deliver("hey :)", conversation=DM)
    conv = conversation_by_thread(u, mac.id, THREAD)
    m1 = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    # Automation (-1743) denied on the Mac: the companion lowers send before claiming.
    r = mac.capabilities_changed(send="UNSUPPORTED")
    assert r.status_code == 202, r.text
    assert connection(u, mac.id)["capabilities"]["capabilities"]["send"]["status"] == "UNSUPPORTED"
    assert mac.claim() == []
    assert u.message(conv["id"], m1["id"])["delivery_state"] == "canceled"
    mac.deliver("hello??", conversation=DM)
    [item] = assisted(u, status="pending")
    assert item["platform"] == "imessage" and u.approvals(status="pending") == []
    assert mac.radio == {}
    record("P4 iMessage: Automation denied -> queued send canceled at claim, then assisted only", "imessage")
