"""P1: Android SMS (WING-016), mock E2E over HTTP with the device-shaped SMS simulator.

Mandatory case: incoming SMS -> contextual AI reply -> configured send path -> outgoing record.
SMS ``send`` is UNVERIFIED (no physical-device run, I-003), so the configured path is
**approval-only**: even with autopilot selected and a client claiming ``VERIFIED``, the policy
adds ``capability_unverified`` and nothing is sent until the user approves. The device-observed
outcome is the simulator's acknowledgement, never a real SMS (``PASSED WITH MOCK``).
"""

from __future__ import annotations

import time

from tests.e2e.harness import Api
from tests.e2e.platforms.platform_harness import connection, outbox, pair_sms, record

PEER = "+15550132001"


def test_p1_sms_inbound_draft_approval_only_send_ack_audit_inbox(make_user) -> None:
    u: Api = make_user("p1-sms")
    u.put_preferences(mode="autopilot")  # the user asks for autopilot ...
    dev = pair_sms(u, claim_verified=True)  # ... and the phone claims VERIFIED send
    stored = connection(u, dev.id)["capabilities"]["capabilities"]["send"]
    assert stored["status"] == "UNVERIFIED", stored  # a client claim is never VERIFIED (D-016)

    # Inbound -> normalize/dedupe -> memory -> agent -> policy.
    first = dev.received("Hey! Are you still into climbing?", conversation=PEER)
    assert dev.upload(first).status_code == 202
    again = dev.upload(first)  # the phone re-uploads after a lost response
    assert again.status_code == 200 and again.json()["outcome"] == "duplicate_event"
    conv = u.conversation_for(PEER)
    assert conv["platform"] == "sms" and conv["external_thread_id"] == f"sms:{PEER}"

    # Contextual draft, autopilot refused: approval with capability_unverified.
    approval = u.pending_approval(conv["id"])
    assert "capability_unverified" in approval["reasons"], approval["reasons"]
    draft = approval["draft"]
    inbound = [m for m in u.messages(conv["id"]) if m["direction"] == "inbound"]
    assert len(inbound) == 1
    assert inbound[0]["id"] in draft["grounding"]["message_ids"]  # cites the SMS it answers
    assert draft["text"].startswith("[dev mock]")
    assert u.outbound(conv["id"]) == [] and outbox(u, dev.id) == []  # nothing before approval
    time.sleep(3)  # several worker ticks: still nothing
    assert u.outbound(conv["id"]) == []

    # Approve -> one instruction -> the phone claims, transmits once, acknowledges.
    msg = u.approve(approval["id"])["message"]
    assert msg["origin"] == "approved_draft" and msg["delivery_state"] == "queued"
    [ins] = dev.claim()
    assert ins["outbound_message_id"] == msg["id"] and ins["authorization"]["origin"] == "approved_draft"
    assert dev.send_all([ins]) == 1
    sent = u.message(conv["id"], msg["id"])
    assert sent["delivery_state"] == "sent" and sent["delivered_at"] is None  # ack is not delivery
    assert dev.claim() == []

    # Audit chain, in order, and the inbox shows the thread as stored.
    types = u.action_types(conv["id"])
    chain = [
        "message_received",
        "draft_generated",
        "approval_requested",
        "approval_approved",
        "send_queued",
        "send_attempted",
        "send_acknowledged",
    ]
    positions = [types.index(t) for t in chain]
    assert positions == sorted(positions), types
    assert types.count("send_acknowledged") == 1
    detail = u.get(f"/v1/conversations/{conv['id']}")
    assert detail["automation"]["effective_mode"] == "autopilot"
    states = [(m["direction"], m["delivery_state"]) for m in u.messages(conv["id"])]
    assert sorted(states) == [("inbound", "received"), ("outbound", "sent")]

    # A second inbound in autopilot again waits for approval: autopilot never applies.
    dev.deliver("ok cool, what gym?", conversation=PEER)
    second = u.pending_approval(conv["id"])
    assert "capability_unverified" in second["reasons"]
    assert [m for m in u.outbound(conv["id"]) if m["origin"] == "autopilot"] == []
    record(
        "P1 SMS inbound -> draft -> approval-only send -> ack -> audit -> inbox",
        "sms",
        autopilot="refused (capability_unverified)",
        sends=1,
    )
