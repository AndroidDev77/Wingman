"""The worker loop: each job on its own interval, in one asyncio process.

* Every job runs in its own task, so a slow or failing job never delays another.
* A tick that raises is logged by error class only (never the message: a database
  error's text can carry bound parameters, i.e. message text) and retried after a
  bounded exponential backoff: ``min(backoff_max_s, interval * 2**failures)``.
* :meth:`Worker.request_stop` (wired to SIGTERM/SIGINT by ``python -m app.worker``) lets
  every task finish its current tick and then exit. A tick is never cancelled, so no
  transaction is cut mid-send; the dispatch job checks for a stop between messages,
  which bounds the wait to one transport call.
* With ``advisory_locks`` each tick first takes a per-job Postgres advisory lock (see
  :mod:`app.worker.locks`); a replica that does not get it skips the tick.
* One debug line per tick (job, outcome, counts, duration) is the heartbeat.
"""

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker

from app.db.base import utc_now
from app.services.automation import AutomationController
from app.services.dispatcher import ConnectorRegistry, Dispatcher
from app.worker.config import WorkerSettings
from app.worker.jobs import dispatch_due_messages, expire_due_approvals, expire_leases
from app.worker.locks import advisory_key, advisory_lock

__all__ = ["Job", "JobState", "TickResult", "Worker", "build_jobs"]

logger = logging.getLogger(__name__)

DISPATCH = "dispatch_due"
LEASE_EXPIRY = "expire_leases"
APPROVAL_EXPIRY = "expire_approvals"


@dataclass(frozen=True)
class TickResult:
    """What a job reports for one tick: counts only, never content."""

    counts: dict[str, int] = field(default_factory=dict)
    failed: bool = False  # e.g. every message in the batch raised: back off


TickFn = Callable[[Callable[[], bool]], Awaitable[TickResult]]


@dataclass(frozen=True)
class Job:
    name: str
    interval_s: float
    tick: TickFn  # receives ``should_stop`` so long ticks can end early between items


@dataclass
class JobState:
    ticks: int = 0
    skipped: int = 0  # lock held by another replica
    errors: int = 0
    consecutive_failures: int = 0
    last_progress: float = field(default_factory=time.monotonic)
    last_error_class: str | None = None


class Worker:
    def __init__(
        self,
        jobs: list[Job],
        *,
        engine: AsyncEngine | None,
        advisory_locks: bool = True,
        backoff_max_s: float = 60.0,
        sleep: Callable[[float], Awaitable[Any]] | None = None,
    ) -> None:
        if advisory_locks and engine is None:
            raise ValueError("advisory locks need an engine")
        if len({j.name for j in jobs}) != len(jobs):
            raise ValueError("job names must be unique")
        self.jobs = jobs
        self.engine = engine
        self.advisory_locks = advisory_locks
        self.backoff_max_s = backoff_max_s
        self.state: dict[str, JobState] = {j.name: JobState() for j in jobs}
        self._stop = asyncio.Event()
        self._sleep = sleep

    # ------------------------------------------------------------------ control

    def request_stop(self) -> None:
        """Finish the current ticks, then return from :meth:`run`. Idempotent."""
        if not self._stop.is_set():
            logger.info("worker stop requested; finishing current ticks")
        self._stop.set()

    @property
    def stopping(self) -> bool:
        return self._stop.is_set()

    # ------------------------------------------------------------------ ticks

    async def run_tick(self, job: Job) -> TickResult | None:
        """One tick of ``job``. Never raises; ``None`` means skipped or failed."""
        state = self.state[job.name]
        started = time.monotonic()
        try:
            if self.advisory_locks:
                assert self.engine is not None  # noqa: S101 - checked in __init__
                async with advisory_lock(self.engine, advisory_key(job.name)) as held:
                    if not held:
                        state.skipped += 1
                        state.consecutive_failures = 0
                        state.last_progress = time.monotonic()
                        logger.debug("worker tick job=%s skipped=lock_held", job.name)
                        return None
                    result = await job.tick(lambda: self.stopping)
            else:
                result = await job.tick(lambda: self.stopping)
        except Exception as exc:
            state.errors += 1
            state.consecutive_failures += 1
            state.last_error_class = type(exc).__name__
            logger.error(  # noqa: TRY400 - no traceback on purpose (redaction)
                "worker tick failed job=%s error_class=%s consecutive=%d",
                job.name,
                type(exc).__name__,
                state.consecutive_failures,
            )
            return None
        state.ticks += 1
        if result.failed:
            state.consecutive_failures += 1
        else:
            state.consecutive_failures = 0
            state.last_progress = time.monotonic()
        logger.debug(
            "worker tick job=%s ok=%s duration_ms=%d %s",
            job.name,
            not result.failed,
            int((time.monotonic() - started) * 1000),
            " ".join(f"{k}={v}" for k, v in sorted(result.counts.items())),
        )
        return result

    def delay_after(self, job: Job) -> float:
        failures = self.state[job.name].consecutive_failures
        if failures == 0:
            return job.interval_s
        return float(min(self.backoff_max_s, job.interval_s * (2 ** min(failures, 16))))

    async def run_once(self) -> dict[str, TickResult | None]:
        """One tick of every job, concurrently (tests, one-shot runs)."""
        results = await asyncio.gather(*(self.run_tick(j) for j in self.jobs))
        return {j.name: r for j, r in zip(self.jobs, results, strict=True)}

    # ------------------------------------------------------------------ loop

    async def _wait(self, seconds: float) -> None:
        if self._sleep is not None:
            await self._sleep(seconds)
            return
        try:
            await asyncio.wait_for(self._stop.wait(), timeout=seconds)
        except TimeoutError:
            pass

    async def _loop(self, job: Job) -> None:
        while not self.stopping:
            await self.run_tick(job)
            if self.stopping:
                break
            await self._wait(self.delay_after(job))

    async def run(self) -> None:
        """Run every job until :meth:`request_stop`; returns after the last tick ends."""
        logger.info(
            "worker started jobs=%s advisory_locks=%s",
            ",".join(f"{j.name}@{j.interval_s:g}s" for j in self.jobs),
            self.advisory_locks,
        )
        tasks = [asyncio.create_task(self._loop(j), name=f"worker:{j.name}") for j in self.jobs]
        try:
            await asyncio.gather(*tasks)
        finally:
            for task in tasks:
                task.cancel()
        logger.info("worker stopped")

    # ------------------------------------------------------------------ health

    def stale_jobs(self, stale_after_s: float) -> list[str]:
        now = time.monotonic()
        return [n for n, s in self.state.items() if now - s.last_progress > stale_after_s]


def build_jobs(
    settings: WorkerSettings,
    session_factory: async_sessionmaker[AsyncSession],
    *,
    dispatcher: Dispatcher | None = None,
    controller: AutomationController | None = None,
    now: Callable[[], datetime] = utc_now,
) -> list[Job]:
    """The three WING-011 jobs with the configured intervals.

    ``dispatcher`` defaults to one whose registry auto-creates mock connectors only when
    ``dispatch_mock`` is on (I-016: a fresh in-process mock has none of the API's state).
    """
    if dispatcher is None:
        dispatcher = Dispatcher(ConnectorRegistry(auto_mock=settings.dispatch_mock), now=now)
    if controller is None:
        controller = AutomationController(dispatcher, now=now)

    async def dispatch(should_stop: Callable[[], bool]) -> TickResult:
        tick = await dispatch_due_messages(
            session_factory,
            dispatcher,
            now=now,
            limit=settings.dispatch_batch,
            include_mock=settings.dispatch_mock,
            should_stop=should_stop,
        )
        counts = {"due": tick.attempted, "errors": tick.errors, **tick.outcomes}
        return TickResult(counts, failed=tick.all_failed)

    async def leases(_: Callable[[], bool]) -> TickResult:
        return TickResult({"expired": await expire_leases(session_factory, now=now)})

    async def approvals(_: Callable[[], bool]) -> TickResult:
        count = await expire_due_approvals(session_factory, controller, now=now)
        return TickResult({"expired": count})

    return [
        Job(DISPATCH, settings.dispatch_interval_s, dispatch),
        Job(LEASE_EXPIRY, settings.lease_expiry_interval_s, leases),
        Job(APPROVAL_EXPIRY, settings.approval_expiry_interval_s, approvals),
    ]
