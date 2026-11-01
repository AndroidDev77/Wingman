"""FastAPI application factory.

Routers from feature tickets are registered here by the integrator only
(see AGENTS.md: shared wiring is integrator-owned).
"""

from datetime import UTC, datetime
from typing import Literal

from fastapi import FastAPI
from pydantic import BaseModel

from app import __version__
from app.config import get_settings


class HealthResponse(BaseModel):
    status: Literal["ok"]
    version: str
    env: str
    time: datetime


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(title="Wingman AI", version=__version__)

    @app.get("/health", response_model=HealthResponse, tags=["health"])
    async def health() -> HealthResponse:
        # Liveness only: deliberately does not touch DB/Redis so the
        # service can boot and report healthy in mock mode.
        return HealthResponse(
            status="ok", version=__version__, env=settings.env, time=datetime.now(UTC)
        )

    return app


app = create_app()
