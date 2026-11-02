"""P3: the experimental Hinge connector (WING-029, D-021/D-023/D-026), backend side.

The connector itself only exists in the sideloaded experimental build (``-Pwingman.experimental=
true``) and has never talked to Hinge (L0 to L10 not run). Here the backend contract is driven
with the connector's envelope shapes:

* pairing without the ban-risk consent is ``422 experimental_consent_required``; with it, the
  connection is ``experimental`` and never operator-verified;
* even in autopilot, every reply waits for approval (``capability_unverified``); an approved reply
  is sent once through the device outbox;
* an ``autopilot`` send can never be produced through the API, so the dispatch-time and
  claim-time re-checks are exercised by **fault injection** (the message's origin is forged to
  ``autopilot`` in the database): both cancel it, nothing is transmitted;
* revoking consent cancels pending work and stops the connection.
"""

from __future__ import annotations

import uuid

from tests.e2e.harness import Api, Db, assert_contract, wait_until
from tests.e2e.platforms.platform_harness import (
    connection,
    conversation_by_thread,
    hinge_experimental_body,
    outbox,
    pair_hinge_experimental,
    problem_code,
    record,
)


def test_p3_pairing_needs_consent_and_is_never_verified(make_user) -> None:
    u: Api = make_user("p3-pair")
    install = str(uuid.uuid4())
    r = u.request(
        "POST", "/v1/connections", json_body=hinge_experimental_body(install, consent=False), key=str(uuid.uuid4())
    )
    assert r.status_code == 422 and problem_code(r) == "experimental_consent_required", r.text
    assert u.get("/v1/connections")["items"] == []
    dev = pair_hinge_experimental(u)
    c = dev.connection
    assert c["experimental"] is True and c["transport"] == "unofficial_api"
    assert c["experimental_verified_at"] is None and c["experimental_consent"]["revoked_at"] is None
    assert c["capabilities"]["capabilities"]["swipe"]["status"] == "UNSUPPORTED"
    types = [a["type"] for a in u.actions()]
    assert "experimental_consent_recorded" in types
    record("P3 Hinge experimental: pairing without consent 422, with consent approval-only", "hinge (experimental)")


def test_p3_autopilot_is_approval_only_and_forged_autopilot_is_canceled(make_user, db: Db) -> None:
    u: Api = make_user("p3-flow")
    u.put_preferences(mode="autopilot")
    dev = pair_hinge_experimental(u)
    thread = "hinge-match-55"
    dev.deliver("Ha, that prompt answer was great. Coffee sometime?", conversation=thread, label="Riley")
    conv = conversation_by_thread(u, dev.id, thread)

    # 1. Autopilot selected: still an approval with capability_unverified; nothing sent.
    a1 = u.pending_approval(conv["id"])
    assert "capability_unverified" in a1["reasons"], a1["reasons"]
    assert u.outbound(conv["id"]) == [] and outbox(u, dev.id) == []
    m1 = u.approve(a1["id"])["message"]
    [ins1] = dev.claim()
    assert ins1["authorization"]["origin"] == "approved_draft"
    assert dev.send_all([ins1]) == 1
    assert u.message(conv["id"], m1["id"])["delivery_state"] == "sent"

    # 2. Claim-time re-check: an approved send whose origin is forged to autopilot (a state the
    #    API cannot produce) is canceled when the device claims; the device receives nothing.
    dev.deliver("Saturday?", conversation=thread, label="Riley")
    m2 = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    assert [p["outbound_message_id"] for p in outbox(u, dev.id)] == [m2["id"]]
    db.execute("UPDATE messages SET origin = 'autopilot' WHERE id = $1::uuid", m2["id"])
    assert dev.claim() == []
    assert u.message(conv["id"], m2["id"])["delivery_state"] == "canceled"
    canceled = [a for a in u.actions(conversation_id=conv["id"]) if a["type"] == "send_canceled"]
    assert [a["reason_code"] for a in canceled] == ["capability_unverified"], canceled

    # 3. Dispatch-time re-check: a retryable failure puts an approved send back in the queue;
    #    forged to autopilot while it waits, the worker cancels it instead of re-dispatching.
    dev.deliver("or Sunday", conversation=thread, label="Riley")
    m3 = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    [ins3] = dev.claim()
    assert dev.transmit(ins3)  # the socket dropped before the echo
    assert dev.fail(ins3, retryable=True, code="timeout").status_code == 202
    assert u.message(conv["id"], m3["id"])["delivery_state"] == "queued"
    db.execute("UPDATE messages SET origin = 'autopilot' WHERE id = $1::uuid", m3["id"])
    wait_until(
        lambda: u.message(conv["id"], m3["id"])["delivery_state"] == "canceled",
        timeout=90,
        interval=2,
        what="worker cancels the forged autopilot retry",
    )
    assert [p for p in outbox(u, dev.id) if p["attempt"] == 2] == []
    assert dev.claim() == []
    assert dev.radio[ins3["idempotency_key"]] == 1 and len(dev.transmitted) == 2
    record(
        "P3 Hinge experimental: autopilot -> approval; forged autopilot canceled at claim and at dispatch",
        "hinge (experimental)",
        fault_injection="messages.origin forged to autopilot",
    )


def test_p3_revoking_consent_cancels_pending_work(make_user) -> None:
    u: Api = make_user("p3-revoke")
    u.put_preferences(mode="assisted")
    dev = pair_hinge_experimental(u)
    thread = "hinge-match-77"
    dev.deliver("hey!", conversation=thread, label="Sky")
    conv = conversation_by_thread(u, dev.id, thread)
    m1 = u.approve(u.pending_approval(conv["id"])["id"])["message"]  # instruction pending
    dev.deliver("you there?", conversation=thread, label="Sky")
    a2 = u.pending_approval(conv["id"])  # approval pending

    r = u.post(f"/v1/connections/{dev.id}/experimental-consent/revoke", {})
    body = r.json()
    assert body["connection"]["experimental_consent"]["revoked_at"] is not None
    assert body["connection"]["status"] == "disconnected"
    assert body["canceled_instruction_count"] == 1 and body["canceled_approval_count"] == 1
    assert u.message(conv["id"], m1["id"])["delivery_state"] == "canceled"
    assert u.get(f"/v1/approvals/{a2['id']}")["status"] == "canceled"
    claim = dev.claim_raw()
    assert claim.status_code == 422, claim.text
    late = dev.upload(dev.received("still there?", conversation=thread, label="Sky"))
    assert late.status_code == 422, late.text
    caps = connection(u, dev.id)["capabilities"]["capabilities"]
    assert all(v["status"] == "UNSUPPORTED" for v in caps.values())
    assert_contract("Connection", body["connection"])
    assert "experimental_consent_revoked" in [a["type"] for a in u.actions()]
    record("P3 Hinge experimental: revoke consent -> pending work canceled, connection stopped", "hinge (experimental)")
