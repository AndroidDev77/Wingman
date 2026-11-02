"""P2: Tinder, Bumble and Hinge through the Android notification listener (WING-017/027/028/029).

Notification-assisted only (D-023): the plugins claim ``read: UNVERIFIED`` and ``send:
UNSUPPORTED`` (no RemoteInput reply verified). For each platform:

* every non-chat kind (match, like/comment, expiry, system) and every unlinked, ambiguous,
  truncated or hidden chat becomes an inbound notice with the right kind: no contact,
  conversation, draft, approval, assisted delivery, outbound message or instruction;
* a linked, complete chat message with autopilot on yields an **assisted delivery** (draft only),
  never an approval or a transmission; "I sent it" records ``user_reported_sent``, never
  ``sent``/``delivered``;
* a notification without a reply action never produces a send claim: the claim returns nothing,
  a forged acknowledgement is refused (``422``), and the user's own send is
  ``capability_unsupported``.
"""

from __future__ import annotations

import time
import uuid

import pytest

from tests.e2e.harness import Api, assert_contract
from tests.e2e.platforms.platform_harness import (
    DATING,
    assisted,
    conversation_by_thread,
    notices,
    outbox,
    pair_notification,
    problem_code,
    record,
)

# Device-shaped kinds per plugin (tinder.md §2, bumble.md §2, hinge.md §8).
KINDS = {
    "tinder": [("match", "It's a Match!"), ("like_comment", "Someone likes you"), ("system", "Tinder tip")],
    "bumble": [
        ("match", "Make the first move"),
        ("like_comment", "Alex reacted to your message"),
        ("expiry", "Your match expires in 3 hours"),
    ],
    "hinge": [("match", "You have a new match"), ("like_comment", "Jordan commented on your prompt")],
}


@pytest.mark.parametrize("platform", DATING)
def test_p2_notification_kinds_and_uncertain_chats_are_notices_only(make_user, platform: str) -> None:
    u: Api = make_user(f"p2-kinds-{platform}")
    u.put_preferences(mode="autopilot")
    dev = pair_notification(u, platform)
    assert dev.connection["capabilities"]["capabilities"]["send"]["status"] == "UNSUPPORTED"
    assert dev.connection["transport"] == "notification" and dev.connection["experimental"] is False

    cases: list[tuple[str, dict]] = [(k, {"kind": k, "label": "Alex"}) for k, _ in KINDS[platform]]
    texts = dict(KINDS[platform])
    cases += [
        ("unlinked", {"label": "Alex"}),  # a name, no stable conversation ID
        ("ambiguous", {"conversation": f"shortcut:{platform}-group", "identity": "ambiguous", "label": "Alex"}),
        ("truncated", {"conversation": f"shortcut:{platform}-77", "truncated": True, "label": "Alex"}),
        ("hidden", {"conversation": f"shortcut:{platform}-77", "content_hidden": True, "label": None}),
    ]
    for name, kw in cases:
        body = dev.deliver(texts.get(name, "hey are you free this wee"), **kw)
        assert body["outcome"] == "accepted" and body["message_id"] is None and body["notice_id"], (name, body)
    listed = {n["id"]: n for n in notices(u)}
    assert len(listed) == len(cases)
    kinds = sorted(n["kind"] for n in listed.values())
    expected = sorted([k for k, _ in KINDS[platform]] + ["chat_message"] * 4)
    assert kinds == expected, kinds
    assert all(n["source"] == "notification" and n["platform"] == platform for n in listed.values())

    # Nothing downstream exists: no person, no draft, no send of any kind.
    time.sleep(2)
    assert u.conversations(limit=100) == []
    assert u.approvals() == [] and assisted(u) == []
    assert outbox(u, dev.id) == [] and dev.claim() == []
    types = {a["type"] for a in u.actions()}
    assert "notice_received" in types
    assert not types & {"draft_generated", "approval_requested", "assisted_delivery_queued", "send_queued"}
    record(f"P2 {platform}: match/like/expiry/system and unlinked/ambiguous/partial chats -> notices only", platform)


@pytest.mark.parametrize("platform", DATING)
def test_p2_linked_chat_is_assisted_delivery_and_user_reported_sent_never_delivered(make_user, platform: str) -> None:
    u: Api = make_user(f"p2-assist-{platform}")
    u.put_preferences(mode="autopilot")
    dev = pair_notification(u, platform)
    thread = f"shortcut:{platform}-match-{uuid.uuid4().hex[:8]}"
    first = dev.received("Haha love that. What are you up to this week?", conversation=thread, label="Riley")
    assert dev.upload(first).status_code == 202
    # The same notification re-posted (an update) and re-uploaded: deduplicated.
    assert dev.upload(first).json()["outcome"] == "duplicate_event"
    conv = conversation_by_thread(u, dev.id, thread)
    assert conv["platform"] == platform

    [item] = assisted(u, status="pending")
    assert item["platform"] == platform and item["external_thread_id"] == thread
    assert item["text"].startswith("[dev mock]") and item["message_id"] is None
    # Assisted, not approval and not a send: no approval, no outbound message, no instruction.
    assert u.approvals() == []
    assert u.outbound(conv["id"]) == [] and outbox(u, dev.id) == [] and dev.claim() == []

    # The user copies it, sends it in the app themselves and taps "I sent it".
    r = u.post(f"/v1/assisted-deliveries/{item['id']}/confirm", {"sent_text": item["text"]})
    result = assert_contract("AssistedDeliveryResult", r.json())
    msg = result["message"]
    assert msg["origin"] == "assisted_draft" and msg["delivery_state"] == "user_reported_sent"
    assert msg["sent_at"] is None and msg["delivered_at"] is None
    time.sleep(2)  # worker ticks never transmit or upgrade it
    [out] = u.outbound(conv["id"])
    assert out["delivery_state"] == "user_reported_sent" and out["sent_at"] is None
    assert dev.claim() == [] and outbox(u, dev.id) == []
    types = u.action_types(conv["id"])
    for t in ("message_received", "draft_generated", "assisted_delivery_queued", "assisted_delivery_confirmed"):
        assert t in types, (t, types)
    assert not {"send_attempted", "send_acknowledged", "delivery_confirmed", "approval_requested"} & set(types)
    record(
        f"P2 {platform}: linked chat -> assisted draft -> 'I sent it' = user_reported_sent",
        platform,
        delivery="user_reported_sent (never sent/delivered)",
    )


@pytest.mark.parametrize("platform", DATING)
def test_p2_no_reply_action_never_produces_a_send_claim(make_user, platform: str) -> None:
    u: Api = make_user(f"p2-noreply-{platform}")
    u.put_preferences(mode="assisted")
    dev = pair_notification(u, platform)
    thread = f"shortcut:{platform}-{uuid.uuid4().hex[:8]}"
    dev.deliver("hey you", conversation=thread, label="Sam")
    conv = conversation_by_thread(u, dev.id, thread)

    # The user's own send through Wingman: refused, nothing queued.
    r = u.request("POST", f"/v1/conversations/{conv['id']}/messages", json_body={"text": "hi"}, key=str(uuid.uuid4()))
    assert r.status_code == 422 and problem_code(r) == "capability_unsupported", r.text
    # A device "acknowledgement" for a send that was never leased is refused.
    forged = {
        "outbound_message_id": str(uuid.uuid4()),
        "idempotency_key": f"send:{uuid.uuid4()}:1",
        "external_thread_id": thread,
    }
    r = dev.acknowledge(forged)
    assert r.status_code == 422, r.text
    assert dev.claim() == [] and outbox(u, dev.id) == []
    assert u.outbound(conv["id"]) == []
    types = {a["type"] for a in u.actions()}
    assert not types & {"send_queued", "send_attempted", "send_acknowledged"}
    record(f"P2 {platform}: notification without reply action -> no send claim possible", platform)
