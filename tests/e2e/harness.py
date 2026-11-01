"""WING-025 end-to-end harness: a black-box client for a RUNNING Wingman backend.

Everything here talks to the server over real HTTP (``httpx``), exactly as the Android
app or a device connector would. The only non-HTTP access is :class:`Db`, a read-mostly
observer used for (a) the run-wide zero-duplicate-sends invariant and (b) seeding one
memory row, because the labeled dev mock LLM deliberately extracts no facts (I-016).

The server is ``scripts/dev-run.sh`` (``uvicorn app.worker.dev_server:app``: API and the
background worker in one process, dev auth stub, dev mock LLM, real Postgres migrated by
Alembic). :class:`Server` starts it, stops it gracefully (SIGTERM) or kills it
(SIGKILL) and starts it again, so restart scenarios run against the same database.
"""

from __future__ import annotations

import asyncio
import json
import os
import signal
import subprocess
import time
import uuid
from collections import Counter
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from functools import cache
from pathlib import Path
from typing import Any

import httpx
import yaml
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

REPO = Path(__file__).resolve().parents[2]
CONTRACTS = REPO / "contracts"
DEFAULT_BASE_URL = "http://127.0.0.1:8000"


def now_z() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def new_key() -> str:
    return str(uuid.uuid4())


def wait_until(probe: Callable[[], Any], *, timeout: float, interval: float = 0.5, what: str = "condition") -> Any:
    """Poll ``probe`` until it returns a truthy value; fail with ``what`` on timeout."""
    deadline = time.monotonic() + timeout
    last: Any = None
    while time.monotonic() < deadline:
        last = probe()
        if last:
            return last
        time.sleep(interval)
    raise AssertionError(f"timed out after {timeout}s waiting for {what} (last={last!r})")


# --------------------------------------------------------------------------- contract


@cache
def _openapi() -> dict[str, Any]:
    doc: dict[str, Any] = yaml.safe_load((CONTRACTS / "openapi.yaml").read_text())
    return doc


@cache
def _validator(schema_name: str) -> Draft202012Validator:
    caps = json.loads((CONTRACTS / "schemas" / "connector_capabilities.v1.json").read_text())
    event = json.loads((CONTRACTS / "schemas" / "connector_event.v1.json").read_text())
    root = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://wingman.invalid/contracts/openapi.json",
        "components": _openapi()["components"],
        "$ref": f"#/components/schemas/{schema_name}",
    }
    registry = Registry().with_resources(
        [
            (u, Resource.from_contents(caps))
            for u in (
                "https://wingman.invalid/contracts/schemas/connector_capabilities.v1.json",
                "https://wingman.invalid/contracts/connector_capabilities.v1.json",
            )
        ]
        + [
            (
                "https://wingman.invalid/contracts/schemas/connector_event.v1.json",
                Resource.from_contents(event),
            )
        ]
    )
    return Draft202012Validator(root, registry=registry, format_checker=Draft202012Validator.FORMAT_CHECKER)


def assert_contract(schema_name: str, body: Any) -> Any:
    """Validate a response body against ``#/components/schemas/<schema_name>``."""
    _validator(schema_name).validate(body)
    return body


@cache
def _event_validator() -> Draft202012Validator:
    schema = json.loads((CONTRACTS / "schemas" / "connector_event.v1.json").read_text())
    return Draft202012Validator(schema, format_checker=Draft202012Validator.FORMAT_CHECKER)


# --------------------------------------------------------------------------- server


@dataclass
class Server:
    """``scripts/dev-run.sh`` as a child process (single-process API + worker)."""

    env: dict[str, str]
    log_path: Path
    base_url: str = DEFAULT_BASE_URL
    proc: subprocess.Popen[bytes] | None = None
    starts: int = 0

    def start(self, timeout: float = 90.0) -> None:
        assert self.proc is None, "already running"
        log = self.log_path.open("ab")
        log.write(f"\n===== server start #{self.starts + 1} at {now_z()} =====\n".encode())
        log.flush()
        self.proc = subprocess.Popen(  # noqa: S603 - fixed repo script
            [str(REPO / "scripts" / "dev-run.sh")],
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

    def stop(self, sig: int = signal.SIGTERM, timeout: float = 40.0) -> int | None:
        """Stop with ``sig`` (SIGTERM: graceful; SIGKILL: crash). Returns the exit code."""
        proc = self.proc
        if proc is None:
            return None
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            pass
        try:
            code = proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            code = proc.wait(timeout=10)
        self.proc = None
        with self.log_path.open("ab") as log:
            log.write(f"===== server stopped by signal {sig} (exit {code}) =====\n".encode())
        return code

    def restart(self, sig: int = signal.SIGTERM) -> None:
        self.stop(sig)
        self.start()

    def log_text(self) -> str:
        return self.log_path.read_text(errors="replace")


# --------------------------------------------------------------------------- client


class ApiError(AssertionError):
    pass


@dataclass
class Api:
    """One signed-in user (dev-stub token) talking to the API."""

    base_url: str
    token: str
    subject: str
    user_id: str = ""
    _client: httpx.Client | None = None

    def client(self) -> httpx.Client:
        if self._client is None:
            self._client = httpx.Client(
                base_url=self.base_url,
                headers={"Authorization": f"Bearer {self.token}"},
                timeout=60,
            )
        return self._client

    def close(self) -> None:
        if self._client is not None:
            self._client.close()
            self._client = None

    # -- raw -------------------------------------------------------------------------

    def request(
        self,
        method: str,
        path: str,
        *,
        json_body: Any = None,
        key: str | None = None,
        headers: dict[str, str] | None = None,
        params: dict[str, Any] | None = None,
        expect: int | Iterable[int] | None = None,
        fresh_client: bool = False,
    ) -> httpx.Response:
        h = dict(headers or {})
        if key is not None:
            h["Idempotency-Key"] = key
        if fresh_client:
            with httpx.Client(
                base_url=self.base_url,
                headers={"Authorization": f"Bearer {self.token}"},
                timeout=60,
            ) as c:
                r = c.request(method, path, json=json_body, headers=h, params=params)
        else:
            r = self.client().request(method, path, json=json_body, headers=h, params=params)
        if expect is not None:
            allowed = {expect} if isinstance(expect, int) else set(expect)
            if r.status_code not in allowed:
                raise ApiError(f"{method} {path} -> {r.status_code} (want {allowed}): {r.text}")
        return r

    def get(self, path: str, **kw: Any) -> Any:
        return self.request("GET", path, expect=200, **kw).json()

    def post(self, path: str, body: Any = None, *, expect: int | Iterable[int] = 200, **kw: Any):
        kw.setdefault("key", new_key())
        return self.request("POST", path, json_body=body if body is not None else {}, expect=expect, **kw)

    # -- operations --------------------------------------------------------------------

    def me(self) -> dict[str, Any]:
        body = assert_contract("User", self.get("/v1/me"))
        self.user_id = body["id"]
        return body

    def put_preferences(
        self,
        *,
        intent: str = "serious",
        mode: str = "assisted",
        style: dict[str, Any] | None = None,
        reply_delay: tuple[int, int] | None = None,
        approval_required_for: list[str] | None = None,
        if_match: str | None = None,
    ) -> dict[str, Any]:
        body = {
            "intent": {"mode": intent, "custom_description": None},
            "dealbreakers": [],
            "style": style or {},
            "automation": {
                "mode": mode,
                "active_hours": None,
                "reply_delay_seconds": None if reply_delay is None else {"min": reply_delay[0], "max": reply_delay[1]},
                "daily_send_limit": None,
                "approval_required_for": approval_required_for or [],
            },
        }
        headers = {"If-Match": if_match} if if_match is not None else None
        r = self.request("PUT", "/v1/preferences", json_body=body, headers=headers, expect=200)
        return assert_contract("Preferences", r.json())

    def pair_mock(self, name: str = "E2E simulator") -> dict[str, Any]:
        r = self.post("/v1/connections", {"platform": "mock", "display_name": name}, expect=201)
        return assert_contract("Connection", r.json())

    def pair_sms(self, install_id: str, *, claim_verified: bool = False) -> dict[str, Any]:
        caps = json.loads((CONTRACTS / "examples" / "connection.create.sms.request.json").read_text())["capabilities"]
        if claim_verified:
            caps["capabilities"]["send"].update(
                status="VERIFIED", evidence="client-claim", verified_at="2026-11-01T00:00:00Z"
            )
        body = {
            "platform": "sms",
            "display_name": "E2E simulated phone",
            "device_install_id": install_id,
            "capabilities": caps,
        }
        r = self.post("/v1/connections", body, expect=(200, 201))
        return assert_contract("Connection", r.json())

    def inbound_event(
        self,
        connection: dict[str, Any],
        *,
        text: str,
        sender: str,
        display_name: str | None,
        external_message_id: str | None = None,
        thread: str | None = None,
        event_id: str | None = None,
    ) -> dict[str, Any]:
        platform = connection["platform"]
        is_mock = platform == "mock"
        ext = external_message_id or (
            f"e2e-in-{uuid.uuid4()}" if is_mock else f"sms:in:{uuid.uuid4().hex}{uuid.uuid4().hex[:8]}"
        )
        payload: dict[str, Any] = {
            "sender": {"external_contact_id": sender, "display_name": display_name},
            "text": text,
        }
        if is_mock:
            payload["mock"] = {"scenario_id": "wing025_e2e", "sequence": 1}
        event = {
            "schema_version": "connector_event.v1",
            "event_id": event_id or new_key(),
            "type": "mock.inbound_message_injected" if is_mock else "message.received",
            "platform": platform,
            "connection_id": connection["id"],
            "user_id": self.user_id,
            "external_message_id": ext,
            "external_thread_id": thread or (f"thread-{sender}" if is_mock else f"sms:{sender}"),
            "occurred_at": now_z(),
            "observed_at": now_z(),
            "payload": payload,
        }
        _event_validator().validate(event)
        return event

    def ingest(self, connection_id: str, event: dict[str, Any], *, fresh_client: bool = False) -> httpx.Response:
        return self.request(
            "POST",
            f"/v1/connections/{connection_id}/events",
            json_body=event,
            key=event["event_id"],
            fresh_client=fresh_client,
        )

    def conversations(self, **params: Any) -> list[dict[str, Any]]:
        body = assert_contract("ConversationPage", self.get("/v1/conversations", params=params))
        return body["items"]

    def conversation_for(self, contact_name: str) -> dict[str, Any]:
        found = [c for c in self.conversations(limit=100) if c["contact"]["display_name"] == contact_name]
        assert len(found) == 1, f"expected one conversation with {contact_name}, got {len(found)}"
        return found[0]

    def messages(self, conversation_id: str) -> list[dict[str, Any]]:
        body = assert_contract(
            "MessagePage", self.get(f"/v1/conversations/{conversation_id}/messages", params={"limit": 100})
        )
        return body["items"]

    def outbound(self, conversation_id: str) -> list[dict[str, Any]]:
        return [m for m in self.messages(conversation_id) if m["direction"] == "outbound"]

    def approvals(self, **params: Any) -> list[dict[str, Any]]:
        params.setdefault("limit", 100)
        body = assert_contract("ApprovalPage", self.get("/v1/approvals", params=params))
        return body["items"]

    def pending_approval(self, conversation_id: str) -> dict[str, Any]:
        def probe() -> dict[str, Any] | None:
            items = self.approvals(status="pending", conversation_id=conversation_id)
            return items[0] if items else None

        return wait_until(probe, timeout=15, what=f"pending approval in {conversation_id}")

    def approve(self, approval_id: str, edited_text: str | None = None, *, key: str | None = None):
        body = {"edited_text": edited_text} if edited_text is not None else {}
        r = self.post(f"/v1/approvals/{approval_id}/approve", body, key=key or new_key())
        return assert_contract("ApprovalDecisionResult", r.json())

    def reject(self, approval_id: str, reason: str | None = None) -> dict[str, Any]:
        r = self.post(f"/v1/approvals/{approval_id}/reject", {"reason": reason})
        return assert_contract("ApprovalDecisionResult", r.json())

    def send_manual(self, conversation_id: str, text: str) -> dict[str, Any]:
        r = self.post(f"/v1/conversations/{conversation_id}/messages", {"text": text}, expect=202)
        return assert_contract("Message", r.json())

    def actions(self, **params: Any) -> list[dict[str, Any]]:
        """Every audit entry for the filter (newest first), following cursors."""
        out: list[dict[str, Any]] = []
        params = {"limit": 100, **params}
        while True:
            body = assert_contract("AgentActionPage", self.get("/v1/agent-actions", params=params))
            out += body["items"]
            if not body["next_cursor"]:
                return out
            params["cursor"] = body["next_cursor"]

    def action_types(self, conversation_id: str) -> list[str]:
        """Audit types for a conversation, oldest first."""
        return [a["type"] for a in reversed(self.actions(conversation_id=conversation_id))]

    def message(self, conversation_id: str, message_id: str) -> dict[str, Any]:
        found = [m for m in self.messages(conversation_id) if m["id"] == message_id]
        assert found, f"message {message_id} not in conversation {conversation_id}"
        return found[0]

    def wait_state(
        self, conversation_id: str, message_id: str, states: set[str], timeout: float = 20
    ) -> dict[str, Any]:
        def probe() -> dict[str, Any] | None:
            m = self.message(conversation_id, message_id)
            return m if m["delivery_state"] in states else None

        return wait_until(probe, timeout=timeout, what=f"message {message_id} in {states}")


# --------------------------------------------------------------------------- device


@dataclass
class SimulatedSmsDevice:
    """A protocol-faithful stand-in for the Android SMS connector (ADR 0006).

    It claims leased instructions with a persisted claim key, performs at most ONE
    "radio" transmission per instruction idempotency key (D-006, like ``SmsSender``'s
    ledger), and reports outcomes as ``connector_event.v1`` envelopes. No real SMS is
    sent: this is a simulator, labeled as such in docs/e2e_results.md.
    """

    api: Api
    connection: dict[str, Any]
    install_id: str
    radio: Counter[str] = field(default_factory=Counter)
    transmitted: list[dict[str, Any]] = field(default_factory=list)
    # Durable envelope store, like SmsOutbox: a re-upload sends the IDENTICAL envelope
    # (same event_id, same body); a new body under an old event_id would be a 422.
    envelopes: dict[str, dict[str, Any]] = field(default_factory=dict)

    def claim(self, *, key: str | None = None, max_instructions: int = 5) -> list[dict[str, Any]]:
        r = self.api.post(
            f"/v1/connections/{self.connection['id']}/outbox/claim",
            {"device_install_id": self.install_id, "max_instructions": max_instructions},
            key=key or new_key(),
        )
        body = assert_contract("OutboxClaimResult", r.json())
        return list(body["instructions"])

    def transmit(self, instruction: dict[str, Any]) -> bool:
        """One radio attempt per key. Returns False (no transmission) for a used key."""
        key = instruction["idempotency_key"]
        if self.radio[key] > 0:
            return False
        self.radio[key] += 1
        self.transmitted.append(instruction)
        return True

    def _outcome(self, instruction: dict[str, Any], etype: str, payload_extra: dict[str, Any]):
        stored_key = f"{etype}:{instruction['idempotency_key']}"
        if stored_key in self.envelopes:
            return self.envelopes[stored_key]
        ext = f"sms:out:{instruction['idempotency_key']}" if etype != "message.send_failed" else None
        event = {
            "schema_version": "connector_event.v1",
            "event_id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{etype}:{instruction['idempotency_key']}")),
            "type": etype,
            "platform": "sms",
            "connection_id": self.connection["id"],
            "user_id": self.api.user_id,
            "external_message_id": ext,
            "external_thread_id": instruction.get("external_thread_id"),
            "occurred_at": now_z(),
            "observed_at": now_z(),
            "payload": {
                "outbound_message_id": instruction["outbound_message_id"],
                "idempotency_key": instruction["idempotency_key"],
                **payload_extra,
            },
        }
        _event_validator().validate(event)
        self.envelopes[stored_key] = event
        return event

    def acknowledge(self, instruction: dict[str, Any]) -> httpx.Response:
        event = self._outcome(instruction, "message.send_acknowledged", {})
        return self.api.ingest(self.connection["id"], event)

    def fail(self, instruction: dict[str, Any], *, retryable: bool, code: str = "not_connected") -> httpx.Response:
        event = self._outcome(
            instruction,
            "message.send_failed",
            {"failure": {"code": code, "detail": "simulated", "retryable": retryable}},
        )
        return self.api.ingest(self.connection["id"], event)

    def send_all(self, instructions: list[dict[str, Any]]) -> int:
        sent = 0
        for ins in instructions:
            if self.transmit(ins):
                sent += 1
                r = self.acknowledge(ins)
                assert r.status_code in (200, 202), r.text
        return sent


# --------------------------------------------------------------------------- db observer


class Db:
    """Read-mostly observer of the server's database (asyncpg)."""

    def __init__(self, url: str) -> None:
        self.dsn = url.replace("postgresql+asyncpg://", "postgresql://")

    def _run(self, coro_fn: Callable[[Any], Any]) -> Any:
        import asyncpg  # backend dependency

        async def main() -> Any:
            conn = await asyncpg.connect(self.dsn)
            try:
                return await coro_fn(conn)
            finally:
                await conn.close()

        return asyncio.run(main())

    def fetch(self, sql: str, *args: Any) -> list[dict[str, Any]]:
        async def go(conn: Any) -> list[dict[str, Any]]:
            return [dict(r) for r in await conn.fetch(sql, *args)]

        result: list[dict[str, Any]] = self._run(go)
        return result

    def execute(self, sql: str, *args: Any) -> None:
        async def go(conn: Any) -> None:
            await conn.execute(sql, *args)

        self._run(go)


def iso_in(seconds: float) -> datetime:
    return datetime.now(UTC) + timedelta(seconds=seconds)
