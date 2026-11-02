from __future__ import annotations

import sys
from collections.abc import Iterator
from datetime import datetime
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from fixture_db import FixtureDb, at, standard_fixture  # noqa: E402

from wingman_imessage.chatdb import ChatDbReader  # noqa: E402
from wingman_imessage.envelope import Scope  # noqa: E402
from wingman_imessage.state import StateStore  # noqa: E402

USER_ID = "0192f3a0-0000-7000-8000-000000000001"
CONNECTION_ID = "0192f3a0-0000-7000-8000-0000000000c1"


class Clock:
    def __init__(self, start: datetime) -> None:
        self.now = start

    def __call__(self) -> datetime:
        return self.now


@pytest.fixture
def scope() -> Scope:
    return Scope(user_id=USER_ID, connection_id=CONNECTION_ID)


@pytest.fixture
def fixture(tmp_path: Path) -> Iterator[FixtureDb]:
    db = standard_fixture(tmp_path / "chat.db")
    yield db
    db.close()


@pytest.fixture
def reader(fixture: FixtureDb) -> Iterator[ChatDbReader]:
    r = ChatDbReader(fixture.path)
    yield r
    r.close()


@pytest.fixture
def store(tmp_path: Path) -> StateStore:
    return StateStore(tmp_path / "state" / "companion-state.json")


@pytest.fixture
def clock() -> Clock:
    return Clock(at(0))
