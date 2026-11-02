"""Fixtures for the WING-032 cross-platform mock E2E suite.

Runs only through ``scripts/e2e/platforms/run.sh`` (``WINGMAN_E2E_PLATFORMS=1``): the WING-025
run (``pytest tests/e2e``) collects nothing from this directory, so its own run-wide
invariants and timings are unchanged.

The server is the WING-025 one (``scripts/dev-run.sh``: API + worker, dev auth stub, labeled
dev mock LLM) started through ``scripts/e2e/platforms/scripted-dev-run.sh``, which adds only the
``[e2e scripted]`` date answers (see ``e2e_scripted_server.py``). Users are prefixed
``e2e-plat-<run id>-`` so the invariants cover exactly this run.
"""

from __future__ import annotations

import os
from collections.abc import Iterator
from pathlib import Path

import pytest

from tests.e2e.conftest import RUN_ID, mint_token
from tests.e2e.harness import REPO, Api
from tests.e2e.platforms.platform_harness import ScriptedServer

if os.environ.get("WINGMAN_E2E_PLATFORMS") != "1":
    # Collected only by scripts/e2e/platforms/run.sh; the WING-025 suite ignores this directory.
    collect_ignore_glob = ["test_*.py"]

ARTIFACTS = Path(os.environ.get("WINGMAN_E2E_ARTIFACTS", REPO / "tests" / "e2e" / "platforms" / ".artifacts"))
SUBJECT_PREFIX = f"e2e-plat-{RUN_ID}-"


@pytest.fixture(scope="session")
def server(server_env: dict[str, str]) -> Iterator[ScriptedServer]:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    srv = ScriptedServer(env=server_env, log_path=ARTIFACTS / "server.log")
    srv.start()
    try:
        yield srv
    finally:
        srv.stop()


@pytest.fixture(scope="session")
def make_user(server: ScriptedServer, server_env: dict[str, str]) -> Iterator[object]:
    made: list[Api] = []

    def make(label: str) -> Api:
        subject = f"{SUBJECT_PREFIX}{label}"
        api = Api(server.base_url, mint_token(server_env, subject, label.title()), subject)
        api.me()
        made.append(api)
        return api

    yield make
    for api in made:
        api.close()
