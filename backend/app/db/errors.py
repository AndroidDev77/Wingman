"""Persistence-layer exceptions. API code maps them to ``Problem`` codes (ADR 0002)."""


class PersistenceError(Exception):
    """Base class for errors raised by ``app.db``."""


class InvalidCursorError(PersistenceError, ValueError):
    """Cursor is malformed or belongs to another listing. Map to ``422 validation_error``."""


class NotFoundError(PersistenceError, LookupError):
    """Row does not exist *in this user's scope*. Map to ``404 not_found``."""


class VersionConflictError(PersistenceError):
    """Optimistic-concurrency mismatch (``If-Match``). Map to ``412 precondition_failed``."""
