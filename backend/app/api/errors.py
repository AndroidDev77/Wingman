"""RFC 9457 ``application/problem+json`` error responses (ADR 0002, I-004).

The integrator calls :func:`register_error_handlers` once in ``create_app()``. After
that every error response, including FastAPI's request validation errors and
unhandled exceptions, is a contract ``Problem``::

    {"type": "https://wingman.invalid/problems/not_found", "title": "Not found",
     "status": 404, "code": "not_found", "detail": "...", "instance": "/v1/...",
     "request_id": "..."}

Feature code should raise :class:`ProblemException` when it needs a specific
``ErrorCode`` (``approval_not_pending``, ``capability_unsupported``, ...). Plain
``HTTPException`` is mapped from its status code. Persistence errors map as:
``NotFoundError`` -> 404 ``not_found``, ``VersionConflictError`` -> 412
``precondition_failed``, ``InvalidCursorError`` -> 422 ``validation_error``.

A ``403`` is never emitted: the contract says another user's resource is a ``404``,
so a stray ``HTTPException(403)`` is rewritten to ``404 not_found``.
"""

from __future__ import annotations

import logging
import re
import uuid
from collections.abc import Mapping, Sequence
from enum import StrEnum
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.db.errors import InvalidCursorError, NotFoundError, VersionConflictError

logger = logging.getLogger(__name__)

PROBLEM_CONTENT_TYPE = "application/problem+json"
PROBLEM_TYPE_BASE = "https://wingman.invalid/problems/"
REQUEST_ID_HEADER = "X-Request-ID"
_REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


class ErrorCode(StrEnum):
    """Mirror of the contract ``ErrorCode`` enum (tests check they match)."""

    VALIDATION_ERROR = "validation_error"
    IDEMPOTENCY_KEY_REQUIRED = "idempotency_key_required"
    IDEMPOTENCY_KEY_MISMATCH = "idempotency_key_mismatch"
    IDEMPOTENCY_KEY_IN_PROGRESS = "idempotency_key_in_progress"
    UNAUTHENTICATED = "unauthenticated"
    NOT_FOUND = "not_found"
    PRECONDITION_FAILED = "precondition_failed"
    APPROVAL_NOT_PENDING = "approval_not_pending"
    AUTOMATION_PAUSED = "automation_paused"
    CAPABILITY_UNSUPPORTED = "capability_unsupported"
    CAPABILITY_UNVERIFIED = "capability_unverified"
    CONNECTION_UNAVAILABLE = "connection_unavailable"
    RATE_LIMITED = "rate_limited"
    UPSTREAM_UNAVAILABLE = "upstream_unavailable"
    INTERNAL_ERROR = "internal_error"


_TITLES: dict[ErrorCode, str] = {
    ErrorCode.VALIDATION_ERROR: "Validation failed",
    ErrorCode.IDEMPOTENCY_KEY_REQUIRED: "Idempotency-Key required",
    ErrorCode.IDEMPOTENCY_KEY_MISMATCH: "Idempotency-Key reused with a different request",
    ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS: "Request with this Idempotency-Key is in progress",
    ErrorCode.UNAUTHENTICATED: "Unauthenticated",
    ErrorCode.NOT_FOUND: "Not found",
    ErrorCode.PRECONDITION_FAILED: "Precondition failed",
    ErrorCode.APPROVAL_NOT_PENDING: "Approval is not pending",
    ErrorCode.AUTOMATION_PAUSED: "Automation is paused",
    ErrorCode.CAPABILITY_UNSUPPORTED: "Capability unsupported",
    ErrorCode.CAPABILITY_UNVERIFIED: "Capability unverified",
    ErrorCode.CONNECTION_UNAVAILABLE: "Connection unavailable",
    ErrorCode.RATE_LIMITED: "Rate limited",
    ErrorCode.UPSTREAM_UNAVAILABLE: "Upstream unavailable",
    ErrorCode.INTERNAL_ERROR: "Internal server error",
}

# Best-effort mapping for plain HTTPException. Raise ProblemException for anything
# that needs a more specific code (e.g. 409 approval_not_pending).
_STATUS_CODES: dict[int, ErrorCode] = {
    400: ErrorCode.VALIDATION_ERROR,
    401: ErrorCode.UNAUTHENTICATED,
    404: ErrorCode.NOT_FOUND,
    405: ErrorCode.NOT_FOUND,
    412: ErrorCode.PRECONDITION_FAILED,
    422: ErrorCode.VALIDATION_ERROR,
    429: ErrorCode.RATE_LIMITED,
    502: ErrorCode.UPSTREAM_UNAVAILABLE,
    503: ErrorCode.UPSTREAM_UNAVAILABLE,
    504: ErrorCode.UPSTREAM_UNAVAILABLE,
}


class ProblemException(Exception):
    """Raise from handlers/services to return a specific contract ``Problem``."""

    def __init__(
        self,
        status: int,
        code: ErrorCode,
        detail: str | None = None,
        *,
        title: str | None = None,
        errors: Sequence[Mapping[str, str]] | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> None:
        if not 400 <= status <= 599:
            raise ValueError("problem status must be 4xx or 5xx")
        super().__init__(detail or code.value)
        self.status = status
        self.code = code
        self.detail = detail
        self.title = title
        self.errors = list(errors) if errors is not None else None
        self.headers = dict(headers) if headers is not None else None


def _request_id(request: Request) -> str:
    incoming = request.headers.get(REQUEST_ID_HEADER)
    if incoming and _REQUEST_ID_RE.match(incoming):
        return incoming
    return uuid.uuid4().hex


def problem_response(
    request: Request,
    status: int,
    code: ErrorCode,
    *,
    detail: str | None = None,
    title: str | None = None,
    errors: Sequence[Mapping[str, str]] | None = None,
    headers: Mapping[str, str] | None = None,
) -> JSONResponse:
    request_id = _request_id(request)
    body: dict[str, Any] = {
        "type": PROBLEM_TYPE_BASE + code.value,
        "title": title or _TITLES[code],
        "status": status,
        "code": code.value,
        "instance": request.url.path,
        "request_id": request_id,
    }
    if detail:
        body["detail"] = detail
    if errors is not None:
        body["errors"] = [dict(e) for e in errors]
    out_headers = dict(headers or {})
    out_headers[REQUEST_ID_HEADER] = request_id
    return JSONResponse(
        body, status_code=status, headers=out_headers, media_type=PROBLEM_CONTENT_TYPE
    )


def _escape_pointer(token: Any) -> str:
    return str(token).replace("~", "~0").replace("/", "~1")


def error_pointer(loc: Sequence[Any]) -> str:
    """FastAPI error ``loc`` -> contract pointer (JSON Pointer or ``query:<name>``)."""
    if not loc:
        return ""
    where, rest = loc[0], list(loc[1:])
    if where == "body":
        return "".join("/" + _escape_pointer(p) for p in rest)
    if where in ("query", "header", "path", "cookie") and rest:
        return f"{where}:{rest[0]}"
    return "".join("/" + _escape_pointer(p) for p in loc)


async def _problem_exception(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, ProblemException)  # noqa: S101
    return problem_response(
        request,
        exc.status,
        exc.code,
        detail=exc.detail,
        title=exc.title,
        errors=exc.errors,
        headers=exc.headers,
    )


async def _http_exception(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, StarletteHTTPException)  # noqa: S101
    status = exc.status_code
    detail: str | None = exc.detail if isinstance(exc.detail, str) else None
    if status == 403:  # contract: other users' resources look missing
        status, detail = 404, "Resource not found"
    code = _STATUS_CODES.get(status)
    if code is None:
        code = ErrorCode.INTERNAL_ERROR if status >= 500 else ErrorCode.VALIDATION_ERROR
    if code is ErrorCode.INTERNAL_ERROR:
        detail = None  # never leak server internals
    return problem_response(request, status, code, detail=detail, headers=exc.headers)


async def _validation_exception(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, RequestValidationError)  # noqa: S101
    # Only location and message; never echo the rejected input (it may be private text).
    errors = [
        {"pointer": error_pointer(e.get("loc", ())), "message": str(e.get("msg", "invalid"))}
        for e in exc.errors()
    ]
    return problem_response(
        request,
        422,
        ErrorCode.VALIDATION_ERROR,
        detail="Request body or parameters are invalid",
        errors=errors,
    )


async def _not_found(request: Request, exc: Exception) -> JSONResponse:
    return problem_response(request, 404, ErrorCode.NOT_FOUND, detail="Resource not found")


async def _version_conflict(request: Request, exc: Exception) -> JSONResponse:
    return problem_response(
        request,
        412,
        ErrorCode.PRECONDITION_FAILED,
        detail="If-Match does not match the current version",
    )


async def _invalid_cursor(request: Request, exc: Exception) -> JSONResponse:
    return problem_response(
        request,
        422,
        ErrorCode.VALIDATION_ERROR,
        detail="Invalid pagination cursor",
        errors=[{"pointer": "query:cursor", "message": "invalid or expired cursor"}],
    )


async def _unhandled(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("unhandled error on %s %s", request.method, request.url.path)
    return problem_response(request, 500, ErrorCode.INTERNAL_ERROR)


def register_error_handlers(app: FastAPI) -> None:
    """Install every problem+json handler on ``app``. Call once in ``create_app()``."""
    app.add_exception_handler(ProblemException, _problem_exception)
    app.add_exception_handler(StarletteHTTPException, _http_exception)
    app.add_exception_handler(RequestValidationError, _validation_exception)
    app.add_exception_handler(NotFoundError, _not_found)
    app.add_exception_handler(VersionConflictError, _version_conflict)
    app.add_exception_handler(InvalidCursorError, _invalid_cursor)
    app.add_exception_handler(Exception, _unhandled)
