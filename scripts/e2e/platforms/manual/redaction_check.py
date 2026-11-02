#!/usr/bin/env python3
"""WING-032 manual runs: refuse an evidence artifact that still contains private data.

    python3 scripts/e2e/platforms/manual/redaction_check.py FILE... [--forbid WORD ...]
    python3 scripts/e2e/platforms/manual/redaction_check.py --self-test

Run it on every file before it is attached to the evidence log (logcat excerpts, companion
Console exports, backend evidence JSON, screenshot OCR text). It fails (exit 1) on:

* phone numbers (``+`` and 8 or more digits, or 10+ digit runs), e-mail addresses;
* bearer tokens / JWTs (``eyJ...``), ``Authorization:`` headers, long hex or base64 secrets;
* iMessage chat GUIDs (``iMessage;-;`` / ``SMS;-;`` / ``any;-;``) and Sendbird channel URLs;
* every ``--forbid`` word (case-insensitive): the controlled test-message words, the test
  match's name, the tester's own name. Pass the words of the controlled input you sent.

Matches are printed masked (first two characters only). Stdlib only; exit 0 = clean,
1 = private data found, 2 = usage error.
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

PATTERNS = {
    "phone number": re.compile(r"\+\d[\d\s().-]{7,}\d|(?<![\w-])\d{10,}(?![\w-])"),
    "e-mail address": re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b"),
    "JWT / bearer token": re.compile(r"\beyJ[\w-]{10,}\.[\w-]{10,}|\bBearer\s+[\w.-]{16,}", re.IGNORECASE),
    "authorization header": re.compile(r"authorization\s*[:=]", re.IGNORECASE),
    "secret-like string": re.compile(r"\b[0-9a-f]{40,}\b|\b[A-Za-z0-9+/]{48,}={0,2}"),
    "chat GUID": re.compile(r"\b(?:iMessage|SMS|RCS|any);[-+];", re.IGNORECASE),
    "Sendbird channel": re.compile(r"sendbird_group_channel_\w+", re.IGNORECASE),
}


def scan(text: str, forbid: list[str]) -> list[tuple[int, str, str]]:
    hits: list[tuple[int, str, str]] = []
    words = [w for w in forbid if w.strip()]
    for n, line in enumerate(text.splitlines(), 1):
        for name, pat in PATTERNS.items():
            for m in pat.finditer(line):
                hits.append((n, name, m.group(0)))
        lower = line.lower()
        for w in words:
            if w.lower() in lower:
                hits.append((n, "forbidden word", w))
    return hits


def mask(value: str) -> str:
    return value[:2] + "*" * max(0, min(len(value) - 2, 12))


def self_test() -> int:
    dirty = "to +1 555 010 0001 ok\nuser bob@example.com\nAuthorization: Bearer abcdefghijklmnopqrstuvwxyz\n"
    dirty += "chat iMessage;-;+15550100001\nhey riley coffee?\n"
    clean = "event=message.received status=202 kind=chat_message count=3 code=timeout\n"
    clean += "instruction_id=0192f3a0-0000-7000-8000-000000000401 attempt=1\n"
    found = {h[1] for h in scan(dirty, ["Riley"])}
    expected = {"phone number", "e-mail address", "authorization header", "chat GUID", "forbidden word"}
    if not expected <= found:
        print(f"self-test FAILED: missed {sorted(expected - found)}", file=sys.stderr)
        return 1
    if scan(clean, ["Riley"]):
        print(f"self-test FAILED: false positive {scan(clean, ['Riley'])}", file=sys.stderr)
        return 1
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "log.txt"
        p.write_text(clean)
        if main_files([p], ["Riley"]) != 0:
            return 1
    print("redaction_check self-test OK")
    return 0


def main_files(files: list[Path], forbid: list[str]) -> int:
    dirty = 0
    for path in files:
        try:
            text = path.read_text(errors="replace")
        except OSError as exc:
            print(f"{path}: cannot read: {exc}", file=sys.stderr)
            return 2
        for line, name, value in scan(text, forbid):
            dirty += 1
            print(f"{path}:{line}: {name}: {mask(value)}")
    if dirty:
        print(f"NOT REDACTED: {dirty} finding(s). Remove them before attaching the file.", file=sys.stderr)
        return 1
    print(f"redaction check OK ({len(files)} file(s))")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("files", nargs="*", type=Path)
    parser.add_argument("--forbid", action="append", default=[], help="a word that must not appear")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.files:
        parser.print_usage(sys.stderr)
        return 2
    return main_files(args.files, args.forbid)


if __name__ == "__main__":
    sys.exit(main())
