"""Fixtures for the WING-025 E2E suite.

``scripts/e2e/run.sh`` (or CI's ``e2e.yml``) provides a migrated, EMPTY Postgres database
in ``WINGMAN_DATABASE_URL`` and the dev auth stub secret. This conftest starts the backend
exactly as a developer does (``scripts/dev-run.sh``: API and worker in one process, dev
auth stub, labeled dev mock LLM), and stops it at the end of the session.

Short worker intervals (``WINGMAN_WORKER_*``) are set so delayed sends and lease expiry are
observed in seconds instead of minutes; nothing else is changed from the dev defaults.
"""

from __future__ import annotations

import os
import subprocess
import sys
import uuid
from collections.abc import Iterator
from pathlib import Path

import pytest

from tests.e2e.harness import REPO, Api, Db, Server

BACKEND = REPO / "backend"
ARTIFACTS = Path(os.environ.get("WINGMAN_E2E_ARTIFACTS", REPO / "tests" / "e2e" / ".artifacts"))
RUN_ID = os.environ.get("WINGMAN_E2E_RUN_ID", uuid.uuid4().hex[:8])


def _require(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        pytest.exit(f"{name} is not set: run the suite with scripts/e2e/run.sh", returncode=2)
    return value


@pytest.fixture(scope="session")
def database_url() -> str:
    return _require("WINGMAN_DATABASE_URL")


@pytest.fixture(scope="session")
def server_env(database_url: str) -> dict[str, str]:
    env = dict(os.environ)
    env.update(
        WINGMAN_ENV="dev",
        WINGMAN_DATABASE_URL=database_url,
        WINGMAN_LLM_PROVIDER="mock",
        WINGMAN_AUTH_DEV_STUB_ENABLED="true",
        WINGMAN_AUTH_DEV_SECRET=_require("WINGMAN_AUTH_DEV_SECRET"),
        # Faster ticks so reply delays and lease expiry are observable in seconds.
        WINGMAN_WORKER_DISPATCH_INTERVAL_S="1",
        WINGMAN_WORKER_LEASE_EXPIRY_INTERVAL_S="1",
        WINGMAN_WORKER_APPROVAL_EXPIRY_INTERVAL_S="5",
    )
    return env


@pytest.fixture(scope="session")
def server(server_env: dict[str, str]) -> Iterator[Server]:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    srv = Server(env=server_env, log_path=ARTIFACTS / "server.log")
    srv.start()
    try:
        yield srv
    finally:
        srv.stop()


@pytest.fixture(scope="session")
def db(database_url: str) -> Db:
    return Db(database_url)


def mint_token(env: dict[str, str], subject: str, name: str) -> str:
    """The backend dev-stub CLI, exactly as docs/auth.md documents it."""
    out = subprocess.run(  # noqa: S603 - repo CLI
        ["uv", "run", "--quiet", "python", "-m", "app.security.dev_stub", "--sub", subject, "--name", name],
        cwd=BACKEND,
        env=env,
        capture_output=True,
        text=True,
        check=True,
    )
    return out.stdout.strip().splitlines()[-1]


@pytest.fixture(scope="session")
def make_user(server: Server, server_env: dict[str, str]) -> Iterator[object]:
    made: list[Api] = []

    def make(label: str) -> Api:
        subject = f"e2e-{RUN_ID}-{label}"
        api = Api(server.base_url, mint_token(server_env, subject, label.title()), subject)
        api.me()
        made.append(api)
        return api

    yield make
    for api in made:
        api.close()


@pytest.fixture(scope="session")
def run_users(db: Db) -> list[str]:
    """Users created by this run (subject prefix), for the run-wide invariants."""
    return []


def pytest_report_header(config: pytest.Config) -> list[str]:
    return [f"wingman e2e run id: {RUN_ID}", f"python: {sys.version.split()[0]}"]
