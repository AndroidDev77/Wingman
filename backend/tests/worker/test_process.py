"""``python -m app.worker`` as a real process against a migrated Postgres: it starts,
ticks, serves /health, and exits cleanly (code 0) on SIGTERM after the current tick."""

import os
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from tests.automation.conftest import committed_db_url  # noqa: F401

BACKEND = Path(__file__).resolve().parents[2]


def _free_port() -> int:
    import socket

    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def test_worker_process_ticks_and_exits_cleanly_on_sigterm(committed_db_url: str) -> None:  # noqa: F811
    port = _free_port()
    env = {
        **os.environ,
        "WINGMAN_DATABASE_URL": committed_db_url,
        "WINGMAN_ENV": "test",
        "WINGMAN_LOG_LEVEL": "DEBUG",
        "WINGMAN_WORKER_DISPATCH_INTERVAL_S": "0.2",
        "WINGMAN_WORKER_LEASE_EXPIRY_INTERVAL_S": "0.2",
        "WINGMAN_WORKER_APPROVAL_EXPIRY_INTERVAL_S": "0.2",
        "WINGMAN_WORKER_HEALTH_HOST": "127.0.0.1",
        "WINGMAN_WORKER_HEALTH_PORT": str(port),
    }
    proc = subprocess.Popen(  # noqa: S603
        [sys.executable, "-m", "app.worker"],
        cwd=BACKEND,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    try:
        body = None
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline and body is None:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:  # noqa: S310
                    body = r.read().decode()
            except OSError:
                time.sleep(0.2)
        assert body is not None, "health endpoint never answered"
        assert '"status": "ok"' in body
        time.sleep(1.0)  # several ticks of each job
        proc.send_signal(signal.SIGTERM)
        out, _ = proc.communicate(timeout=20)
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.communicate()
    assert proc.returncode == 0, out
    assert "worker started" in out
    for job in ("dispatch_due", "expire_leases", "expire_approvals"):
        assert f"worker tick job={job} ok=True" in out, out
    assert "worker stop requested" in out
    assert out.rstrip().endswith("worker stopped"), out[-500:]
    assert "Traceback" not in out


def test_dev_server_runs_api_and_worker_in_one_process(committed_db_url: str) -> None:  # noqa: F811
    """DEV story for I-016: ``uvicorn app.worker.dev_server:app`` serves the API and runs
    the embedded worker (mock dispatch on) in the same process, and stops both cleanly."""
    port = _free_port()
    env = {
        **os.environ,
        "WINGMAN_DATABASE_URL": committed_db_url,
        "WINGMAN_ENV": "dev",
        "WINGMAN_LOG_LEVEL": "DEBUG",
        "WINGMAN_WORKER_DISPATCH_INTERVAL_S": "0.2",
    }
    proc = subprocess.Popen(  # noqa: S603
        [
            sys.executable,
            "-m",
            "uvicorn",
            "app.worker.dev_server:app",
            "--host",
            "127.0.0.1",
            "--port",
            str(port),
        ],
        cwd=BACKEND,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    try:
        body = None
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline and body is None:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:  # noqa: S310
                    body = r.read().decode()
            except OSError:
                time.sleep(0.2)
        assert body is not None and '"env":"dev"' in body.replace(" ", "")
        time.sleep(0.8)
        proc.send_signal(signal.SIGTERM)
        out, _ = proc.communicate(timeout=20)
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.communicate()
    # uvicorn >= 0.29 re-raises the captured SIGTERM after a graceful shutdown.
    assert proc.returncode in (0, -signal.SIGTERM), out
    assert "worker tick job=dispatch_due ok=True" in out, out
    assert out.index("worker stopped") < out.index("Application shutdown complete"), out
    assert "Traceback" not in out
