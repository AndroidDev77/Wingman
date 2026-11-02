"""Build a SYNTHETIC chat.db-shaped SQLite file from the community-documented schema.

Only the tables and columns the reader uses, plus a few realistic extras. All
addresses use reserved test ranges (+1 555 01xx, example.com). No real data.
"""

from __future__ import annotations

import sqlite3
from datetime import UTC, datetime, timedelta
from pathlib import Path

from wingman_imessage.timeconv import datetime_to_apple_ns
from wingman_imessage.typedstream import encode_attributed_body_for_fixture

SCHEMA = """
CREATE TABLE handle (
    ROWID INTEGER PRIMARY KEY AUTOINCREMENT UNIQUE, id TEXT NOT NULL, country TEXT,
    service TEXT NOT NULL, uncanonicalized_id TEXT, person_centric_id TEXT,
    UNIQUE (id, service)
);
CREATE TABLE chat (
    ROWID INTEGER PRIMARY KEY AUTOINCREMENT, guid TEXT UNIQUE NOT NULL, style INTEGER,
    state INTEGER, account_id TEXT, chat_identifier TEXT, service_name TEXT,
    room_name TEXT, display_name TEXT, is_archived INTEGER DEFAULT 0
);
CREATE TABLE message (
    ROWID INTEGER PRIMARY KEY AUTOINCREMENT, guid TEXT UNIQUE NOT NULL, text TEXT,
    handle_id INTEGER DEFAULT 0, service TEXT, date INTEGER, date_read INTEGER,
    date_delivered INTEGER, is_delivered INTEGER DEFAULT 0, is_from_me INTEGER DEFAULT 0,
    is_sent INTEGER DEFAULT 0, is_read INTEGER DEFAULT 0, error INTEGER DEFAULT 0,
    item_type INTEGER DEFAULT 0, cache_has_attachments INTEGER DEFAULT 0,
    cache_roomnames TEXT, associated_message_guid TEXT, associated_message_type INTEGER DEFAULT 0,
    attributedBody BLOB, date_edited INTEGER DEFAULT 0, date_retracted INTEGER DEFAULT 0,
    thread_originator_guid TEXT
);
CREATE TABLE chat_message_join (
    chat_id INTEGER REFERENCES chat (ROWID) ON DELETE CASCADE,
    message_id INTEGER REFERENCES message (ROWID) ON DELETE CASCADE,
    message_date INTEGER DEFAULT 0, PRIMARY KEY (chat_id, message_id)
);
CREATE TABLE chat_handle_join (
    chat_id INTEGER REFERENCES chat (ROWID) ON DELETE CASCADE,
    handle_id INTEGER REFERENCES handle (ROWID) ON DELETE CASCADE, UNIQUE (chat_id, handle_id)
);
CREATE TABLE attachment (
    ROWID INTEGER PRIMARY KEY AUTOINCREMENT, guid TEXT UNIQUE NOT NULL, filename TEXT,
    mime_type TEXT, transfer_state INTEGER DEFAULT 0, total_bytes INTEGER DEFAULT 0
);
CREATE TABLE message_attachment_join (
    message_id INTEGER REFERENCES message (ROWID) ON DELETE CASCADE,
    attachment_id INTEGER REFERENCES attachment (ROWID) ON DELETE CASCADE,
    UNIQUE (message_id, attachment_id)
);
"""

T0 = datetime(2026, 10, 30, 18, 0, 0, tzinfo=UTC)

ALICE = "+15550100001"
BOB = "bob@example.com"
CARA = "+15550100003"
DAN_SMS = "+15550100004"


class FixtureDb:
    """A writable fixture standing in for Messages. The code under test opens it read-only."""

    def __init__(self, path: Path, *, wal: bool = True) -> None:
        self.path = path
        self.conn = sqlite3.connect(path, isolation_level=None)
        if wal:
            self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.executescript(SCHEMA)
        self._n = 0
        self.handles: dict[str, int] = {}
        self.chats: dict[str, int] = {}

    def close(self) -> None:
        self.conn.close()

    def handle(self, address: str, service: str = "iMessage") -> int:
        if address not in self.handles:
            cur = self.conn.execute("INSERT INTO handle (id, service) VALUES (?, ?)", (address, service))
            self.handles[address] = int(cur.lastrowid)
        return self.handles[address]

    def chat(self, guid: str, members: list[str], *, style: int, display_name: str | None = None) -> int:
        service, _, ident = guid.split(";", 2)
        cur = self.conn.execute(
            "INSERT INTO chat (guid, style, chat_identifier, service_name, display_name) VALUES (?,?,?,?,?)",
            (guid, style, ident, service, display_name),
        )
        cid = int(cur.lastrowid)
        for m in members:
            self.conn.execute(
                "INSERT INTO chat_handle_join (chat_id, handle_id) VALUES (?, ?)", (cid, self.handle(m, service))
            )
        self.chats[guid] = cid
        return cid

    def message(
        self,
        chat_guid: str | None,
        *,
        sender: str | None,
        text: str | None,
        at: datetime,
        from_me: bool = False,
        attributed: bool = False,
        service: str = "iMessage",
        associated_type: int = 0,
        associated_guid: str | None = None,
        item_type: int = 0,
        attachments: int = 0,
        guid: str | None = None,
        join: bool = True,
        error: int = 0,
        delivered: bool = False,
        raw_attributed: bytes | None = None,
    ) -> tuple[int, str]:
        self._n += 1
        guid = guid or f"FIXTURE-{self._n:04d}-0000-0000-0000-000000000000"
        body = raw_attributed
        if attributed and text is not None:
            body = encode_attributed_body_for_fixture(text)
        stored_text = None if attributed else text
        handle_id = self.handle(sender, service) if sender else 0
        cur = self.conn.execute(
            """INSERT INTO message (guid, text, handle_id, service, date, is_from_me, is_sent,
                   associated_message_type, associated_message_guid, item_type, attributedBody,
                   cache_has_attachments, error, is_delivered)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                guid,
                stored_text,
                handle_id,
                service,
                datetime_to_apple_ns(at),
                int(from_me),
                int(from_me),
                associated_type,
                associated_guid,
                item_type,
                body,
                int(attachments > 0),
                error,
                int(delivered),
            ),
        )
        rowid = int(cur.lastrowid)
        if chat_guid and join:
            self.join(chat_guid, rowid)
        for i in range(attachments):
            a = self.conn.execute(
                "INSERT INTO attachment (guid, filename, mime_type) VALUES (?,?,?)",
                (f"ATT-{rowid}-{i}", f"~/Library/Messages/Attachments/x/{rowid}-{i}.jpg", "image/jpeg"),
            )
            self.conn.execute(
                "INSERT INTO message_attachment_join (message_id, attachment_id) VALUES (?,?)", (rowid, a.lastrowid)
            )
        return rowid, guid

    def join(self, chat_guid: str, rowid: int) -> None:
        self.conn.execute(
            "INSERT INTO chat_message_join (chat_id, message_id) VALUES (?, ?)", (self.chats[chat_guid], rowid)
        )

    def mark_delivered(self, rowid: int) -> None:
        self.conn.execute("UPDATE message SET is_delivered = 1 WHERE ROWID = ?", (rowid,))


def standard_fixture(path: Path) -> FixtureDb:
    """1:1 iMessage (phone), 1:1 iMessage (email), group iMessage, 1:1 SMS."""
    db = FixtureDb(path)
    db.chat(f"iMessage;-;{ALICE}", [ALICE], style=45)
    db.chat(f"iMessage;-;{BOB}", [BOB], style=45)
    db.chat("iMessage;+;chat123456789012345", [ALICE, CARA], style=43, display_name="Weekend plans")
    db.chat(f"SMS;-;{DAN_SMS}", [DAN_SMS], style=45)
    return db


def at(minutes: float) -> datetime:
    return T0 + timedelta(minutes=minutes)
