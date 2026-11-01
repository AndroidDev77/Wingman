"""The loop itself: isolation between jobs, bounded backoff, graceful stop, health and
redacted logging. Most cases need no database (advisory locks off)."""

import asyncio
import json
import logging
from typing import Any

import pytest
from sqlalchemy.exc import OperationalError

from app.connectors.mock import MockConnector
from app.worker.config import WorkerSettings
from app.worker.health import health_body, start_health_server
from app.worker.runner import Job, TickResult, Worker
from tests.worker.conftest import Factory, worker_for
from tests.worker.helpers import ingest, queue_manual, setup

SENTINEL = "SENTINEL-7f3a-do-not-log"


def counting_job(name: str, interval: float = 0.01) -> tuple[Job, list[int]]:
    calls: list[int] = []

    async def tick(_: Any) -> TickResult:
        calls.append(1)
        return TickResult({"n": len(calls)})

    return Job(name, interval, tick), calls


def failing_job(name: str, exc: Exception, interval: float = 0.01) -> tuple[Job, list[int]]:
    calls: list[int] = []

    async def tick(_: Any) -> TickResult:
        calls.append(1)
        raise exc

    return Job(name, interval, tick), calls


async def run_for(worker: Worker, seconds: float) -> None:
    task = asyncio.create_task(worker.run())
    await asyncio.sleep(seconds)
    worker.request_stop()
    await asyncio.wait_for(task, timeout=5)


async def test_a_failing_job_does_not_stop_the_others() -> None:
    bad, bad_calls = failing_job("bad", RuntimeError("boom"))
    good, good_calls = counting_job("good")
    worker = Worker([bad, good], engine=None, advisory_locks=False, backoff_max_s=0.05)
    await run_for(worker, 0.4)
    assert len(good_calls) >= 10
    assert len(bad_calls) >= 2  # retried after backoff, never fatal
    assert worker.state["bad"].errors == len(bad_calls)
    assert worker.state["bad"].last_error_class == "RuntimeError"
    assert worker.state["good"].errors == 0


async def test_db_errors_back_off_exponentially_and_bounded() -> None:
    err = OperationalError("SELECT 1", {}, Exception("connection refused"))
    bad, _ = failing_job("db", err, interval=2.0)
    worker = Worker([bad], engine=None, advisory_locks=False, backoff_max_s=30.0)
    delays = []
    for _ in range(6):
        await worker.run_tick(bad)
        delays.append(worker.delay_after(bad))
    assert delays == [4.0, 8.0, 16.0, 30.0, 30.0, 30.0]

    async def ok(_: Any) -> TickResult:
        return TickResult({})

    healed = Job("db", 2.0, ok)
    await worker.run_tick(healed)
    assert worker.delay_after(healed) == 2.0  # one success resets the backoff


async def test_a_tick_where_every_item_failed_backs_off() -> None:
    async def all_failed(_: Any) -> TickResult:
        return TickResult({"due": 3, "errors": 3}, failed=True)

    job = Job("d", 1.0, all_failed)
    worker = Worker([job], engine=None, advisory_locks=False, backoff_max_s=60)
    await worker.run_tick(job)
    assert worker.delay_after(job) == 2.0


async def test_stop_finishes_the_current_tick_and_never_cancels_it() -> None:
    started = asyncio.Event()
    release = asyncio.Event()
    finished: list[str] = []

    async def slow(_: Any) -> TickResult:
        started.set()
        await release.wait()
        await asyncio.sleep(0.05)
        finished.append("done")
        return TickResult({})

    job = Job("slow", 0.01, slow)
    worker = Worker([job], engine=None, advisory_locks=False)
    task = asyncio.create_task(worker.run())
    await started.wait()
    worker.request_stop()
    await asyncio.sleep(0.05)
    assert not task.done()  # waits for the tick in progress
    release.set()
    await asyncio.wait_for(task, timeout=5)
    assert finished == ["done"] and worker.state["slow"].ticks == 1


async def test_stop_is_checked_between_messages_in_a_dispatch_tick(committed: Factory) -> None:
    world = await setup(committed)
    await ingest(committed, world, world.event("hi"))
    assert isinstance(world.connector, MockConnector)
    await queue_manual(committed, world, "m", count=5)
    worker = worker_for(committed, world)
    dispatch = worker.jobs[0]
    real_send = world.connector._do_send_message  # noqa: SLF001

    async def send_then_stop(request: Any) -> Any:
        result = await real_send(request)
        worker.request_stop()  # SIGTERM arrives during the first send
        return result

    world.connector._do_send_message = send_then_stop  # type: ignore[method-assign]  # noqa: SLF001
    await worker.run_tick(dispatch)
    # The send in flight completed and was committed; nothing else was started.
    assert len(world.connector.transport_log) == 1


async def test_health_reports_stale_jobs_and_stopping() -> None:
    job, _ = counting_job("j")
    worker = Worker([job], engine=None, advisory_locks=False)
    await worker.run_tick(job)
    status, body = health_body(worker, stale_after_s=60)
    assert status == 200 and body["jobs"] == {"j": {"ticks": 1, "skipped": 0, "errors": 0}}
    worker.state["j"].last_progress -= 120
    assert health_body(worker, stale_after_s=60)[0] == 503
    worker.state["j"].last_progress += 120
    worker.request_stop()
    assert health_body(worker, stale_after_s=60)[0] == 503


async def test_health_endpoint_over_tcp() -> None:
    job, _ = counting_job("j")
    worker = Worker([job], engine=None, advisory_locks=False)
    server = await start_health_server(worker, "127.0.0.1", 0, 60)
    port = server.sockets[0].getsockname()[1]
    try:
        for path, expected in (("/health", 200), ("/other", 404)):
            reader, writer = await asyncio.open_connection("127.0.0.1", port)
            writer.write(f"GET {path} HTTP/1.1\r\nHost: x\r\n\r\n".encode())
            await writer.drain()
            raw = await reader.read()
            writer.close()
            head, _, payload = raw.partition(b"\r\n\r\n")
            assert head.split()[1] == str(expected).encode()
            json.loads(payload)
    finally:
        server.close()
        await server.wait_closed()


def test_settings_defaults_and_env(monkeypatch: pytest.MonkeyPatch) -> None:
    s = WorkerSettings()
    assert (s.dispatch_interval_s, s.lease_expiry_interval_s, s.approval_expiry_interval_s) == (
        2.0,
        15.0,
        60.0,
    )
    assert s.advisory_locks and not s.dispatch_mock and s.health_port is None
    monkeypatch.setenv("WINGMAN_WORKER_DISPATCH_INTERVAL_S", "0.5")
    monkeypatch.setenv("WINGMAN_WORKER_HEALTH_PORT", "8081")
    s = WorkerSettings()
    assert s.dispatch_interval_s == 0.5 and s.health_port == 8081
    monkeypatch.setenv("WINGMAN_WORKER_DISPATCH_INTERVAL_S", "0")
    with pytest.raises(ValueError):
        WorkerSettings()


async def test_logs_never_carry_message_text(
    committed: Factory, caplog: pytest.LogCaptureFixture
) -> None:
    """Sentinel text in messages, a transport failure detail and an exception message;
    none of it may reach any log record at any level."""
    caplog.set_level(logging.DEBUG)
    world = await setup(committed)
    await ingest(committed, world, world.event(f"hi {SENTINEL}"))
    assert isinstance(world.connector, MockConnector)
    ids = await queue_manual(committed, world, f"secret {SENTINEL}", count=3)
    world.connector.inject_send_failures(1, retryable=True, detail=f"radio {SENTINEL}")
    worker = worker_for(committed, world)
    await worker.run_once()

    # A dispatch that raises with the text in the exception (like a DB error echoing
    # bound parameters), and a job whose tick raises the same.
    async def leaky(*_: Any, **__: Any) -> Any:
        statement = "UPDATE messages SET text=" + repr(SENTINEL)  # noqa: S608 - fake error
        raise OperationalError(statement, {}, Exception(SENTINEL))

    world.clock.advance(minutes=5)
    world.dispatcher.dispatch = leaky  # type: ignore[method-assign]
    await worker.run_once()
    bad, _ = failing_job("leaky", RuntimeError(SENTINEL))
    await Worker([bad], engine=None, advisory_locks=False).run_tick(bad)

    assert caplog.records, "expected log output"
    for record in caplog.records:
        rendered = record.getMessage() + (record.exc_text or "") + str(record.exc_info or "")
        assert SENTINEL not in rendered, record.name
    assert any("error_class=OperationalError" in r.getMessage() for r in caplog.records)
    assert any(
        r.levelno == logging.DEBUG and "worker tick" in r.getMessage() for r in caplog.records
    )
    assert len(ids) == 3
