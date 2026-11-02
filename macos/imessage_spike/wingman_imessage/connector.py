"""One poll round of the inbound path: read -> classify -> dedup -> enqueue -> cursor.

Startup policy: the first round on a fresh state only BASELINES (cursor = current
MAX(ROWID), ``baseline_at`` = now) and emits nothing. Wingman never uploads the user's
message history; it starts with messages that arrive after pairing.

Hold-back: Messages may write the ``message`` row before its ``chat_message_join`` row
(UNVERIFIED; the order inside Messages' transactions is not documented). A row whose
chat is still missing is therefore not skipped for good while it is young: the round
stops before it and retries next time. Old rows with no chat are skipped.

Ordering of durable effects: everything a round produces (envelopes, dedup marks,
cursor) is persisted in ONE atomic state write at the end. A crash before it re-reads
the same rows next time and yields identical envelopes (same ``event_id``), so the
upload stays idempotent and no message is lost.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from .chatdb import ChatDbReader
from .envelope import Scope, SkipReason, classify, received_envelope
from .state import CompanionState, StateStore, utcnow

HOLD_BACK_GRACE = timedelta(seconds=30)


@dataclass
class PollReport:
    baselined: bool = False
    read: int = 0
    enqueued: int = 0
    duplicates: int = 0
    held_back: bool = False
    skipped: dict[str, int] = field(default_factory=dict)
    cursor_before: int | None = None
    cursor_after: int | None = None


class InboundPoller:
    def __init__(
        self,
        reader: ChatDbReader,
        store: StateStore,
        scope: Scope,
        *,
        batch_size: int = 200,
        clock: Callable[[], datetime] = utcnow,
    ) -> None:
        self._reader = reader
        self._store = store
        self._scope = scope
        self._batch = batch_size
        self._clock = clock

    def poll(self, *, persist: bool = True) -> tuple[CompanionState, PollReport]:
        state = self._store.load()
        now = self._clock()
        report = PollReport(cursor_before=state.cursor)

        if state.cursor is None:
            state.cursor = self._reader.max_rowid()
            state.baseline_at = now.isoformat()
            report.baselined = True
            report.cursor_after = state.cursor
            if persist:
                self._store.save(state)
            return state, report

        baseline = datetime.fromisoformat(state.baseline_at) if state.baseline_at else None
        cursor = state.cursor
        while True:
            rows = self._reader.read_after(cursor, self._batch)
            if not rows:
                break
            stop = False
            for row in rows:
                report.read += 1
                reason = classify(row, baseline)
                if (
                    reason == SkipReason.NO_CHAT
                    and row.occurred_at is not None
                    and now - row.occurred_at < HOLD_BACK_GRACE
                ):
                    report.held_back = True
                    stop = True
                    break
                cursor = row.rowid
                if reason is not None:
                    report.skipped[reason] = report.skipped.get(reason, 0) + 1
                    continue
                env = received_envelope(row, self._scope, observed_at=now)
                key = env["external_message_id"]
                if key in state.seen or env["event_id"] in state.outbox:
                    report.duplicates += 1
                    continue
                state.outbox[env["event_id"]] = env
                state.seen[key] = now.isoformat()
                report.enqueued += 1
            if stop or len(rows) < self._batch:
                break

        state.cursor = cursor
        state.prune(now)
        state.bump("enqueued", report.enqueued)
        report.cursor_after = cursor
        if persist:
            self._store.save(state)
        return state, report


# ---------------------------------------------------------------- upload (uplink)
UploadAnswer = str  # "accepted" | "duplicate" | "permanent" | "retry"


@dataclass
class DrainReport:
    uploaded: int = 0
    duplicates: int = 0
    dead_lettered: int = 0
    stopped_on_retry: bool = False


def drain_outbox(store: StateStore, upload: Callable[[dict], UploadAnswer]) -> DrainReport:
    """Upload queued envelopes in order with the WING-016 response rules (ADR 0006).

    ``upload`` performs ``POST /v1/connections/{id}/events`` with
    ``Idempotency-Key = event_id`` and maps the answer: 202 -> accepted, 200 ->
    duplicate, 400/404/422 -> permanent, anything else -> retry. The drain stops at
    the first retry so nothing overtakes a waiting envelope. Each removal is saved
    before the next request.
    """
    report = DrainReport()
    state = store.load()
    for event_id in list(state.outbox):
        envelope = state.outbox[event_id]
        answer = upload(envelope)
        if answer == "retry":
            report.stopped_on_retry = True
            break
        if answer == "accepted":
            report.uploaded += 1
        elif answer == "duplicate":
            report.duplicates += 1
        elif answer == "permanent":
            report.dead_lettered += 1
            state.bump("dead_lettered")
        else:
            raise ValueError(f"unknown upload answer {answer!r}")
        del state.outbox[event_id]
        store.save(state)
    return report
