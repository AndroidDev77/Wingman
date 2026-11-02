"""Every envelope and the capability document validate against the frozen contract.

Platform ``imessage`` matches ``connector_event.v1``'s platform pattern, so these
validate. Since contract v1.3.0 (ADR 0008) ``ConnectionCreateRequest`` also pairs
``imessage`` with ``transport: companion``; that is asserted here too.
"""

from __future__ import annotations

import json
from datetime import timedelta
from pathlib import Path
from typing import Any

import pytest
import yaml
from conftest import Clock
from fixture_db import ALICE, CARA, at
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from test_sendplan import SERVER_TIME, FakeMessages, instruction, make

from wingman_imessage.capabilities import capabilities_document
from wingman_imessage.chatdb import ChatDbReader
from wingman_imessage.connector import InboundPoller
from wingman_imessage.envelope import send_failed_envelope

CONTRACTS = Path(__file__).resolve().parents[3] / "contracts"
GROUP = "iMessage;+;chat123456789012345"


@pytest.fixture(scope="module")
def validators() -> dict[str, Draft202012Validator]:
    reg: Registry = Registry()
    schemas = {}
    for p in (CONTRACTS / "schemas").glob("*.json"):
        s = json.loads(p.read_text())
        schemas[p.name] = s
        r = Resource.from_contents(s)
        reg = reg.with_resource(s["$id"], r).with_resource(p.name, r)
    return {name: Draft202012Validator(s, registry=reg, format_checker=FormatChecker()) for name, s in schemas.items()}


def errors(v: Draft202012Validator, doc: dict[str, Any]) -> list[str]:
    return [f"{list(e.absolute_path)}: {e.message}" for e in v.iter_errors(doc)]


def test_all_emitted_envelopes_validate(fixture, store, scope, clock: Clock, validators) -> None:  # type: ignore[no-untyped-def]
    ev = validators["connector_event.v1.json"]
    fixture_reader = ChatDbReader(fixture.path)
    p = InboundPoller(fixture_reader, store, scope, clock=clock)
    p.poll()
    clock.now = at(10)
    fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text="Hey! Free tonight?", at=at(1))
    fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text="from blob", at=at(1.5), attributed=True)
    fixture.message(GROUP, sender=CARA, text="group", at=at(2))
    fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text="￼", at=at(3), attachments=1)
    fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text="z" * 25_000, at=at(4))
    state, _ = p.poll()
    envelopes = list(state.outbox.values())
    fixture_reader.close()

    s = make(fixture, store, scope, clock, FakeMessages(fixture, clock))
    ins = instruction()
    s.execute(ins, server_time=SERVER_TIME, anchor=clock())
    envelopes += s.reconcile({ins["idempotency_key"]: ins["text"]})
    fixture.mark_delivered(fixture.conn.execute("SELECT MAX(ROWID) FROM message").fetchone()[0])
    envelopes += s.reconcile({})
    envelopes.append(
        send_failed_envelope(
            scope=scope,
            chat_guid=None,
            outbound_message_id=ins["outbound_message_id"],
            idempotency_key="send:x:2xxxxxxx",
            code="permission_denied",
            retryable=False,
            detail=None,
            occurred_at=clock(),
        )
    )
    types = sorted({e["type"] for e in envelopes})
    assert types == ["message.delivery_receipt", "message.received", "message.send_acknowledged", "message.send_failed"]
    for env in envelopes:
        assert env["platform"] == "imessage"
        assert errors(ev, env) == [], env["type"]


def test_negative_envelope_is_rejected(validators) -> None:  # type: ignore[no-untyped-def]
    bad = {
        "schema_version": "connector_event.v1",
        "event_id": "not-a-uuid",
        "type": "message.received",
        "platform": "iMessage",
        "connection_id": "x",
        "user_id": "y",
        "external_message_id": None,
        "occurred_at": "2026-01-01 00:00:00",
        "payload": {"text": "t", "transport": "imessage"},
    }
    assert len(errors(validators["connector_event.v1.json"], bad)) >= 5


@pytest.mark.parametrize(("fda", "auto"), [(None, None), (False, None), (None, False), (False, False)])
def test_capabilities_document_validates(validators, fda, auto) -> None:  # type: ignore[no-untyped-def]
    doc = capabilities_document(at(0) + timedelta(microseconds=1), full_disk_access=fda, automation_granted=auto)
    assert errors(validators["connector_capabilities.v1.json"], doc) == []
    statuses = {k: v["status"] for k, v in doc["capabilities"].items()}
    assert "VERIFIED" not in statuses.values()
    assert statuses["draft"] == statuses["swipe"] == statuses["calendar_write"] == "UNSUPPORTED"


def test_imessage_is_pairable_with_companion_transport() -> None:
    """Contract v1.3.0 (ADR 0008): `imessage` pairs as a device-side connection with
    `transport: companion` (the Mac is the device install), needing `device_install_id`
    and a claimed capability document like `sms`."""
    spec = yaml.safe_load((CONTRACTS / "openapi.yaml").read_text())
    major, minor, _ = (int(x) for x in spec["info"]["version"].split("."))
    assert (major, minor) >= (1, 3)
    schemas = spec["components"]["schemas"]
    request = schemas["ConnectionCreateRequest"]
    assert "imessage" in request["properties"]["platform"]["enum"]
    assert "companion" in schemas["ConnectionTransport"]["enum"]
    rules = request["allOf"]
    device_side = [
        r for r in rules if "imessage" in (r.get("if", {}).get("properties", {}).get("platform", {}).get("enum") or [])
    ]
    assert device_side and set(device_side[0]["then"]["required"]) >= {"device_install_id", "capabilities"}
    transport = [
        r for r in rules if r.get("if", {}).get("properties", {}).get("platform", {}).get("const") == "imessage"
    ]
    assert transport and transport[0]["then"]["properties"]["transport"] == {"const": "companion"}
