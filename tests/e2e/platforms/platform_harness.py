"""WING-032 cross-platform harness, on top of the WING-025 one (``tests/e2e/harness.py``).

:class:`PlatformDevice` is a protocol-faithful stand-in for each device-side connector, built
from that connector's own wire shapes:

* ``sms``: the Android SMS connector (``sms:in:`` IDs, ``sms:+<number>`` threads), as
  ``SimulatedSmsDevice`` in WING-025.
* ``tinder`` / ``bumble`` / ``hinge`` with ``transport: notification``: the Android notification
  plugins (WING-027/028/029) as ``NotificationContractMapping.receivedEnvelope`` builds them:
  ``linked`` only with a stable conversation ID (``shortcut:<id>``), otherwise no thread and the
  fixed ``<platform>:unlinked`` sender; ``kind``, ``truncated``, ``content_hidden``,
  ``source: notification``. Their capability claim is ``send: UNSUPPORTED`` (no direct reply).
* ``hinge`` with ``transport: unofficial_api``: the experimental connector (WING-029)
  (``hinge:msg:`` IDs, ``hinge-match-`` channels, ``source: unofficial_api``, consent at pairing).
* ``imessage`` with ``transport: companion``: the macOS companion (WING-030), paired with the
  Swift app's own golden pairing body (``macos/wingman-companion/Fixtures``) and its envelope
  shapes (``imessage:<guid>``, ``imessage:chat:<chat guid>``; groups ``identity: ambiguous``).

Each device makes at most ONE "transport" attempt per instruction key (D-006) and reports
``connector_event.v1`` outcomes. Nothing is sent anywhere: these are simulators, and a pass is
``PASSED WITH MOCK`` only.
"""

from __future__ import annotations

import copy
import json
import os
import subprocess
import time
import uuid
from collections import Counter
from dataclasses import dataclass, field
from functools import cache
from typing import Any

import httpx
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

from tests.e2e.harness import CONTRACTS, REPO, Api, Server, assert_contract, new_key, now_z

COMPANION_FIXTURES = REPO / "macos" / "wingman-companion" / "Fixtures"
SCRIPTED_RUN = REPO / "scripts" / "e2e" / "platforms" / "scripted-dev-run.sh"
DATING = ("tinder", "bumble", "hinge")
CONSENT_VERSION = "d021-v1"
NAMESPACE = uuid.UUID("2f1b2d4e-5a0c-4c3e-9e57-0d6f5b7e3201")


# --------------------------------------------------------------------------- contract


@cache
def _event_validator() -> Draft202012Validator:
    """``connector_event.v1`` with its ``connector_capabilities.v1`` reference resolved locally
    (a ``connection.capabilities_changed`` payload embeds a capability document)."""
    event = json.loads((CONTRACTS / "schemas" / "connector_event.v1.json").read_text())
    caps = json.loads((CONTRACTS / "schemas" / "connector_capabilities.v1.json").read_text())
    registry = Registry().with_resources([(caps["$id"], Resource.from_contents(caps))])
    return Draft202012Validator(event, registry=registry, format_checker=Draft202012Validator.FORMAT_CHECKER)


# --------------------------------------------------------------------------- server


@dataclass
class ScriptedServer(Server):
    """``scripted-dev-run.sh``: ``scripts/dev-run.sh`` plus the ``[e2e scripted]`` date answers."""

    def start(self, timeout: float = 90.0) -> None:
        assert self.proc is None, "already running"
        log = self.log_path.open("ab")
        log.write(f"\n===== server start #{self.starts + 1} at {now_z()} =====\n".encode())
        log.flush()
        self.proc = subprocess.Popen(  # noqa: S603 - fixed repo script
            [str(SCRIPTED_RUN)],
            env=self.env,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        self.starts += 1
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(f"server exited early (code {self.proc.returncode})")
            try:
                if httpx.get(f"{self.base_url}/health", timeout=2).status_code == 200:
                    return
            except httpx.HTTPError:
                pass
            time.sleep(0.5)
        raise RuntimeError("server did not answer /health in time")


# --------------------------------------------------------------------------- capability claims


def _cap(status: str, notes: str | None = None, **extra: Any) -> dict[str, Any]:
    out: dict[str, Any] = {"status": status, **extra}
    if notes:
        out["notes"] = notes
    return out


def notification_capabilities(platform: str) -> dict[str, Any]:
    """What the Android notification plugins claim (``NotificationContractMapping.capabilitiesDocument``
    with reading on and direct reply off): read UNVERIFIED, everything else UNSUPPORTED."""
    return {
        "schema_version": "connector_capabilities.v1",
        "platform": platform,
        "connector_version": "0.1.0",
        "capabilities": {
            "read": _cap("UNVERIFIED", "Notification text only; may be truncated."),
            "draft": _cap("UNSUPPORTED"),
            "send": _cap("UNSUPPORTED", "No RemoteInput reply verified; assisted delivery only."),
            "swipe": _cap("UNSUPPORTED", "Blocked: no tested swipe path."),
            "calendar_write": _cap("UNSUPPORTED"),
            "profile_read": _cap("UNSUPPORTED"),
        },
        "assessed_at": now_z(),
    }


def hinge_experimental_capabilities() -> dict[str, Any]:
    """``HingeCapabilities.document()`` (experimental source set): read/send UNVERIFIED."""
    return {
        "schema_version": "connector_capabilities.v1",
        "platform": "hinge",
        "connector_version": "0.1.0",
        "capabilities": {
            "read": _cap("UNVERIFIED", "Experimental unofficial API. Not yet tested on a real account."),
            "draft": _cap("UNSUPPORTED"),
            "send": _cap(
                "UNVERIFIED", "Approval-only until the owner's live test (D-021).", reports_delivery_receipts=False
            ),
            "swipe": _cap("UNSUPPORTED", "Blocked by policy (D-021)."),
            "calendar_write": _cap("UNSUPPORTED"),
            "profile_read": _cap("UNSUPPORTED", "Profile routes are not allowlisted in v1."),
        },
        "assessed_at": now_z(),
    }


@cache
def _companion_pairing() -> dict[str, Any]:
    data: dict[str, Any] = json.loads((COMPANION_FIXTURES / "companion_pairing_requests.json").read_text())
    return data


def sms_capabilities(*, claim_verified: bool = False) -> dict[str, Any]:
    caps: dict[str, Any] = json.loads((CONTRACTS / "examples" / "connection.create.sms.request.json").read_text())[
        "capabilities"
    ]
    if claim_verified:
        caps["capabilities"]["send"].update(
            status="VERIFIED", evidence="client-claim", verified_at="2026-11-01T00:00:00Z"
        )
    return caps


# --------------------------------------------------------------------------- device


@dataclass
class PlatformDevice:
    """One paired device-side connection (see the module docstring for the shapes)."""

    api: Api
    connection: dict[str, Any]
    install_id: str
    platform: str
    transport: str
    radio: Counter[str] = field(default_factory=Counter)
    transmitted: list[dict[str, Any]] = field(default_factory=list)
    envelopes: dict[str, dict[str, Any]] = field(default_factory=dict)

    @property
    def id(self) -> str:
        return str(self.connection["id"])

    # -- inbound ---------------------------------------------------------------------

    def _event_id(self, kind: str, ext: str) -> str:
        return str(uuid.uuid5(NAMESPACE, f"{self.api.user_id}|{self.id}|{kind}|{ext}"))

    def received(
        self,
        text: str,
        *,
        kind: str = "chat_message",
        conversation: str | None = None,
        label: str | None = None,
        identity: str | None = None,
        truncated: bool = False,
        content_hidden: bool = False,
        ext: str | None = None,
        sender: str | None = None,
    ) -> dict[str, Any]:
        """A ``message.received`` envelope as this platform's connector builds it.

        ``conversation`` is the stable platform conversation ID (notification shortcut ID,
        Hinge match/channel, iMessage chat GUID, SMS number). Without one, a notification is
        ``unlinked`` (no thread, placeholder sender), never named by its label."""
        p = self.platform
        if p == "sms":
            number = conversation or sender or "+15550100000"
            event = self.api.inbound_event(
                self.connection, text=text, sender=number, display_name=None, thread=f"sms:{number}"
            )
            if ext:
                event["external_message_id"] = ext
            event["event_id"] = self._event_id("message.received", event["external_message_id"])
            _event_validator().validate(event)
            return event
        payload: dict[str, Any]
        if self.transport == "notification":
            if identity is None:
                identity = "linked" if conversation else "unlinked"
            linked = identity == "linked"
            hidden = content_hidden
            ext = ext or f"{p}:notif:{uuid.uuid4().hex[:16]}"
            thread = conversation if linked else None
            payload = {
                "sender": {
                    "external_contact_id": conversation if linked else f"{p}:unlinked",
                    "display_name": label,
                },
                "text": "" if hidden else text,
                "kind": kind,
                "sender_label": label,
                "identity": identity,
                "truncated": truncated,
                "content_hidden": hidden,
                "source": "notification",
            }
            if conversation:
                payload["external_conversation_id"] = conversation
        elif self.transport == "unofficial_api":
            conversation = conversation or "hinge-match-1"
            ext = ext or f"hinge:msg:{uuid.uuid4().hex[:20]}"
            thread = conversation
            payload = {
                "sender": {"external_contact_id": sender or f"hinge-subject-{conversation}", "display_name": label},
                "text": text,
                "kind": kind,
                "external_conversation_id": conversation,
                "sender_label": label,
                "identity": identity or "linked",
                "truncated": truncated,
                "content_hidden": content_hidden,
                "source": "unofficial_api",
            }
        elif self.transport == "companion":
            chat = conversation or "iMessage;-;+15550100001"
            ext = ext or f"imessage:WING032-{uuid.uuid4()}"
            thread = f"imessage:chat:{chat}"
            group = identity == "ambiguous"
            payload = {
                "sender": {"external_contact_id": f"imessage:{sender or '+15550100001'}", "display_name": None},
                "text": text,
                "kind": "chat_message",
                "source": "companion",
                "identity": "ambiguous" if group else "linked",
            }
        else:  # pragma: no cover
            raise AssertionError(f"unknown transport {self.transport}")
        event = {
            "schema_version": "connector_event.v1",
            "event_id": self._event_id("message.received", ext),
            "type": "message.received",
            "platform": p,
            "connection_id": self.id,
            "user_id": self.api.user_id,
            "external_message_id": ext,
            "external_thread_id": thread,
            "occurred_at": now_z(),
            "observed_at": now_z(),
            "payload": payload,
        }
        _event_validator().validate(event)
        return event

    def upload(self, event: dict[str, Any], *, fresh_client: bool = False) -> httpx.Response:
        return self.api.ingest(self.id, event, fresh_client=fresh_client)

    def deliver(self, text: str, **kw: Any) -> dict[str, Any]:
        """Upload one inbound envelope; returns the ``ConnectorEventIngestResult``."""
        r = self.upload(self.received(text, **kw))
        assert r.status_code == 202, r.text
        return assert_contract("ConnectorEventIngestResult", r.json())

    # -- outbox ----------------------------------------------------------------------

    def claim(self, *, key: str | None = None, max_instructions: int = 5) -> list[dict[str, Any]]:
        r = self.api.post(
            f"/v1/connections/{self.id}/outbox/claim",
            {"device_install_id": self.install_id, "max_instructions": max_instructions},
            key=key or new_key(),
        )
        body = assert_contract("OutboxClaimResult", r.json())
        return list(body["instructions"])

    def claim_raw(self) -> httpx.Response:
        return self.api.request(
            "POST",
            f"/v1/connections/{self.id}/outbox/claim",
            json_body={"device_install_id": self.install_id, "max_instructions": 5},
            key=new_key(),
        )

    def transmit(self, instruction: dict[str, Any]) -> bool:
        key = instruction["idempotency_key"]
        if self.radio[key] > 0:
            return False
        self.radio[key] += 1
        self.transmitted.append(instruction)
        return True

    def _outcome(self, instruction: dict[str, Any], etype: str, extra: dict[str, Any]) -> dict[str, Any]:
        stored = f"{etype}:{instruction['idempotency_key']}"
        if stored in self.envelopes:
            return self.envelopes[stored]
        ext = None
        if etype == "message.send_acknowledged":
            ext = {
                "sms": f"sms:out:{instruction['idempotency_key']}",
                "hinge": f"hinge:msg:sent-{uuid.uuid4().hex[:16]}",
                "imessage": f"imessage:SENT-{uuid.uuid4()}",
            }.get(self.platform, f"{self.platform}:out:{uuid.uuid4().hex[:16]}")
        event = {
            "schema_version": "connector_event.v1",
            "event_id": self._event_id(etype, instruction["idempotency_key"]),
            "type": etype,
            "platform": self.platform,
            "connection_id": self.id,
            "user_id": self.api.user_id,
            "external_message_id": ext,
            "external_thread_id": instruction.get("external_thread_id"),
            "occurred_at": now_z(),
            "observed_at": now_z(),
            "payload": {
                "outbound_message_id": instruction["outbound_message_id"],
                "idempotency_key": instruction["idempotency_key"],
                **extra,
            },
        }
        _event_validator().validate(event)
        self.envelopes[stored] = event
        return event

    def acknowledge(self, instruction: dict[str, Any]) -> httpx.Response:
        return self.upload(self._outcome(instruction, "message.send_acknowledged", {}))

    def fail(self, instruction: dict[str, Any], *, retryable: bool, code: str = "timeout") -> httpx.Response:
        return self.upload(
            self._outcome(
                instruction,
                "message.send_failed",
                {"failure": {"code": code, "detail": "simulated", "retryable": retryable}},
            )
        )

    def send_all(self, instructions: list[dict[str, Any]]) -> int:
        sent = 0
        for ins in instructions:
            if self.transmit(ins):
                sent += 1
                r = self.acknowledge(ins)
                assert r.status_code in (200, 202), r.text
        return sent

    def capabilities_changed(self, **statuses: str) -> httpx.Response:
        """``connection.capabilities_changed`` lowering (or keeping) capabilities, as a device
        does on a permission change (Full Disk Access / Automation denied, access revoked)."""
        doc = copy.deepcopy(self.connection["capabilities"])
        for name, status in statuses.items():
            doc["capabilities"][name] = {"status": status, "notes": "permission changed on the device"}
        doc["assessed_at"] = now_z()
        event = {
            "schema_version": "connector_event.v1",
            "event_id": str(uuid.uuid4()),
            "type": "connection.capabilities_changed",
            "platform": self.platform,
            "connection_id": self.id,
            "user_id": self.api.user_id,
            "external_message_id": None,
            "external_thread_id": None,
            "occurred_at": now_z(),
            "observed_at": now_z(),
            "payload": {"capabilities": doc},
        }
        _event_validator().validate(event)
        return self.upload(event)


DEVICES: list[PlatformDevice] = []  # every simulated device of the run (zero-duplicate check)


def _register(api: Api, conn: dict[str, Any], install: str, platform: str, transport: str) -> PlatformDevice:
    dev = PlatformDevice(api, conn, install, platform, transport)
    DEVICES.append(dev)
    return dev


def pair_sms(api: Api, *, claim_verified: bool = False) -> PlatformDevice:
    install = str(uuid.uuid4())
    body = {
        "platform": "sms",
        "display_name": "E2E simulated phone",
        "device_install_id": install,
        "capabilities": sms_capabilities(claim_verified=claim_verified),
    }
    conn = assert_contract("Connection", api.post("/v1/connections", body, expect=(200, 201)).json())
    return _register(api, conn, install, "sms", "sms_provider")


def pair_notification(api: Api, platform: str) -> PlatformDevice:
    install = str(uuid.uuid4())
    body = {
        "platform": platform,
        "display_name": f"{platform.title()} notifications",
        "transport": "notification",
        "device_install_id": install,
        "capabilities": notification_capabilities(platform),
    }
    conn = assert_contract("Connection", api.post("/v1/connections", body, expect=201).json())
    return _register(api, conn, install, platform, "notification")


def hinge_experimental_body(install: str, *, consent: bool = True) -> dict[str, Any]:
    body: dict[str, Any] = {
        "platform": "hinge",
        "display_name": "Hinge (experimental)",
        "transport": "unofficial_api",
        "experimental": True,
        "device_install_id": install,
        "capabilities": hinge_experimental_capabilities(),
    }
    if consent:
        body["experimental_consent"] = {
            "platform": "hinge",
            "consent_text_version": CONSENT_VERSION,
            "accepted_at": now_z(),
            "ban_risk_acknowledged": True,
        }
    return body


def pair_hinge_experimental(api: Api) -> PlatformDevice:
    install = str(uuid.uuid4())
    conn = assert_contract(
        "Connection", api.post("/v1/connections", hinge_experimental_body(install), expect=201).json()
    )
    return _register(api, conn, install, "hinge", "unofficial_api")


def pair_imessage(api: Api, kind: str = "default") -> PlatformDevice:
    """The companion's own golden pairing body, with a fresh install ID per test user."""
    body = copy.deepcopy(_companion_pairing()[kind])
    install = str(uuid.uuid4())
    body["device_install_id"] = install
    conn = assert_contract("Connection", api.post("/v1/connections", body, expect=201).json())
    return _register(api, conn, install, "imessage", "companion")


# --------------------------------------------------------------------------- reads


def notices(api: Api) -> list[dict[str, Any]]:
    body = assert_contract("InboundNoticePage", api.get("/v1/inbound-notices", params={"limit": 100}))
    return list(body["items"])


def assisted(api: Api, **params: Any) -> list[dict[str, Any]]:
    params.setdefault("limit", 100)
    body = assert_contract("AssistedDeliveryPage", api.get("/v1/assisted-deliveries", params=params))
    return list(body["items"])


def outbox(api: Api, connection_id: str, **params: Any) -> list[dict[str, Any]]:
    body = assert_contract("OutboxInstructionPage", api.get(f"/v1/connections/{connection_id}/outbox", params=params))
    return list(body["items"])


def conversation_by_thread(api: Api, connection_id: str, thread: str) -> dict[str, Any]:
    found = [
        c
        for c in api.conversations(limit=100)
        if c["connection_id"] == connection_id and c.get("external_thread_id") == thread
    ]
    assert len(found) == 1, f"expected one conversation for {thread}, got {len(found)}"
    return found[0]


def connection(api: Api, connection_id: str) -> dict[str, Any]:
    return assert_contract("Connection", api.get(f"/v1/connections/{connection_id}"))


def problem_code(r: httpx.Response) -> str:
    return str(r.json().get("code"))


# --------------------------------------------------------------------------- scenario log

RESULTS: dict[str, dict[str, Any]] = {}


def record(scenario: str, platform: str, *, result: str = "PASSED WITH MOCK", **facts: Any) -> None:
    """One row of ``scenario_results.json`` (written by the invariants test). A scenario
    records itself only after its assertions passed, so a failed test has no row."""
    RESULTS[scenario] = {"platform": platform, "result": result, **facts}


def write_artifact(name: str, data: Any) -> None:
    root = os.environ.get("WINGMAN_E2E_ARTIFACTS", str(REPO / "tests" / "e2e" / "platforms" / ".artifacts"))
    os.makedirs(root, exist_ok=True)
    with open(os.path.join(root, name), "w") as fh:
        fh.write(json.dumps(data, indent=2, sort_keys=True) + "\n")
