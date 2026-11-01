"""``GET /v1/me`` (contract ``getMe``).

Mounting (integrator, in ``app/main.py``)::

    from app.api.auth import create_auth_router
    app.include_router(create_auth_router())
"""

import uuid
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter
from pydantic import BaseModel, ConfigDict, Field, field_serializer

from app.db.models import User
from app.security.deps import CurrentUser


class UserOut(BaseModel):
    """Contract ``User``."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    email: str | None = None
    display_name: Annotated[str | None, Field(max_length=120)] = None
    created_at: datetime

    @field_serializer("id")
    def _ser_id(self, v: uuid.UUID) -> str:
        return str(v)

    @field_serializer("created_at")
    def _ser_ts(self, v: datetime) -> str:
        # Contract Timestamp: UTC with a literal "Z".
        return v.astimezone(UTC).isoformat().replace("+00:00", "Z")

    @classmethod
    def from_user(cls, user: User) -> "UserOut":
        return cls.model_validate(user)


def create_auth_router() -> APIRouter:
    router = APIRouter(prefix="/v1", tags=["users"])

    @router.get(
        "/me",
        operation_id="getMe",
        summary="The authenticated user",
        response_model=UserOut,
    )
    async def get_me(user: CurrentUser) -> UserOut:
        return UserOut.from_user(user)

    return router


router = create_auth_router()
