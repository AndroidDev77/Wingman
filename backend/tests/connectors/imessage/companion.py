"""The WING-030 macOS companion as the backend sees it.

The request bodies and envelopes come from the Swift app's own golden files
(``macos/wingman-companion/Fixtures/``), which ``GoldenEnvelopeTests`` regenerates from
the real Swift mapper. ``received`` builds further envelopes of exactly that shape (a test
checks the key sets match) for flows that need more messages than the golden file has.
"""

import copy
import dataclasses
import json
import uuid
from datetime import UTC, datetime
from functools import cache
from pathlib import Path
from typing import Any

import httpx

from tests.automation.conftest import World, headers

FIXTURES = Path(__file__).resolve().parents[4] / "macos" / "wingman-companion" / "Fixtures"
# The install the golden pairing request carries (``device_install_id``).
COMPANION_INSTALL = "00000000-0000-4000-8000-0000000000c0"
FOREIGN_INSTALL = "00000000-0000-4000-8000-0000000000ff"
ALICE = "+15550100001"
DM = f"iMessage;-;{ALICE}"
DM_THREAD = f"imessage:chat:{DM}"
EVENT_NAMESPACE = uuid.UUID(
    "6f0e4a52-6d0b-5d4e-9a38-1f5d2c0e2002"
)  # = Swift Envelope.eventNamespace


@cache
def _load(name: str) -> Any:
    return json.loads((FIXTURES / name).read_text())


def golden_envelopes() -> list[dict[str, Any]]:
    return copy.deepcopy(_load("companion_envelopes.json"))


def pairing_request(kind: str = "default") -> dict[str, Any]:
    body: dict[str, Any] = copy.deepcopy(_load("companion_pairing_requests.json")[kind])
    return body


def rebind(envelope: dict[str, Any], world: World) -> dict[str, Any]:
    """The golden envelope for this test's user and connection (the Swift test used fixed
    IDs). The event ID stays deterministic: UUIDv5 of the new user and the original ID."""
    env = copy.deepcopy(envelope)
    env["connection_id"] = str(world.connection_id)
    env["user_id"] = str(world.scope.user_id)
    env["event_id"] = str(uuid.uuid5(EVENT_NAMESPACE, f"{world.scope.user_id}|{env['event_id']}"))
    return env


def ts(world: World, seconds_ago: float = 30) -> str:
    when = world.clock.now.timestamp() - seconds_ago
    return datetime.fromtimestamp(when, UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def received(
    world: World,
    text: str,
    *,
    guid: str | None = None,
    chat: str = DM,
    sender: str = ALICE,
    group: bool = False,
) -> dict[str, Any]:
    """A ``message.received`` exactly as the Swift ``Envelope.received`` builds it."""
    guid = guid or f"WING030-{uuid.uuid4()}"
    ext = f"imessage:{guid}"
    return {
        "schema_version": "connector_event.v1",
        "event_id": str(
            uuid.uuid5(EVENT_NAMESPACE, f"{world.scope.user_id}|message.received|{ext}")
        ),
        "type": "message.received",
        "platform": "imessage",
        "connection_id": str(world.connection_id),
        "user_id": str(world.scope.user_id),
        "external_message_id": ext,
        "external_thread_id": f"imessage:chat:{chat}",
        "occurred_at": ts(world),
        "observed_at": ts(world, 0),
        "payload": {
            "sender": {"external_contact_id": f"imessage:{sender}", "display_name": None},
            "text": text,
            "kind": "chat_message",
            "source": "companion",
            "identity": "ambiguous" if group else "linked",
        },
    }


def outcome(world: World, instruction: dict[str, Any], *, ok: bool = True) -> dict[str, Any]:
    """``send_acknowledged`` (from the sent chat.db row) or ``send_failed`` (Automation
    denied), shaped like the Swift executor's."""
    key = instruction["idempotency_key"]
    kind = "message.send_acknowledged" if ok else "message.send_failed"
    payload: dict[str, Any] = {
        "outbound_message_id": instruction["outbound_message_id"],
        "idempotency_key": key,
    }
    if not ok:
        payload["failure"] = {
            "code": "permission_denied",
            "retryable": False,
            "detail": "apple event error -1743",
        }
    return {
        "schema_version": "connector_event.v1",
        "event_id": str(uuid.uuid5(EVENT_NAMESPACE, f"{world.scope.user_id}|{kind}|{key}")),
        "type": kind,
        "platform": "imessage",
        "connection_id": str(world.connection_id),
        "user_id": str(world.scope.user_id),
        "external_message_id": f"imessage:SENT-{uuid.uuid4()}" if ok else None,
        "external_thread_id": instruction["external_thread_id"],
        "occurred_at": ts(world, 0),
        "observed_at": ts(world, 0),
        "payload": payload,
    }


async def pair(
    client: httpx.AsyncClient, world: World, kind: str = "default"
) -> tuple[World, dict[str, Any]]:
    """``createConnection`` with the companion's own body; returns the iMessage world."""
    resp = await client.post(
        "/v1/connections", json=pairing_request(kind), headers=headers(world.scope)
    )
    assert resp.status_code == 201, resp.text
    conn: dict[str, Any] = resp.json()
    mac = dataclasses.replace(world, connection_id=uuid.UUID(conn["id"]), platform="imessage")
    return mac, conn


async def post(client: httpx.AsyncClient, world: World, env: dict[str, Any]) -> httpx.Response:
    return await client.post(
        f"/v1/connections/{world.connection_id}/events",
        json=env,
        headers=headers(world.scope, env["event_id"]),
    )


async def claim(
    client: httpx.AsyncClient,
    world: World,
    device: str = COMPANION_INSTALL,
    key: str | None = None,
) -> httpx.Response:
    return await client.post(
        f"/v1/connections/{world.connection_id}/outbox/claim",
        json={"device_install_id": device, "max_instructions": 5},
        headers=headers(world.scope, key),
    )
