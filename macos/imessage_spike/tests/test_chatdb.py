from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest
from fixture_db import ALICE, BOB, CARA, DAN_SMS, FixtureDb, at

from wingman_imessage.chatdb import ChatDbError, ChatDbReader, is_group_chat
from wingman_imessage.envelope import transport_of

GROUP = "iMessage;+;chat123456789012345"


def test_reads_rows_with_joins(fixture: FixtureDb, reader: ChatDbReader) -> None:
    fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text="hi", at=at(1))
    fixture.message(f"iMessage;-;{BOB}", sender=BOB, text="hello", at=at(2))
    rows = reader.read_after(0)
    assert [r.text for r in rows] == ["hi", "hello"]
    assert rows[0].sender_handle == ALICE
    assert rows[0].chat_guid == f"iMessage;-;{ALICE}"
    assert rows[0].occurred_at == at(1)
    assert rows[0].text_source == "text"
    assert not rows[0].is_group


def test_cursor_is_exclusive_and_limited(fixture: FixtureDb, reader: ChatDbReader) -> None:
    ids = [fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text=f"m{i}", at=at(i))[0] for i in range(5)]
    assert [r.rowid for r in reader.read_after(ids[1], limit=2)] == ids[2:4]
    assert reader.read_after(ids[-1]) == []
    assert reader.max_rowid() == ids[-1]
    with pytest.raises(ValueError):
        reader.read_after(0, limit=0)


def test_null_text_falls_back_to_attributed_body(fixture: FixtureDb, reader: ChatDbReader) -> None:
    fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text="Ventura style body", at=at(1), attributed=True)
    (row,) = reader.read_after(0)
    assert row.text == "Ventura style body"
    assert row.text_source == "attributedBody"


def test_undecodable_attributed_body_gives_none(fixture: FixtureDb, reader: ChatDbReader) -> None:
    fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text=None, at=at(1), raw_attributed=b"garbage")
    (row,) = reader.read_after(0)
    assert row.text is None and row.text_source == "none"


def test_group_detection(fixture: FixtureDb, reader: ChatDbReader) -> None:
    fixture.message(GROUP, sender=CARA, text="group msg", at=at(1))
    fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text="dm", at=at(2))
    group, dm = reader.read_after(0)
    assert group.is_group and group.participant_count == 2
    assert group.chat_display_name == "Weekend plans"
    assert not dm.is_group and dm.participant_count == 1


@pytest.mark.parametrize(
    ("guid", "ident", "participants", "expected"),
    [
        ("iMessage;+;chat1", "chat1", 0, True),
        ("iMessage;-;+15550100001", "+15550100001", 1, False),
        ("any;-;x@example.com", "x@example.com", 1, False),
        ("SMS;-;+15550100004", "+15550100004", 3, True),  # MMS group shown with ;-; in some reports
        (None, "chat987", 0, True),
        (None, None, 0, False),
    ],
)
def test_is_group_chat_signals(guid: str | None, ident: str | None, participants: int, expected: bool) -> None:
    assert is_group_chat(guid, ident, participants) is expected


def test_reactions_system_items_attachments_and_transport(fixture: FixtureDb, reader: ChatDbReader) -> None:
    _, target = fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text="photo?", at=at(1))
    fixture.message(
        f"iMessage;-;{ALICE}",
        sender=ALICE,
        text="Loved “photo?”",
        at=at(2),
        associated_type=2000,
        associated_guid=f"p:0/{target}",
    )
    fixture.message(GROUP, sender=CARA, text=None, at=at(3), item_type=2)
    fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text="￼", at=at(4), attachments=2)
    fixture.message(f"SMS;-;{DAN_SMS}", sender=DAN_SMS, text="sms", at=at(5), service="SMS")
    plain, reaction, system, attach, sms = reader.read_after(0)
    assert not plain.is_reaction and reaction.is_reaction
    assert system.is_system_item
    assert attach.attachment_count == 2
    assert transport_of(sms.chat_guid, sms.service) == "sms"
    assert transport_of(plain.chat_guid, plain.service) == "imessage"
    assert transport_of("RCS;-;+15550100009", None) == "rcs"
    assert transport_of("any;-;+15550100009", None) == "unknown"


def test_missing_optional_columns_read_as_null(tmp_path: Path) -> None:
    """An older schema without attributedBody/date_edited/etc. still reads."""
    p = tmp_path / "old.db"
    c = sqlite3.connect(p)
    c.executescript(
        """
        CREATE TABLE handle (ROWID INTEGER PRIMARY KEY, id TEXT);
        CREATE TABLE chat (ROWID INTEGER PRIMARY KEY, guid TEXT, chat_identifier TEXT);
        CREATE TABLE message (ROWID INTEGER PRIMARY KEY, guid TEXT, text TEXT, handle_id INTEGER,
                              date INTEGER, is_from_me INTEGER);
        CREATE TABLE chat_message_join (chat_id INTEGER, message_id INTEGER);
        CREATE TABLE chat_handle_join (chat_id INTEGER, handle_id INTEGER);
        CREATE TABLE message_attachment_join (message_id INTEGER, attachment_id INTEGER);
        INSERT INTO handle VALUES (1, '+15550100001');
        INSERT INTO chat VALUES (1, 'iMessage;-;+15550100001', '+15550100001');
        INSERT INTO message VALUES (1, 'G1', 'old', 1, 500000000, 0);
        INSERT INTO chat_message_join VALUES (1, 1);
        """
    )
    c.commit()
    c.close()
    with ChatDbReader(p) as r:
        (row,) = r.read_after(0)
    assert row.text == "old" and row.associated_message_type == 0 and not row.edited
    assert row.occurred_at is not None and row.occurred_at.year == 2016  # seconds-based legacy date


def test_reader_never_writes(fixture: FixtureDb, reader: ChatDbReader) -> None:
    fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text="hi", at=at(1))
    reader.read_after(0)
    assert reader._conn is not None
    with pytest.raises(sqlite3.OperationalError, match="readonly"):
        reader._conn.execute("DELETE FROM message")


def test_sees_rows_still_in_wal(fixture: FixtureDb, reader: ChatDbReader) -> None:
    """The writer (Messages stand-in) keeps its WAL un-checkpointed; the reader still sees commits."""
    fixture.conn.execute("PRAGMA wal_autocheckpoint=0")
    assert reader.read_after(0) == []
    fixture.message(f"iMessage;-;{ALICE}", sender=ALICE, text="in wal", at=at(1))
    assert Path(str(fixture.path) + "-wal").stat().st_size > 0
    assert [r.text for r in reader.read_after(0)] == ["in wal"]


def test_missing_db_is_typed_error(tmp_path: Path) -> None:
    with pytest.raises(ChatDbError):
        ChatDbReader(tmp_path / "nope.db").open()


def test_not_a_database_is_typed_error(tmp_path: Path) -> None:
    p = tmp_path / "chat.db"
    p.write_bytes(b"this is not sqlite" * 100)
    with pytest.raises(ChatDbError):
        ChatDbReader(p).open()
