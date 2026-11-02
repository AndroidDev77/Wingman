from __future__ import annotations

from datetime import timedelta

import pytest
from conftest import CONNECTION_ID, Clock
from fixture_db import ALICE, FixtureDb, at

from wingman_imessage.applescript import SendCommand
from wingman_imessage.chatdb import ChatDbReader
from wingman_imessage.envelope import Scope
from wingman_imessage.sendplan import ExecResult, ImessageSender, local_deadline
from wingman_imessage.state import StateStore

DM = f"iMessage;-;{ALICE}"
SERVER_TIME = "2026-10-30T18:00:00Z"
LEASE = "2026-10-30T18:02:00Z"


def instruction(key: str = "send:0192f3a0-0000-7000-8000-00000000aa01:1", text: str = "Yes! 8pm?", **kw) -> dict:  # type: ignore[no-untyped-def,type-arg]
    base = {
        "id": "0192f3a0-0000-7000-8000-00000000bb01",
        "connection_id": CONNECTION_ID,
        "outbound_message_id": "0192f3a0-0000-7000-8000-00000000aa01",
        "idempotency_key": key,
        "attempt": 1,
        "state": "leased",
        "created_at": SERVER_TIME,
        "leased_at": SERVER_TIME,
        "lease_expires_at": LEASE,
        "conversation_id": "0192f3a0-0000-7000-8000-00000000cc01",
        "external_thread_id": f"imessage:chat:{DM}",
        "text": text,
        "authorization": {"origin": "user_manual", "approval_id": None},
    }
    base.update(kw)
    return base


class FakeMessages:
    """Records commands; optionally writes the sent row into the fixture like Messages would."""

    def __init__(
        self, db: FixtureDb, clock: Clock, *, write_row: bool = True, result: ExecResult | None = None, error: int = 0
    ) -> None:
        self.db, self.clock, self.write_row, self.error = db, clock, write_row, error
        self.result = result or ExecResult(0)
        self.calls: list[SendCommand] = []

    def __call__(self, cmd: SendCommand) -> ExecResult:
        self.calls.append(cmd)
        if self.result.exit_code == 0 and self.write_row:
            self.db.message(
                cmd.chat_guid, sender=None, text=cmd.argv[-1], at=self.clock(), from_me=True, error=self.error
            )
        return self.result


def make(fixture: FixtureDb, store: StateStore, scope: Scope, clock: Clock, fake: FakeMessages) -> ImessageSender:
    return ImessageSender(store, ChatDbReader(fixture.path), scope, fake, clock)


def test_send_then_ack_only_after_row_observed(fixture, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    fixture.message(DM, sender=ALICE, text="Free tonight?", at=at(-1))
    fake = FakeMessages(fixture, clock, write_row=False)
    s = make(fixture, store, scope, clock, fake)
    ins = instruction()
    out = s.execute(ins, server_time=SERVER_TIME, anchor=clock())
    assert out.status == "executed" and out.envelope is None  # script success is NOT an ack
    assert s.reconcile({ins["idempotency_key"]: ins["text"]}) == []  # row not there yet
    rid, guid = fixture.message(DM, sender=None, text="Yes! 8pm?", at=clock(), from_me=True)
    (ack,) = s.reconcile({ins["idempotency_key"]: ins["text"]})
    assert ack["type"] == "message.send_acknowledged"
    assert ack["external_message_id"] == f"imessage:{guid}"
    assert ack["payload"] == {
        "outbound_message_id": ins["outbound_message_id"],
        "idempotency_key": ins["idempotency_key"],
    }
    # Delivery receipt only when the row says delivered.
    assert s.reconcile({}) == []
    fixture.mark_delivered(rid)
    (receipt,) = s.reconcile({})
    assert receipt["type"] == "message.delivery_receipt"
    assert s.reconcile({}) == []


def test_one_transport_attempt_per_key_across_restart(fixture, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    fake = FakeMessages(fixture, clock)
    ins = instruction()
    assert (
        make(fixture, store, scope, clock, fake).execute(ins, server_time=SERVER_TIME, anchor=clock()).transport_called
    )
    for _ in range(3):  # replayed claim, duplicate instruction, restart
        out = make(fixture, store, scope, clock, fake).execute(ins, server_time=SERVER_TIME, anchor=clock())
        assert out.status == "replayed" and not out.transport_called
    assert len(fake.calls) == 1


def test_key_is_claimed_before_transport(fixture, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    seen = {}

    def crashing(cmd: SendCommand) -> ExecResult:
        seen["phase"] = store.load().ledger["send:0192f3a0-0000-7000-8000-00000000aa01:1"]["phase"]
        raise RuntimeError("process died mid-send")

    s = ImessageSender(store, ChatDbReader(fixture.path), scope, crashing, clock)
    with pytest.raises(RuntimeError):
        s.execute(instruction(), server_time=SERVER_TIME, anchor=clock())
    assert seen["phase"] == "claimed"
    fake = FakeMessages(fixture, clock)
    assert (
        make(fixture, store, scope, clock, fake).execute(instruction(), server_time=SERVER_TIME, anchor=clock()).status
        == "replayed"
    )
    assert fake.calls == []
    # Recovery: nothing observed within the window -> timeout, not retryable (may have gone out).
    clock.now = clock.now + timedelta(minutes=2)
    (env,) = make(fixture, store, scope, clock, fake).reconcile({})
    assert env["type"] == "message.send_failed"
    assert env["payload"]["failure"]["code"] == "timeout" and env["payload"]["failure"]["retryable"] is False


def test_crash_after_send_recovers_ack_without_text(fixture, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    def crash_after_messages_sent(cmd: SendCommand) -> ExecResult:
        fixture.message(cmd.chat_guid, sender=None, text=cmd.argv[-1], at=clock(), from_me=True)
        raise RuntimeError("died after Messages accepted it")

    s = ImessageSender(store, ChatDbReader(fixture.path), scope, crash_after_messages_sent, clock)
    with pytest.raises(RuntimeError):
        s.execute(instruction(), server_time=SERVER_TIME, anchor=clock())
    (ack,) = make(fixture, store, scope, clock, FakeMessages(fixture, clock)).reconcile({})
    assert ack["type"] == "message.send_acknowledged"


def test_identical_texts_match_distinct_rows(fixture, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    fake = FakeMessages(fixture, clock)
    s = make(fixture, store, scope, clock, fake)
    k1, k2 = "send:0192f3a0-0000-7000-8000-00000000aa01:1", "send:0192f3a0-0000-7000-8000-00000000aa02:1"
    s.execute(instruction(k1, text="ok"), server_time=SERVER_TIME, anchor=clock())
    s.execute(
        instruction(k2, text="ok", outbound_message_id="0192f3a0-0000-7000-8000-00000000aa02"),
        server_time=SERVER_TIME,
        anchor=clock(),
    )
    acks = s.reconcile({k1: "ok", k2: "ok"})
    assert len({a["external_message_id"] for a in acks}) == 2


@pytest.mark.parametrize(
    ("ae", "code", "retryable"),
    [
        (-1743, "permission_denied", False),
        (-1728, "rejected_by_platform", False),
        (-600, "not_connected", True),
        (-1712, "timeout", False),
        (None, "transport_error", False),
        (-9999, "transport_error", False),
    ],
)
def test_executor_failure_mapping(fixture, store, scope, clock, ae, code, retryable) -> None:  # type: ignore[no-untyped-def]
    fake = FakeMessages(fixture, clock, result=ExecResult(1, ae))
    out = make(fixture, store, scope, clock, fake).execute(instruction(), server_time=SERVER_TIME, anchor=clock())
    assert out.status == "failed" and out.envelope is not None
    f = out.envelope["payload"]["failure"]
    assert (f["code"], f["retryable"]) == (code, retryable)
    assert out.envelope["external_message_id"] is None


def test_messages_error_on_row_is_failure(fixture, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    fake = FakeMessages(fixture, clock, error=22)
    s = make(fixture, store, scope, clock, fake)
    ins = instruction()
    s.execute(ins, server_time=SERVER_TIME, anchor=clock())
    (env,) = s.reconcile({ins["idempotency_key"]: ins["text"]})
    assert env["type"] == "message.send_failed"
    assert env["payload"]["failure"]["code"] == "rejected_by_platform"


def test_lease_deadline_blocks_send(fixture, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    fake = FakeMessages(fixture, clock)
    anchor = clock()
    clock.now = anchor + timedelta(seconds=106)  # 120 s lease - 15 s margin = 105 s
    out = make(fixture, store, scope, clock, fake).execute(instruction(), server_time=SERVER_TIME, anchor=anchor)
    assert out.status == "expired" and fake.calls == []
    assert out.envelope is not None and out.envelope["payload"]["failure"] == {
        "code": "timeout",
        "retryable": True,
        "detail": "lease deadline passed before the send started",
    }


def test_deadline_uses_server_relative_time(clock) -> None:  # type: ignore[no-untyped-def]
    assert local_deadline(clock(), SERVER_TIME, LEASE) == clock() + timedelta(seconds=105)


@pytest.mark.parametrize(
    "override",
    [
        {"connection_id": "0192f3a0-0000-7000-8000-0000000000ff"},
        {"state": "expired"},
        {"external_thread_id": "sms:+15550100001"},
        {"external_thread_id": 'imessage:chat:iMessage;-;+1555" & quit'},
        {"text": "bad\x00text"},
    ],
)
def test_invalid_instruction_never_executes(fixture, store, scope, clock, override) -> None:  # type: ignore[no-untyped-def]
    fake = FakeMessages(fixture, clock)
    out = make(fixture, store, scope, clock, fake).execute(
        instruction(**override), server_time=SERVER_TIME, anchor=clock()
    )
    assert out.status == "rejected" and fake.calls == [] and store.load().ledger == {}
