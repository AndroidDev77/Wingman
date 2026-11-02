"""P8: a device reporting ``connection.status_changed`` ``error`` holds its sends (D-030).

SMS over HTTP with the device simulator: an approved reply whose instruction is pending is
not leased while the phone reports ``error``; a reply approved during the hold gets no
instruction and stays ``queued`` (nothing is canceled); the worker ticks change nothing.
When the phone reports ``connected`` again, the next claim leases both exactly once.
"""

from __future__ import annotations

import time
import uuid
from typing import Any

from tests.e2e.harness import Api, now_z
from tests.e2e.platforms.platform_harness import (
    PlatformDevice,
    _event_validator,
    outbox,
    pair_sms,
    record,
)

PEER = "+15550132077"


def _status(dev: PlatformDevice, status: str) -> None:
    event: dict[str, Any] = {
        "schema_version": "connector_event.v1",
        "event_id": str(uuid.uuid4()),
        "type": "connection.status_changed",
        "platform": dev.platform,
        "connection_id": dev.id,
        "user_id": dev.api.user_id,
        "external_message_id": None,
        "external_thread_id": None,
        "occurred_at": now_z(),
        "observed_at": now_z(),
        "payload": {"status": status, "detail": None},
    }
    _event_validator().validate(event)
    r = dev.upload(event)
    assert r.status_code == 202, r.text


def test_p8_status_error_holds_sends_and_recovery_sends_each_once(make_user) -> None:
    u: Api = make_user("p8-hold")
    u.put_preferences(mode="assisted")
    dev = pair_sms(u)
    dev.deliver("hey, free tonight?", conversation=PEER)
    conv = u.conversation_for(PEER)
    first = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    assert [p["outbound_message_id"] for p in outbox(u, dev.id)] == [first["id"]]

    _status(dev, "error")
    assert dev.claim() == []
    dev.deliver("hello?", conversation=PEER)
    second = u.approve(u.pending_approval(conv["id"])["id"])["message"]
    time.sleep(4)  # worker ticks during the hold
    assert dev.claim() == []
    assert [p["outbound_message_id"] for p in outbox(u, dev.id)] == [first["id"]]
    for m in (first, second):
        assert u.message(conv["id"], m["id"])["delivery_state"] == "queued"
    held = [a for a in u.actions() if a["type"] == "policy_blocked" and a["reason_code"] == "connection_unavailable"]
    assert {a["details"]["hold"] for a in held} == {"started", True}

    _status(dev, "connected")
    leased = dev.claim()
    assert sorted(i["outbound_message_id"] for i in leased) == sorted([first["id"], second["id"]])
    assert dev.send_all(leased) == 2
    assert dev.claim() == []
    for m in (first, second):
        assert u.message(conv["id"], m["id"])["delivery_state"] == "sent"
    record(
        "P8 SMS: status error -> sends held (no claim, nothing canceled); connected -> each sent once",
        "sms",
    )
