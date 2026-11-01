"""FastAPI application factory.

Routers from feature tickets are registered here by the integrator only
(see AGENTS.md: shared wiring is integrator-owned).
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import Literal

from fastapi import FastAPI, Request
from pydantic import BaseModel

from app import __version__
from app.ai.llm_gateway import build_gateway
from app.ai.memory import MemoryService
from app.api.agent_actions import router as agent_actions_router
from app.api.auth import create_auth_router
from app.api.connections import router as connections_router
from app.api.conversations import router as conversations_router
from app.api.demo_connector import create_demo_connector_router
from app.api.device_connector import router as device_connector_router
from app.api.drafts import router as drafts_router
from app.api.errors import register_error_handlers
from app.api.memories import create_memories_router
from app.api.messages import router as messages_router
from app.api.preferences import router as preferences_router
from app.config import get_settings
from app.db.session import dispose_engine
from app.security.factory import check_auth_config


async def get_memory_service(request: Request) -> MemoryService:
    """FastAPI dependency: the app's single ``MemoryService``.

    Built from ``build_gateway()`` on first use and then reused for every request of
    this app, so no provider is constructed per request. Built lazily (not in
    ``create_app``) so an LLM misconfiguration cannot stop unrelated routes from
    starting; listing, correcting and deleting memories never call the model. It is
    ``async`` so it runs on the event loop: there is no await between the check and
    the assignment, so concurrent first requests cannot build two services.
    """
    service: MemoryService | None = getattr(request.app.state, "memory_service", None)
    if service is None:
        service = MemoryService(build_gateway())
        request.app.state.memory_service = service
    return service


class HealthResponse(BaseModel):
    status: Literal["ok"]
    version: str
    env: str
    time: datetime


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    yield
    # The engine is created lazily on first DB use; this is a no-op if it never was.
    await dispose_engine()


def create_app() -> FastAPI:
    settings = get_settings()
    # WING-004: refuse to start on unsafe auth config (e.g. the dev stub with env=prod).
    check_auth_config()
    app = FastAPI(title="Wingman AI", version=__version__, lifespan=lifespan)
    # Every error response is a contract problem+json body (I-004).
    register_error_handlers(app)

    # WING-004: GET /v1/me.
    app.include_router(create_auth_router())
    # WING-007: /v1/preferences and /v1/contacts/{id}/preferences.
    app.include_router(preferences_router)
    # WING-008 (contract v1.1.0, D-012): contact memories, correction, deletion.
    app.include_router(create_memories_router(get_memory_service))
    # WING-010: POST /v1/conversations/{id}/drafts (suggestions only; never sends).
    app.include_router(drafts_router)
    # WING-011: approvals, pause/resume/takeover, audit timeline; the user's own send;
    # device event ingest and the leased device outbox (contract v1.1.0, ADR 0006).
    # All sends go through the single dispatcher (app.services.dispatcher).
    app.include_router(agent_actions_router)
    app.include_router(messages_router)
    app.include_router(device_connector_router)
    # Contract 1.2.0 (I-017, ADR 0007): list, get, pair and unpair connections. Pairing
    # never stores a client-claimed VERIFIED capability; `mock` is refused in prod.
    app.include_router(connections_router)
    # I-021: listConversations, getConversation, listMessages (read-only, user-scoped).
    app.include_router(conversations_router)

    # WING-006 simulator: unauthenticated, in-memory, single demo user. Never in prod.
    if settings.env != "prod":
        app.include_router(create_demo_connector_router())

    @app.get("/health", response_model=HealthResponse, tags=["health"], operation_id="getHealth")
    async def health() -> HealthResponse:
        # Liveness only: deliberately does not touch DB/Redis so the
        # service can boot and report healthy in mock mode.
        return HealthResponse(
            status="ok", version=__version__, env=settings.env, time=datetime.now(UTC)
        )

    return app


app = create_app()
