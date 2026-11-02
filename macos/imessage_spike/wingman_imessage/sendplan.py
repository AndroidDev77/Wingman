"""Leased outbox instruction -> at most one AppleScript send -> honest outcome envelope.

Mirrors WING-016's ``SmsSender``/``SmsOutboxPoller`` rules (ADR 0006, D-006):

1. Validate the instruction (right connection, ``leased``, an ``imessage:chat:`` thread,
   a buildable command). An invalid one is never executed; it is left to expire.
2. Lease deadline: ``anchor + (lease_expires_at - server_time) - margin``. Past it, the
   key is used up without a transport call and ``send_failed``/``timeout``/
   ``retryable: true`` is reported (nothing was sent).
3. **Claim the key in the durable ledger BEFORE the transport is called.** Any later
   call with the same key, before or after a restart, is answered from the ledger.
4. Record ``MAX(ROWID)`` before the send, run the injected executor once.
5. Outcome. ``osascript`` exiting 0 means only that Messages accepted the Apple event
   ("script success"). It is NOT an acknowledgement. ``message.send_acknowledged`` is
   emitted only after the companion finds the sent row in chat.db: ``is_from_me = 1``,
   same chat, same text, ROWID above the pre-send mark, ``error = 0``. A found row with
   ``error != 0`` becomes ``send_failed``. No row within the correlation window is
   ``timeout``/``retryable: false`` (it may still go out). ``is_delivered = 1`` on the
   row later yields ``message.delivery_receipt`` (iMessage only; UNVERIFIED).

Executor exit mapping (UNVERIFIED on a Mac; error numbers from Apple's AppleScript
error tables and widely reported TCC behaviour):

| executor result | failure.code | retryable |
|---|---|---|
| AppleEvent -1743 (errAEEventNotPermitted: Automation consent denied) | permission_denied | false |
| -1728 (errAENoSuchObject: chat id not found) | rejected_by_platform | false |
| -600 / -609 (app not running / connection invalid) | not_connected | true |
| -1712 (errAETimeout) | timeout | false |
| other non-zero | transport_error | false |
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from .applescript import SendCommand, SendCommandError, build_send_command
from .chatdb import ChatDbReader, MessageRow
from .envelope import (
    Scope,
    chat_guid_from_thread_id,
    clean_text,
    delivery_receipt_envelope,
    send_acknowledged_envelope,
    send_failed_envelope,
)
from .state import StateStore, utcnow

DEADLINE_MARGIN = timedelta(seconds=15)
CORRELATION_WINDOW = timedelta(seconds=60)

APPLE_EVENT_ERRORS: dict[int, tuple[str, bool]] = {
    -1743: ("permission_denied", False),
    -1728: ("rejected_by_platform", False),
    -600: ("not_connected", True),
    -609: ("not_connected", True),
    -1712: ("timeout", False),
}


@dataclass(frozen=True, slots=True)
class ExecResult:
    exit_code: int
    apple_event_error: int | None = None


Executor = Callable[[SendCommand], ExecResult]


@dataclass
class SendOutcome:
    status: str  # "executed" | "replayed" | "rejected" | "expired" | "failed"
    transport_called: bool
    envelope: dict[str, Any] | None = None


def _parse_ts(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def local_deadline(anchor: datetime, server_time: str, lease_expires_at: str) -> datetime:
    return anchor + (_parse_ts(lease_expires_at) - _parse_ts(server_time)) - DEADLINE_MARGIN


class ImessageSender:
    def __init__(
        self,
        store: StateStore,
        reader: ChatDbReader,
        scope: Scope,
        executor: Executor,
        clock: Callable[[], datetime] = utcnow,
    ) -> None:
        self._store = store
        self._reader = reader
        self._scope = scope
        self._exec = executor
        self._clock = clock

    # -- step 1 to 4 -----------------------------------------------------------
    def execute(self, instruction: dict[str, Any], *, server_time: str, anchor: datetime) -> SendOutcome:
        key = instruction.get("idempotency_key", "")
        state = self._store.load()
        if key in state.ledger:
            return SendOutcome("replayed", transport_called=False)

        if instruction.get("connection_id") != self._scope.connection_id or instruction.get("state") != "leased":
            return SendOutcome("rejected", transport_called=False)
        chat_guid = chat_guid_from_thread_id(instruction.get("external_thread_id", ""))
        try:
            if chat_guid is None:
                raise SendCommandError("not an imessage thread")
            command = build_send_command(chat_guid, instruction.get("text", ""))
        except SendCommandError:
            return SendOutcome("rejected", transport_called=False)

        record: dict[str, Any] = {
            "outbound_message_id": instruction["outbound_message_id"],
            "connection_id": self._scope.connection_id,
            "chat_guid": chat_guid,
            "text_len": len(instruction["text"]),
            "claimed_at": self._clock().isoformat(),
        }
        now = self._clock()
        if now >= local_deadline(anchor, server_time, instruction["lease_expires_at"]):
            record.update(phase="failed", transport_called=False)
            env = send_failed_envelope(
                scope=self._scope,
                chat_guid=chat_guid,
                outbound_message_id=instruction["outbound_message_id"],
                idempotency_key=key,
                code="timeout",
                retryable=True,
                detail="lease deadline passed before the send started",
                occurred_at=now,
            )
            state.ledger[key] = record
            state.outbox[env["event_id"]] = env
            self._store.save(state)
            return SendOutcome("expired", transport_called=False, envelope=env)

        record.update(phase="claimed", transport_called=True, rowid_mark=self._reader.max_rowid())
        state.ledger[key] = record
        self._store.save(state)  # durable BEFORE the transport (D-006)

        result = self._exec(command)

        state = self._store.load()
        record = state.ledger[key]
        if result.exit_code == 0:
            record["phase"] = "script_ok"  # NOT acknowledged yet
            record["script_ok_at"] = self._clock().isoformat()
            self._store.save(state)
            return SendOutcome("executed", transport_called=True)
        code, retryable = APPLE_EVENT_ERRORS.get(result.apple_event_error or 0, ("transport_error", False))
        env = send_failed_envelope(
            scope=self._scope,
            chat_guid=chat_guid,
            outbound_message_id=record["outbound_message_id"],
            idempotency_key=key,
            code=code,
            retryable=retryable,
            detail=f"osascript exit {result.exit_code}, apple event error {result.apple_event_error}",
            occurred_at=self._clock(),
        )
        record["phase"] = "failed"
        state.outbox[env["event_id"]] = env
        self._store.save(state)
        return SendOutcome("failed", transport_called=True, envelope=env)

    # -- step 5 ----------------------------------------------------------------------
    def reconcile(self, texts: dict[str, str]) -> list[dict[str, Any]]:
        """Correlate pending sends with chat.db rows and emit outcome envelopes.

        ``texts`` maps idempotency key -> the authorized text (kept in memory from
        the claim; the ledger stores only its length). Also covers a crash after the
        ledger claim: a ``claimed`` record whose row shows up is acknowledged; one
        that never shows up within the window becomes ``timeout``/not retryable.
        """
        state = self._store.load()
        now = self._clock()
        emitted: list[dict[str, Any]] = []
        taken: set[int] = {int(r["sent_rowid"]) for r in state.ledger.values() if "sent_rowid" in r}
        for key, rec in state.ledger.items():
            phase = rec.get("phase")
            if phase in ("claimed", "script_ok"):
                row = self._find_sent_row(rec, texts.get(key), taken)
                if row is not None:
                    taken.add(row.rowid)
                if row is not None and row.error == 0:
                    env = send_acknowledged_envelope(
                        scope=self._scope,
                        sent_guid=row.guid,
                        chat_guid=rec["chat_guid"],
                        outbound_message_id=rec["outbound_message_id"],
                        idempotency_key=key,
                        occurred_at=row.occurred_at or now,
                    )
                    rec.update(phase="acknowledged", sent_guid=row.guid, sent_rowid=row.rowid)
                elif row is not None:
                    env = self._failed(rec, key, "rejected_by_platform", False, f"messages error {row.error}", now)
                    rec["phase"] = "failed"
                elif now - datetime.fromisoformat(rec["claimed_at"]) > CORRELATION_WINDOW:
                    env = self._failed(rec, key, "timeout", False, "sent row not observed; outcome unknown", now)
                    rec["phase"] = "failed"
                else:
                    continue
                state.outbox[env["event_id"]] = env
                emitted.append(env)
            elif phase == "acknowledged" and not rec.get("delivered"):
                rows = self._reader.read_rowids([rec["sent_rowid"]])
                if rows and rows[0].is_delivered:
                    env = delivery_receipt_envelope(
                        scope=self._scope,
                        sent_guid=rec["sent_guid"],
                        chat_guid=rec["chat_guid"],
                        outbound_message_id=rec["outbound_message_id"],
                        occurred_at=now,
                    )
                    rec["delivered"] = True
                    state.outbox[env["event_id"]] = env
                    emitted.append(env)
        self._store.save(state)
        return emitted

    def _failed(self, rec: dict, key: str, code: str, retryable: bool, detail: str, now: datetime) -> dict[str, Any]:
        return send_failed_envelope(
            scope=self._scope,
            chat_guid=rec["chat_guid"],
            outbound_message_id=rec["outbound_message_id"],
            idempotency_key=key,
            code=code,
            retryable=retryable,
            detail=detail,
            occurred_at=now,
        )

    def _find_sent_row(self, rec: dict[str, Any], text: str | None, taken: set[int]) -> MessageRow | None:
        """First own row in the same chat after the pre-send mark with the same text.

        Rows already matched to another key are excluded, so two identical replies in
        one chat are matched in order. Without the text (crash recovery), the length
        is the only check: weaker, and documented as such.
        """
        cursor = int(rec["rowid_mark"])

        def same_text(row: MessageRow) -> bool:
            if text is not None:
                return clean_text(row.text) == clean_text(text)
            return len(row.text or "") == rec["text_len"]

        while True:
            rows = self._reader.read_after(cursor, 200)
            if not rows:
                return None
            for row in rows:
                cursor = row.rowid
                if (
                    row.is_from_me
                    and row.chat_guid == rec["chat_guid"]
                    and row.rowid not in taken
                    and not row.is_reaction
                    and same_text(row)
                ):
                    return row
