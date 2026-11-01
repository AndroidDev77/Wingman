"""DEV ONLY: the API and the worker in ONE process, sharing one dispatcher (I-016).

``MockConnector`` state (threads, send ledger) lives in process memory. A separate
``python -m app.worker`` process has its own, empty simulator, so it cannot send on a
``mock`` connection (and therefore skips mock messages by default). To exercise reply
delays and retry backoff end to end against the mock, run::

    uvicorn app.worker.dev_server:app --host 127.0.0.1 --port 8000

This builds its own instance with ``app.main.create_app()`` (``app.main.app`` is left
untouched) and wraps it with a lifespan that builds the app's
``AutomationServices`` up front and runs the worker loop in the same event loop with
that same dispatcher, so the worker sees exactly the simulator the API ingested into.
Refuses to start with ``WINGMAN_ENV=prod``.
"""

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api._automation_common import build_services, default_pipeline_controller
from app.config import get_settings
from app.db.session import get_engine, get_session_factory
from app.main import create_app
from app.worker.config import WorkerSettings
from app.worker.runner import Worker, build_jobs

__all__ = ["app"]

if get_settings().env == "prod":  # pragma: no cover - guard
    raise RuntimeError("app.worker.dev_server is for development only (WINGMAN_ENV=prod)")

logging.basicConfig(
    level=getattr(logging, get_settings().log_level.upper(), logging.INFO),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logging.getLogger("sqlalchemy.engine").setLevel(logging.WARNING)

api = create_app()
_inner = api.router.lifespan_context


@asynccontextmanager
async def _lifespan(application: FastAPI) -> AsyncIterator[None]:
    async with _inner(application):
        services = build_services(controller=default_pipeline_controller())
        application.state.automation = services
        settings = WorkerSettings(dispatch_mock=True)
        worker = Worker(
            build_jobs(
                settings,
                get_session_factory(),
                dispatcher=services.dispatcher,
                controller=services.controller,
            ),
            engine=get_engine(),
            advisory_locks=settings.advisory_locks,
            backoff_max_s=settings.backoff_max_s,
        )
        task = asyncio.create_task(worker.run(), name="dev-embedded-worker")
        try:
            yield
        finally:
            # Finish the current ticks before the engine is disposed by the inner lifespan.
            worker.request_stop()
            await task


api.router.lifespan_context = _lifespan
app = api
