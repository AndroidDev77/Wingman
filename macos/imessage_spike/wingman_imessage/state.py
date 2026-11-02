"""Durable companion state: ROWID cursor, dedup set, envelope outbox, send ledger.

One JSON file, replaced atomically (write temp, fsync, ``os.replace``) with mode 0600.
Because cursor, dedup claims and queued envelopes are written in ONE replace, a crash
leaves either the old state (rows are re-read and produce byte-identical envelopes
with the same ``event_id``) or the new one. Neither loses nor duplicates a message.

The file holds message text while envelopes wait for upload, like WING-016's
``wingman-sms-state.json``. WING-030 must keep it under
``~/Library/Application Support/<bundle id>/`` (excluded from backups is optional:
Time Machine already copies chat.db itself). It must never hold the backend token:
that lives in the Keychain.
"""

from __future__ import annotations

import json
import os
import tempfile
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

STATE_VERSION = 1
DEDUP_RETENTION = timedelta(days=14)


@dataclass
class CompanionState:
    cursor: int | None = None  # None = never baselined
    baseline_at: str | None = None  # no message older than this is ever emitted
    seen: dict[str, str] = field(default_factory=dict)  # external_message_id -> first seen (ISO)
    outbox: dict[str, dict[str, Any]] = field(default_factory=dict)  # event_id -> envelope (insertion ordered)
    ledger: dict[str, dict[str, Any]] = field(default_factory=dict)  # idempotency_key -> send record
    device_install_id: str | None = None
    counters: dict[str, int] = field(default_factory=dict)

    def bump(self, name: str, by: int = 1) -> None:
        self.counters[name] = self.counters.get(name, 0) + by

    def prune(self, now: datetime) -> None:
        horizon = now - DEDUP_RETENTION
        self.seen = {k: v for k, v in self.seen.items() if datetime.fromisoformat(v) >= horizon}

    def to_json(self) -> dict[str, Any]:
        return {
            "version": STATE_VERSION,
            "cursor": self.cursor,
            "baseline_at": self.baseline_at,
            "seen": self.seen,
            "outbox": list(self.outbox.values()),
            "ledger": self.ledger,
            "device_install_id": self.device_install_id,
            "counters": self.counters,
        }

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> CompanionState:
        if data.get("version") != STATE_VERSION:
            raise ValueError("unsupported state version")
        outbox = {e["event_id"]: e for e in data.get("outbox", [])}
        return cls(
            cursor=data.get("cursor"),
            baseline_at=data.get("baseline_at"),
            seen=dict(data.get("seen", {})),
            outbox=outbox,
            ledger=dict(data.get("ledger", {})),
            device_install_id=data.get("device_install_id"),
            counters=dict(data.get("counters", {})),
        )


class StateStore:
    def __init__(self, path: Path) -> None:
        self.path = path

    def load(self) -> CompanionState:
        try:
            raw = self.path.read_text("utf-8")
        except FileNotFoundError:
            return CompanionState()
        return CompanionState.from_json(json.loads(raw))

    def save(self, state: CompanionState) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(prefix=".state-", dir=self.path.parent)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(state.to_json(), fh, ensure_ascii=False, sort_keys=True)
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(tmp, self.path)
        except BaseException:
            try:
                os.unlink(tmp)
            except FileNotFoundError:
                pass
            raise


def utcnow() -> datetime:
    return datetime.now(UTC)
