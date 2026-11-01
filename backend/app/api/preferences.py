"""``/v1`` preference endpoints (WING-007), exactly as in ``contracts/openapi.yaml`` v1.0.0.

- ``GET  /v1/preferences``                          user-wide preferences (or safe defaults)
- ``PUT  /v1/preferences``                          replace; ``If-Match: "<version>"`` optional
- ``GET  /v1/preferences/versions``                 history, newest first, cursor-paginated
- ``GET  /v1/contacts/{contact_id}/preferences``    override + merged effective settings
- ``PUT  /v1/contacts/{contact_id}/preferences``    replace the override (never user-wide)
- ``DELETE /v1/contacts/{contact_id}/preferences``  clear the override (204, also if none)

Mounting (integrator, in ``app/main.py``)::

    from app.api.preferences import router as preferences_router
    app.include_router(preferences_router)

Errors are ``application/problem+json`` (ADR 0002) produced here, independent of any
app-wide handler: request validation -> ``422 validation_error`` with ``errors[]``;
unknown or other-user contact -> ``404 not_found``; stale ``If-Match`` ->
``412 precondition_failed``; bad cursor -> ``422 validation_error``; ``HTTPException``
raised by dependencies (e.g. the auth dependency's 401) -> the matching ``code``.

Handlers commit before responding, so a ``200`` means the version is durable.
"""

import uuid
from collections.abc import AsyncIterator, Callable, Coroutine, Mapping
from contextlib import asynccontextmanager
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.errors import InvalidCursorError, NotFoundError, VersionConflictError
from app.db.pagination import MAX_CURSOR_LEN, MAX_LIMIT
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope
from app.services.preferences import (
    ContactPreferenceOverrideUpdate,
    ContactPreferences,
    Preferences,
    PreferencesPage,
    PreferencesService,
    PreferencesUpdate,
)

PROBLEM_MEDIA_TYPE = "application/problem+json"
PROBLEM_TYPE_BASE = "https://wingman.invalid/problems/"
IF_MATCH_PATTERN = r'^"[0-9]+"$'

_STATUS_CODES: dict[int, tuple[str, str]] = {
    401: ("unauthenticated", "Authentication required"),
    404: ("not_found", "Not found"),
    412: ("precondition_failed", "Precondition failed"),
    422: ("validation_error", "Request validation failed"),
    429: ("rate_limited", "Too many requests"),
}


class ProblemError(Exception):
    """Raise from a handler to return a contract ``Problem``."""

    def __init__(
        self,
        status_code: int,
        code: str,
        title: str,
        detail: str | None = None,
        errors: list[dict[str, str]] | None = None,
    ) -> None:
        super().__init__(detail or title)
        self.status_code = status_code
        self.code = code
        self.title = title
        self.detail = detail
        self.errors = errors


def problem_response(
    request: Request,
    status_code: int,
    code: str,
    title: str,
    detail: str | None = None,
    errors: list[dict[str, str]] | None = None,
    headers: Mapping[str, str] | None = None,
) -> JSONResponse:
    body: dict[str, Any] = {
        "type": f"{PROBLEM_TYPE_BASE}{code}",
        "title": title,
        "status": status_code,
        "code": code,
        "instance": request.url.path,
    }
    if detail:
        body["detail"] = detail
    if errors is not None:
        body["errors"] = errors
    return JSONResponse(
        body, status_code=status_code, media_type=PROBLEM_MEDIA_TYPE, headers=headers
    )


def _pointer(loc: tuple[int | str, ...], error_type: str) -> str:
    """FastAPI error location -> contract pointer (JSON Pointer or ``query:``/``header:``)."""
    if not loc:
        return ""
    where, rest = loc[0], loc[1:]
    if where == "body":
        if error_type == "json_invalid":
            return ""
        parts = (str(p).replace("~", "~0").replace("/", "~1") for p in rest)
        return "".join(f"/{p}" for p in parts)
    if where in ("query", "header", "path") and rest:
        return f"{where}:{rest[0]}"
    return "/".join(str(p) for p in loc)


def validation_errors(exc: RequestValidationError) -> list[dict[str, str]]:
    # Messages only, never the submitted input (it may contain private text).
    return [
        {"pointer": _pointer(tuple(e.get("loc", ())), str(e.get("type", ""))), "message": e["msg"]}
        for e in exc.errors()
    ]


class ProblemRoute(APIRoute):
    """Route class that turns request/dependency/domain errors into ``Problem`` bodies."""

    def get_route_handler(self) -> Callable[[Request], Coroutine[Any, Any, Response]]:
        handler = super().get_route_handler()

        async def route_handler(request: Request) -> Response:
            try:
                return await handler(request)
            except RequestValidationError as exc:
                return problem_response(
                    request,
                    422,
                    "validation_error",
                    "Request validation failed",
                    errors=validation_errors(exc),
                )
            except ProblemError as exc:
                return problem_response(
                    request, exc.status_code, exc.code, exc.title, exc.detail, exc.errors
                )
            except HTTPException as exc:
                mapped = _STATUS_CODES.get(exc.status_code)
                if mapped is None:
                    raise
                code, title = mapped
                detail = exc.detail if isinstance(exc.detail, str) else None
                return problem_response(
                    request, exc.status_code, code, title, detail, headers=exc.headers
                )

        return route_handler


@asynccontextmanager
async def _domain_errors(session: AsyncSession) -> AsyncIterator[None]:
    """Map persistence errors to problems, rolling back any partial write first."""
    try:
        yield
    except NotFoundError as exc:
        await session.rollback()
        raise ProblemError(404, "not_found", "Not found") from exc
    except VersionConflictError as exc:
        await session.rollback()
        raise ProblemError(
            412,
            "precondition_failed",
            "Precondition failed",
            "If-Match does not match the current version; re-read and retry.",
        ) from exc
    except InvalidCursorError as exc:
        await session.rollback()
        raise ProblemError(
            422,
            "validation_error",
            "Request validation failed",
            errors=[{"pointer": "query:cursor", "message": "invalid cursor"}],
        ) from exc


def _expected_version(if_match: str | None) -> int | None:
    return None if if_match is None else int(if_match.strip('"'))


Session = Annotated[AsyncSession, Depends(get_session, scope="function")]
Scope = Annotated[UserScope, Depends(get_current_user_scope)]
IfMatch = Annotated[
    str | None,
    Header(
        alias="If-Match",
        pattern=IF_MATCH_PATTERN,
        description='Quoted current `version`, e.g. `"3"`. Mismatch -> 412.',
    ),
]

router = APIRouter(prefix="/v1", tags=["preferences"], route_class=ProblemRoute)


@router.get("/preferences", response_model=Preferences, operation_id="getPreferences")
async def get_preferences(session: Session, scope: Scope) -> Preferences:
    return await PreferencesService(session, scope).get_user_preferences()


@router.put("/preferences", response_model=Preferences, operation_id="putPreferences")
async def put_preferences(
    body: PreferencesUpdate, session: Session, scope: Scope, if_match: IfMatch = None
) -> Preferences:
    async with _domain_errors(session):
        result = await PreferencesService(session, scope).put_user_preferences(
            body, expected_version=_expected_version(if_match)
        )
        await session.commit()
    return result


@router.get(
    "/preferences/versions",
    response_model=PreferencesPage,
    operation_id="listPreferenceVersions",
)
async def list_preference_versions(
    session: Session,
    scope: Scope,
    cursor: Annotated[str | None, Query(min_length=1, max_length=MAX_CURSOR_LEN)] = None,
    limit: Annotated[int, Query(ge=1, le=MAX_LIMIT)] = 50,
) -> PreferencesPage:
    async with _domain_errors(session):
        return await PreferencesService(session, scope).list_user_versions(
            cursor=cursor, limit=limit
        )


@router.get(
    "/contacts/{contact_id}/preferences",
    response_model=ContactPreferences,
    operation_id="getContactPreferences",
)
async def get_contact_preferences(
    contact_id: uuid.UUID, session: Session, scope: Scope
) -> ContactPreferences:
    async with _domain_errors(session):
        return await PreferencesService(session, scope).get_contact_preferences(contact_id)


@router.put(
    "/contacts/{contact_id}/preferences",
    response_model=ContactPreferences,
    operation_id="putContactPreferences",
)
async def put_contact_preferences(
    contact_id: uuid.UUID,
    body: ContactPreferenceOverrideUpdate,
    session: Session,
    scope: Scope,
    if_match: IfMatch = None,
) -> ContactPreferences:
    async with _domain_errors(session):
        result = await PreferencesService(session, scope).put_contact_override(
            contact_id, body, expected_version=_expected_version(if_match)
        )
        await session.commit()
    return result


@router.delete(
    "/contacts/{contact_id}/preferences",
    status_code=status.HTTP_204_NO_CONTENT,
    response_class=Response,
    operation_id="deleteContactPreferences",
)
async def delete_contact_preferences(
    contact_id: uuid.UUID, session: Session, scope: Scope
) -> Response:
    async with _domain_errors(session):
        await PreferencesService(session, scope).clear_contact_override(contact_id)
        await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)
