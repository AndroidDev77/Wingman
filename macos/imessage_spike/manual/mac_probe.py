#!/usr/bin/env python3
"""WING-020 manual probe. RUN ONLY ON A REAL MAC, BY ITS OWNER, AGAINST THEIR OWN ACCOUNT.

Never run by CI or by an agent. Steps and recording rules:
docs/integrations/imessage.md section "Manual real-Mac test".

  python3 macos/imessage_spike/manual/mac_probe.py env
  python3 macos/imessage_spike/manual/mac_probe.py read  [--limit 50]
  python3 macos/imessage_spike/manual/mac_probe.py watch [--seconds 120]
  python3 macos/imessage_spike/manual/mac_probe.py send  --chat-guid 'iMessage;-;+1...' --text 'WING020 probe 1'

Privacy: output never contains message text, addresses or full GUIDs, only counts,
column names, flags and hashed IDs. ``send`` asks for an explicit typed confirmation
and sends exactly once; it is the ONLY subcommand that changes anything, and it only
sends a message through Messages (it never writes chat.db).

Uses only the macOS system Python standard library plus this repo's pure-logic package.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import subprocess
import sys
import time
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from wingman_imessage.applescript import build_send_command  # noqa: E402
from wingman_imessage.chatdb import ChatDbError, ChatDbReader  # noqa: E402
from wingman_imessage.envelope import transport_of  # noqa: E402

CHAT_DB = Path.home() / "Library" / "Messages" / "chat.db"


def h(value: str | None) -> str:
    return "-" if not value else hashlib.sha256(value.encode()).hexdigest()[:10]


def emit(obj: dict) -> None:  # type: ignore[type-arg]
    print(json.dumps(obj, indent=2, sort_keys=True, default=str))


def cmd_env(_: argparse.Namespace) -> int:
    if platform.system() != "Darwin":
        print("Not macOS: this probe only means something on a Mac.", file=sys.stderr)
        return 2
    sw = subprocess.run(["/usr/bin/sw_vers"], capture_output=True, text=True, check=False).stdout
    files = {n: (CHAT_DB.parent / n).exists() for n in ("chat.db", "chat.db-wal", "chat.db-shm")}
    result: dict[str, object] = {"sw_vers": sw.strip().splitlines(), "machine": platform.machine(), "files": files}
    try:
        with ChatDbReader(CHAT_DB) as r:
            assert r._conn is not None
            tables = [x[0] for x in r._conn.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
            result["full_disk_access"] = "readable (FDA granted to this terminal/app)"
            result["tables"] = tables
            result["message_columns"] = sorted(r._message_cols)
            result["chat_columns"] = sorted(r._chat_cols)
            result["max_rowid"] = r.max_rowid()
    except ChatDbError as exc:
        result["full_disk_access"] = f"NOT readable: {exc}"
    emit(result)
    return 0


def cmd_read(args: argparse.Namespace) -> int:
    with ChatDbReader(CHAT_DB) as r:
        top = r.max_rowid()
        rows = r.read_after(max(0, top - args.limit), args.limit)
    stats = Counter()
    for row in rows:
        stats[f"text_source={row.text_source}"] += 1
        stats[f"transport={transport_of(row.chat_guid, row.service)}"] += 1
        stats[f"group={row.is_group}"] += 1
        stats[f"style={row.chat_style}|group={row.is_group}"] += 1
        stats[f"from_me={row.is_from_me}"] += 1
        stats[f"reaction={row.is_reaction}"] += 1
        stats[f"edited={row.edited}"] += 1
        stats[f"attachments>0={row.attachment_count > 0}"] += 1
        stats[f"no_chat_join={row.chat_guid is None}"] += 1
    emit({"rows": len(rows), "stats": dict(sorted(stats.items()))})
    return 0


def cmd_watch(args: argparse.Namespace) -> int:
    with ChatDbReader(CHAT_DB) as r:
        cursor = r.max_rowid()
        print(f"watching from ROWID {cursor} for {args.seconds}s; send yourself a message from another device")
        end = time.monotonic() + args.seconds
        while time.monotonic() < end:
            for row in r.read_after(cursor, 200):
                cursor = row.rowid
                lag = (datetime.now(UTC) - row.occurred_at).total_seconds() if row.occurred_at else None
                emit(
                    {
                        "rowid": row.rowid,
                        "guid#": h(row.guid),
                        "chat#": h(row.chat_guid),
                        "transport": transport_of(row.chat_guid, row.service),
                        "group": row.is_group,
                        "from_me": row.is_from_me,
                        "text_source": row.text_source,
                        "text_len": len(row.text or ""),
                        "reaction": row.is_reaction,
                        "observed_lag_s": lag,
                    }
                )
            time.sleep(1.0)
    return 0


def cmd_send(args: argparse.Namespace) -> int:
    cmd = build_send_command(args.chat_guid, args.text)
    print(f"About to run ONE {cmd.describe()} into chat {h(cmd.chat_guid)}.")
    if input("Type SEND to continue: ").strip() != "SEND":
        print("aborted")
        return 1
    with ChatDbReader(CHAT_DB) as r:
        mark = r.max_rowid()
        started = time.monotonic()
        proc = subprocess.run(list(cmd.argv), capture_output=True, text=True, check=False, timeout=60)
        script_s = time.monotonic() - started
        found = None
        while time.monotonic() - started < 60 and found is None:
            for row in r.read_after(mark, 200):
                if row.is_from_me and row.chat_guid == cmd.chat_guid and (row.text or "") == args.text:
                    found = row
            time.sleep(0.5)
    emit(
        {
            "osascript_exit": proc.returncode,
            "osascript_stderr": proc.stderr.strip()[:300],  # error numbers such as -1743 appear here
            "script_seconds": round(script_s, 2),
            "sent_row_found": found is not None,
            "sent_row": None
            if found is None
            else {
                "rowid": found.rowid,
                "guid#": h(found.guid),
                "error": found.error,
                "is_sent": found.is_sent,
                "is_delivered": found.is_delivered,
                "service": found.service,
            },
            "row_seconds": round(time.monotonic() - started, 2),
        }
    )
    print("Now record on the PEER device whether the text arrived, once, and when.")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("env").set_defaults(fn=cmd_env)
    rp = sub.add_parser("read")
    rp.add_argument("--limit", type=int, default=50)
    rp.set_defaults(fn=cmd_read)
    wp = sub.add_parser("watch")
    wp.add_argument("--seconds", type=int, default=120)
    wp.set_defaults(fn=cmd_watch)
    sp = sub.add_parser("send")
    sp.add_argument("--chat-guid", required=True)
    sp.add_argument("--text", required=True)
    sp.set_defaults(fn=cmd_send)
    args = p.parse_args()
    return int(args.fn(args))


if __name__ == "__main__":
    raise SystemExit(main())
