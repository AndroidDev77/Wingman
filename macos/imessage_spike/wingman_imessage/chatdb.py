"""Read-only, incremental reader over a ``chat.db``-shaped SQLite file.

Apple does not document ``~/Library/Messages/chat.db``. The tables and columns used
here are the ones every community reference agrees on (see the sources list in
docs/integrations/imessage.md): ``message``, ``chat``, ``handle``,
``chat_message_join``, ``chat_handle_join``, ``message_attachment_join``.

Rules this reader follows (and WING-030 must keep):

* **Never write.** The file is opened with ``mode=ro`` through a URI. No PRAGMA that
  writes, no checkpoint, no ``immutable=1`` (that would hide rows still in the WAL,
  because SQLite then ignores the ``-wal`` file). Reading a WAL database read-only
  works when the ``-wal``/``-shm`` files exist, which they do while Messages runs
  (sqlite.org/wal.html section 5). UNVERIFIED on a real Mac.
* **Short read transactions.** One ``SELECT`` per poll, so the reader never holds a
  snapshot that would starve Messages' checkpoints (sqlite.org/wal.html section 6).
* **Schema drift tolerant.** Optional columns are discovered with ``PRAGMA
  table_info`` and read as NULL when a macOS version lacks them.
* **ROWID cursor.** New rows get increasing ROWIDs, so ``ROWID > cursor`` finds new
  messages. Edits, unsends and delivery flags UPDATE existing rows and are therefore
  NOT seen by the cursor (documented as a limit, see the capability matrix).
"""

from __future__ import annotations

import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from .timeconv import apple_to_datetime
from .typedstream import decode_attributed_body

# Columns that are not present on every macOS version (or every fixture).
_OPTIONAL_MESSAGE_COLUMNS = (
    "attributedBody",
    "associated_message_type",
    "associated_message_guid",
    "date_edited",
    "date_retracted",
    "item_type",
    "cache_has_attachments",
    "is_sent",
    "is_delivered",
    "error",
    "service",
    "thread_originator_guid",
)
_OPTIONAL_CHAT_COLUMNS = ("style", "display_name", "service_name", "room_name")

# chat.style: sources disagree on which of 43/45 is the group value. The reader
# therefore never relies on style alone (see is_group_chat).
STYLE_GROUP_COMMUNITY = 43


class ChatDbError(RuntimeError):
    """chat.db could not be opened or read (missing, no Full Disk Access, corrupt)."""


@dataclass(frozen=True, slots=True)
class MessageRow:
    rowid: int
    guid: str
    text: str | None
    text_source: str  # "text" | "attributedBody" | "none"
    occurred_at: datetime | None
    is_from_me: bool
    sender_handle: str | None
    service: str | None
    chat_guid: str | None
    chat_identifier: str | None
    chat_display_name: str | None
    chat_style: int | None
    participant_count: int
    attachment_count: int
    associated_message_type: int
    associated_message_guid: str | None
    item_type: int
    edited: bool
    retracted: bool
    is_sent: bool | None
    is_delivered: bool | None
    error: int

    @property
    def is_group(self) -> bool:
        return is_group_chat(self.chat_guid, self.chat_identifier, self.participant_count)

    @property
    def is_reaction(self) -> bool:
        # Tapbacks: 2000..2006 add, 3000..3006 remove (older docs use -2000..-2005).
        t = self.associated_message_type
        return t != 0 and (2000 <= abs(t) <= 3999)

    @property
    def is_system_item(self) -> bool:
        # item_type != 0: group renames, member changes, etc.
        return self.item_type != 0


def is_group_chat(chat_guid: str | None, chat_identifier: str | None, participants: int) -> bool:
    """Group detection from structural signals, not from the disputed ``style`` value.

    * chat GUIDs are ``<service>;-;<address>`` for 1:1 and ``<service>;+;<id>`` for
      groups (community-documented),
    * group ``chat_identifier`` values look like ``chat123456789``,
    * a group has more than one row in ``chat_handle_join``.
    """
    if chat_guid and ";+;" in chat_guid:
        return True
    if participants > 1:
        return True
    return bool(chat_identifier and chat_identifier.startswith("chat") and "@" not in chat_identifier)


def open_readonly(path: Path) -> sqlite3.Connection:
    """Open chat.db read-only. Raises ChatDbError (e.g. no Full Disk Access -> EPERM)."""
    if not path.exists():
        # Without Full Disk Access, macOS reports the file as unreadable rather than
        # missing (UNVERIFIED which errno is surfaced). Both map to ChatDbError.
        raise ChatDbError(f"chat.db not found or not accessible: {path.name}")
    uri = f"{path.resolve().as_uri()}?mode=ro"
    try:
        conn = sqlite3.connect(uri, uri=True, timeout=2.0, isolation_level=None)
        conn.execute("SELECT 1 FROM message LIMIT 1")
    except sqlite3.DatabaseError as exc:  # includes "unable to open", "not a database"
        raise ChatDbError(f"cannot read chat.db: {exc.__class__.__name__}") from exc
    conn.row_factory = sqlite3.Row
    return conn


class ChatDbReader:
    def __init__(self, path: Path) -> None:
        self._path = path
        self._conn: sqlite3.Connection | None = None
        self._message_cols: frozenset[str] = frozenset()
        self._chat_cols: frozenset[str] = frozenset()

    # -- lifecycle -----------------------------------------------------------
    def open(self) -> None:
        if self._conn is None:
            self._conn = open_readonly(self._path)
            self._message_cols = self._columns("message")
            self._chat_cols = self._columns("chat")

    def close(self) -> None:
        if self._conn is not None:
            self._conn.close()
            self._conn = None

    def __enter__(self) -> ChatDbReader:
        self.open()
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    @contextmanager
    def _read(self) -> Iterator[sqlite3.Connection]:
        self.open()
        assert self._conn is not None
        # Autocommit connection: each statement is its own short read transaction.
        yield self._conn

    def _columns(self, table: str) -> frozenset[str]:
        assert self._conn is not None
        return frozenset(r[1] for r in self._conn.execute(f"PRAGMA table_info({table})"))

    # -- queries ---------------------------------------------------------------
    def max_rowid(self) -> int:
        with self._read() as conn:
            row = conn.execute("SELECT COALESCE(MAX(ROWID), 0) FROM message").fetchone()
            return int(row[0])

    def read_after(self, cursor: int, limit: int = 200) -> list[MessageRow]:
        """Messages with ROWID > cursor, ascending, at most ``limit``."""
        if limit < 1:
            raise ValueError("limit must be >= 1")
        with self._read() as conn:
            rows = conn.execute(self._select_sql() + " WHERE m.ROWID > ? ORDER BY m.ROWID LIMIT ?", (cursor, limit))
            return [self._to_row(r) for r in rows.fetchall()]

    def read_rowids(self, rowids: list[int]) -> list[MessageRow]:
        if not rowids:
            return []
        marks = ",".join("?" for _ in rowids)
        with self._read() as conn:
            rows = conn.execute(self._select_sql() + f" WHERE m.ROWID IN ({marks}) ORDER BY m.ROWID", rowids)
            return [self._to_row(r) for r in rows.fetchall()]

    def _select_sql(self) -> str:
        def m(col: str) -> str:
            return f"m.{col}" if col in self._message_cols else "NULL"

        def c(col: str) -> str:
            return f"c.{col}" if col in self._chat_cols else "NULL"

        return f"""
            SELECT m.ROWID AS rowid, m.guid AS guid, m.text AS text,
                   {m("attributedBody")} AS attributed_body, m.date AS date,
                   m.is_from_me AS is_from_me, {m("service")} AS service,
                   {m("associated_message_type")} AS associated_message_type,
                   {m("associated_message_guid")} AS associated_message_guid,
                   {m("date_edited")} AS date_edited, {m("date_retracted")} AS date_retracted,
                   {m("item_type")} AS item_type, {m("is_sent")} AS is_sent,
                   {m("is_delivered")} AS is_delivered, {m("error")} AS error,
                   h.id AS sender_handle,
                   c.guid AS chat_guid, c.chat_identifier AS chat_identifier,
                   {c("display_name")} AS chat_display_name, {c("style")} AS chat_style,
                   (SELECT COUNT(*) FROM chat_handle_join chj WHERE chj.chat_id = c.ROWID) AS participant_count,
                   (SELECT COUNT(*) FROM message_attachment_join maj WHERE maj.message_id = m.ROWID) AS attachment_count
            FROM message m
            LEFT JOIN handle h ON h.ROWID = m.handle_id
            LEFT JOIN chat_message_join cmj ON cmj.message_id = m.ROWID
            LEFT JOIN chat c ON c.ROWID = cmj.chat_id
        """

    @staticmethod
    def _to_row(r: sqlite3.Row) -> MessageRow:
        text = r["text"]
        source = "text"
        if text is None or text == "":
            decoded = decode_attributed_body(r["attributed_body"])
            if decoded is not None:
                text, source = decoded, "attributedBody"
            else:
                text, source = None, "none"
        return MessageRow(
            rowid=int(r["rowid"]),
            guid=str(r["guid"]),
            text=text,
            text_source=source,
            occurred_at=apple_to_datetime(r["date"]),
            is_from_me=bool(r["is_from_me"]),
            sender_handle=r["sender_handle"],
            service=r["service"],
            chat_guid=r["chat_guid"],
            chat_identifier=r["chat_identifier"],
            chat_display_name=r["chat_display_name"],
            chat_style=r["chat_style"],
            participant_count=int(r["participant_count"] or 0),
            attachment_count=int(r["attachment_count"] or 0),
            associated_message_type=int(r["associated_message_type"] or 0),
            associated_message_guid=r["associated_message_guid"],
            item_type=int(r["item_type"] or 0),
            edited=bool(r["date_edited"]),
            retracted=bool(r["date_retracted"]),
            is_sent=None if r["is_sent"] is None else bool(r["is_sent"]),
            is_delivered=None if r["is_delivered"] is None else bool(r["is_delivered"]),
            error=int(r["error"] or 0),
        )
