from __future__ import annotations

from datetime import timedelta
from pathlib import Path

import pytest
from conftest import CONNECTION_ID, USER_ID, Clock
from fixture_db import ALICE, BOB, CARA, FixtureDb, at

from wingman_imessage.chatdb import ChatDbReader
from wingman_imessage.connector import InboundPoller, drain_outbox
from wingman_imessage.envelope import Scope
from wingman_imessage.state import StateStore

GROUP = "iMessage;+;chat123456789012345"
DM = f"iMessage;-;{ALICE}"


def poller(reader: ChatDbReader, store: StateStore, scope: Scope, clock: Clock, batch: int = 200) -> InboundPoller:
    return InboundPoller(reader, store, scope, batch_size=batch, clock=clock)


def test_first_poll_baselines_and_emits_no_history(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    old = [fixture.message(DM, sender=ALICE, text=f"history {i}", at=at(-60 + i))[0] for i in range(3)]
    state, rep = poller(reader, store, scope, clock).poll()
    assert rep.baselined and rep.enqueued == 0
    assert state.cursor == old[-1] and state.outbox == {}
    # Nothing new: still nothing.
    _, rep2 = poller(reader, store, scope, clock).poll()
    assert rep2.read == 0 and rep2.enqueued == 0


def test_incremental_reads_by_cursor(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    p = poller(reader, store, scope, clock)
    p.poll()
    clock.now = at(5)
    fixture.message(DM, sender=ALICE, text="one", at=at(1))
    fixture.message(f"iMessage;-;{BOB}", sender=BOB, text="two", at=at(2))
    state, rep = p.poll()
    assert rep.enqueued == 2
    texts = [e["payload"]["text"] for e in state.outbox.values()]
    assert texts == ["one", "two"]
    rid, _ = fixture.message(DM, sender=ALICE, text="three", at=at(3))
    state, rep = p.poll()
    assert rep.read == 1 and rep.enqueued == 1 and state.cursor == rid


def test_small_batches_cover_everything(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    p = poller(reader, store, scope, clock, batch=2)
    p.poll()
    clock.now = at(10)
    for i in range(7):
        fixture.message(DM, sender=ALICE, text=f"m{i}", at=at(1 + i / 10))
    state, rep = p.poll()
    assert rep.enqueued == 7
    assert [e["payload"]["text"] for e in state.outbox.values()] == [f"m{i}" for i in range(7)]


def test_restart_resumes_from_persisted_cursor(fixture, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    r1 = ChatDbReader(fixture.path)
    poller(r1, store, scope, clock).poll()
    clock.now = at(5)
    fixture.message(DM, sender=ALICE, text="before restart", at=at(1))
    poller(r1, store, scope, clock).poll()
    r1.close()
    # "Restart": new reader, new poller, state from disk only.
    fixture.message(DM, sender=ALICE, text="after restart", at=at(2))
    r2 = ChatDbReader(fixture.path)
    state, rep = poller(r2, store, scope, clock).poll()
    r2.close()
    assert rep.enqueued == 1
    assert [e["payload"]["text"] for e in state.outbox.values()] == ["before restart", "after restart"]


def test_crash_before_save_replays_identical_envelopes(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    p = poller(reader, store, scope, clock)
    p.poll()
    clock.now = at(5)
    fixture.message(DM, sender=ALICE, text="x", at=at(1))
    lost, rep_lost = p.poll(persist=False)  # simulated crash: nothing saved
    kept, rep = p.poll()
    assert rep_lost.enqueued == rep.enqueued == 1
    assert list(lost.outbox) == list(kept.outbox)  # same event_id -> backend dedups
    assert list(lost.outbox.values()) == list(kept.outbox.values())


def test_dedup_when_cursor_regresses(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    """chat.db restored from backup / rebuilt: old ROWIDs are read again but not re-emitted."""
    p = poller(reader, store, scope, clock)
    p.poll()
    clock.now = at(5)
    fixture.message(DM, sender=ALICE, text="a", at=at(1))
    fixture.message(DM, sender=ALICE, text="b", at=at(2))
    state, _ = p.poll()
    drain_outbox(store, lambda env: "accepted")
    state = store.load()
    state.cursor = 0
    store.save(state)
    state, rep = p.poll()
    assert rep.enqueued == 0 and rep.duplicates == 2 and state.outbox == {}


def test_dedup_same_guid_under_new_rowid(tmp_path: Path, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    """Same message GUID re-inserted under a new ROWID (e.g. iCloud resync) is one message."""
    db1 = FixtureDb(tmp_path / "a.db")
    db1.chat(DM, [ALICE], style=45)
    with ChatDbReader(db1.path) as r:
        poller(r, store, scope, clock).poll()
        clock.now = at(5)
        db1.message(DM, sender=ALICE, text="dup", at=at(1), guid="SAME-GUID")
        s1, _ = poller(r, store, scope, clock).poll()
    db1.close()
    db2 = FixtureDb(tmp_path / "b.db")
    db2.chat(DM, [ALICE], style=45)
    for i in range(3):
        db2.message(DM, sender=ALICE, text=f"pad{i}", at=at(-100))
    db2.message(DM, sender=ALICE, text="dup", at=at(1), guid="SAME-GUID")
    with ChatDbReader(db2.path) as r:
        s2, rep = poller(r, store, scope, clock).poll()
    db2.close()
    assert len(s2.outbox) == 1 and rep.duplicates == 1


def test_skips_own_reactions_system_retracted_and_pre_baseline(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    p = poller(reader, store, scope, clock)
    p.poll()
    clock.now = at(10)
    _, g = fixture.message(DM, sender=ALICE, text="keep", at=at(1))
    fixture.message(DM, sender=None, text="my own", at=at(2), from_me=True)
    fixture.message(DM, sender=ALICE, text="Liked", at=at(3), associated_type=2001, associated_guid=f"p:0/{g}")
    fixture.message(GROUP, sender=CARA, text=None, at=at(4), item_type=1)
    fixture.message(DM, sender=ALICE, text="late sync of old msg", at=at(-30))
    fixture.message(DM, sender=ALICE, text="￼", at=at(5))  # placeholder only, no attachment rows
    state, rep = p.poll()
    assert [e["payload"]["text"] for e in state.outbox.values()] == ["keep"]
    assert rep.skipped == {
        "from_me": 1,
        "reaction": 1,
        "system_item": 1,
        "before_baseline": 1,
        "no_content": 1,
    }


def test_attachment_only_and_group_messages(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    p = poller(reader, store, scope, clock)
    p.poll()
    clock.now = at(10)
    fixture.message(DM, sender=ALICE, text="￼", at=at(1), attachments=1)
    fixture.message(GROUP, sender=CARA, text="group hello", at=at(2))
    state, _ = p.poll()
    att, grp = state.outbox.values()
    assert att["payload"] == {
        "sender": {"external_contact_id": f"imessage:{ALICE}", "display_name": None},
        "text": "",
        "attachment_count": 1,
    }
    assert grp["external_thread_id"] == f"imessage:chat:{GROUP}"
    assert grp["payload"]["sender"]["external_contact_id"] == f"imessage:{CARA}"


def test_null_text_message_is_emitted_from_attributed_body(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    p = poller(reader, store, scope, clock)
    p.poll()
    clock.now = at(10)
    fixture.message(DM, sender=ALICE, text="hidden in blob", at=at(1), attributed=True)
    state, _ = p.poll()
    (env,) = state.outbox.values()
    assert env["payload"]["text"] == "hidden in blob"


def test_young_row_without_chat_is_held_back_then_emitted(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    p = poller(reader, store, scope, clock)
    p.poll()
    clock.now = at(1) + timedelta(seconds=5)
    rid, _ = fixture.message(DM, sender=ALICE, text="join pending", at=at(1), join=False)
    fixture.message(DM, sender=ALICE, text="after it", at=at(1))
    state, rep = p.poll()
    assert rep.held_back and rep.enqueued == 0 and state.cursor == rid - 1
    fixture.join(DM, rid)
    state, rep = p.poll()
    assert [e["payload"]["text"] for e in state.outbox.values()] == ["join pending", "after it"]


def test_old_row_without_chat_is_skipped(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    p = poller(reader, store, scope, clock)
    p.poll()
    clock.now = at(10)
    fixture.message(DM, sender=ALICE, text="orphan", at=at(1), join=False)
    state, rep = p.poll()
    assert rep.skipped == {"no_chat": 1} and state.outbox == {}


def test_envelope_ids_are_stable_and_user_scoped(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    p = poller(reader, store, scope, clock)
    p.poll()
    clock.now = at(10)
    fixture.message(DM, sender=ALICE, text="x", at=at(1), guid="GUID-STABLE")
    state, _ = p.poll()
    (env,) = state.outbox.values()
    assert env["external_message_id"] == "imessage:GUID-STABLE"
    assert env["user_id"] == USER_ID and env["connection_id"] == CONNECTION_ID
    from wingman_imessage.envelope import event_id

    assert env["event_id"] == event_id(USER_ID, "message.received", "imessage:GUID-STABLE")
    assert env["event_id"] != event_id(
        "0192f3a0-0000-7000-8000-000000000002", "message.received", "imessage:GUID-STABLE"
    )


def test_state_file_is_private(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    poller(reader, store, scope, clock).poll()
    assert (store.path.stat().st_mode & 0o777) == 0o600


def test_drain_order_retry_and_dead_letter(fixture, reader, store, scope, clock) -> None:  # type: ignore[no-untyped-def]
    p = poller(reader, store, scope, clock)
    p.poll()
    clock.now = at(10)
    for t in ["a", "b", "c", "d"]:
        fixture.message(DM, sender=ALICE, text=t, at=at(1))
    p.poll()
    answers = iter(["accepted", "permanent", "retry"])
    rep = drain_outbox(store, lambda env: next(answers))
    assert (rep.uploaded, rep.dead_lettered, rep.stopped_on_retry) == (1, 1, True)
    assert [e["payload"]["text"] for e in store.load().outbox.values()] == ["c", "d"]
    rep = drain_outbox(store, lambda env: "duplicate")
    assert rep.duplicates == 2 and store.load().outbox == {}
    fixture.message(DM, sender=ALICE, text="e", at=at(2))
    p.poll()
    with pytest.raises(ValueError):
        drain_outbox(store, lambda env: "bogus")
    assert len(store.load().outbox) == 1  # an unknown answer never drops the envelope
