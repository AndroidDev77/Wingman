"""FastAPI application factory.

Routers from feature tickets are registered here by the integrator only
(see AGENTS.md: shared wiring is integrator-owned).
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import Literal

from fastapi import FastAPI
from pydantic import BaseModel

from app import __version__
from app.api.auth import create_auth_router
from app.api.demo_connector import create_demo_connector_router
from app.api.errors import register_error_handlers
from app.api.preferences import router as preferences_router
from app.config import get_settings
from app.db.session import dispose_engine
from app.security.factory import check_auth_config


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

    # WING-006 simulator: unauthenticated, in-memory, single demo user. Never in prod.
    if settings.env != "prod":
        app.include_router(create_demo_connector_router())

    @app.get("/health", response_model=HealthResponse, tags=["health"])
    async def health() -> HealthResponse:
        # Liveness only: deliberately does not touch DB/Redis so the
        # service can boot and report healthy in mock mode.
        return HealthResponse(
            status="ok", version=__version__, env=settings.env, time=datetime.now(UTC)
        )

    return app


app = create_app()
