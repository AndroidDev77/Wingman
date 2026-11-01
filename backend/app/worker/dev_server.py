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

With ``WINGMAN_LLM_PROVIDER=mock`` (the default) every model call here is answered by
the deterministic, clearly labeled dev handler (:mod:`app.ai.providers.dev_mock`), so
drafts, analysis and memory work out of the box without scripting (I-021). That covers
the inbound pipeline, ``createDraft`` and the memory routes. A configured real provider
is used unchanged (the handler only applies to the mock provider).
"""

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.ai.analyzer import ConversationAnalyzer
from app.ai.llm_gateway import LLMGateway, build_gateway
from app.ai.memory import MemoryService
from app.ai.providers.dev_mock import build_dev_mock_handler
from app.ai.reply_generator import ReplyGenerator
from app.api._automation_common import build_services
from app.api.drafts import get_reply_generator
from app.config import get_settings
from app.db.session import get_engine, get_session_factory
from app.main import create_app
from app.services.automation import AutomationController
from app.services.dispatcher import Dispatcher
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


def dev_gateway() -> LLMGateway:
    """The configured gateway; for the mock provider, answered by the dev handler."""
    return build_gateway(mock_handler=build_dev_mock_handler())


def dev_pipeline() -> tuple[AutomationController, ReplyGenerator, MemoryService]:
    """Like ``default_pipeline_controller``, on :func:`dev_gateway`; also returns the
    generator and memory service so ``createDraft`` and the memory routes share them."""
    gateway = dev_gateway()
    memory = MemoryService(gateway)
    generator = ReplyGenerator(gateway, memory=memory)
    controller = AutomationController(
        Dispatcher(),
        generator=generator,
        analyzer=ConversationAnalyzer(gateway, memory=memory),
        memory=memory,
    )
    return controller, generator, memory


@asynccontextmanager
async def _lifespan(application: FastAPI) -> AsyncIterator[None]:
    async with _inner(application):
        controller, generator, memory = dev_pipeline()
        services = build_services(controller=controller)
        application.state.automation = services
        # createDraft and the memory routes use the same dev gateway.
        application.dependency_overrides[get_reply_generator] = lambda: generator
        application.state.memory_service = memory
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
