"""Worker tests: real PostgreSQL (committing scratch database per test, the WING-003
throwaway cluster or ``WINGMAN_TEST_DATABASE_URL``), the in-process ``MockConnector``
and a controllable clock. No network, no model keys."""

from collections.abc import Callable
from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.services.dispatcher import Dispatcher
from app.worker.config import WorkerSettings
from app.worker.runner import Worker, build_jobs

# Real-Postgres fixtures and the WING-011 world helpers.
from tests.automation.conftest import (  # noqa: F401
    World,
    committed,
    committed_db_url,
    migrated_db_url,
    pg_server_url,
)

Factory = async_sessionmaker[AsyncSession]


def worker_for(
    factory: Factory,
    world: World,
    *,
    advisory_locks: bool = False,
    dispatcher: Dispatcher | None = None,
    now: Callable[[], datetime] | None = None,
    **settings: float | int | bool,
) -> Worker:
    """A worker on the world's dispatcher, controller and clock. Mock dispatch is on:
    the test owns the simulator, like the dev single-process server (I-016)."""
    cfg = WorkerSettings.model_validate(
        {"dispatch_mock": True, "advisory_locks": advisory_locks, **settings}
    )
    return Worker(
        build_jobs(
            cfg,
            factory,
            dispatcher=dispatcher or world.dispatcher,
            controller=world.controller,
            now=now or world.clock,
        ),
        engine=factory.kw["bind"],
        advisory_locks=advisory_locks,
        backoff_max_s=cfg.backoff_max_s,
    )
