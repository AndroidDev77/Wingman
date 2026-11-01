"""Fixtures for WING-007 preference tests on a real PostgreSQL database.

Reuses the ``tests/db`` server selection (``WINGMAN_TEST_DATABASE_URL`` or a throwaway
``initdb`` cluster; never silently skipped). Unlike ``tests/db`` these tests use real
commits through the same session factory the app uses, so "restart" tests can build a
brand-new engine against the same database. Isolation comes from a fresh user per test.

The test app mounts only the preferences router. Auth is injected via
``app.dependency_overrides[get_current_user_scope]`` (D-007): the scope is read from the
``X-Test-User`` header so one client can act as several users.
"""

import uuid
from collections.abc import AsyncIterator, Iterator
from functools import cache
from pathlib import Path
from typing import Any

import httpx
import pytest
import yaml
from fastapi import FastAPI, Header, HTTPException
from jsonschema import Draft202012Validator
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker

from alembic import command
from app.api.preferences import router
from app.db.repositories import ContactRepository
from app.db.scope import UserScope
from app.db.session import create_engine, create_session_factory, get_session, session_scope
from app.security.deps import get_current_user_scope
from tests.db.conftest import (
    alembic_config,
    create_scratch_database,
    drop_scratch_database,
    make_connection,
    make_user,
    pg_server_url,
)

__all__ = ["pg_server_url"]

OPENAPI = Path(__file__).resolve().parents[3] / "contracts" / "openapi.yaml"


# --------------------------------------------------------------------------- contract


@cache
def openapi() -> dict[str, Any]:
    doc: dict[str, Any] = yaml.safe_load(OPENAPI.read_text())
    return doc


@cache
def contract_validator(schema_name: str) -> Draft202012Validator:
    """Validator for ``#/components/schemas/<schema_name>`` of the frozen contract."""
    root = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "components": openapi()["components"],
        "$ref": f"#/components/schemas/{schema_name}",
    }
    return Draft202012Validator(root, format_checker=Draft202012Validator.FORMAT_CHECKER)


def assert_contract(schema_name: str, body: Any) -> None:
    contract_validator(schema_name).validate(body)


def assert_problem(resp: httpx.Response, status: int, code: str) -> dict[str, Any]:
    assert resp.status_code == status, resp.text
    assert resp.headers["content-type"].startswith("application/problem+json")
    body: dict[str, Any] = resp.json()
    assert_contract("Problem", body)
    assert body["code"] == code
    assert body["status"] == status
    assert body["type"] == f"https://wingman.invalid/problems/{code}"
    return body


# --------------------------------------------------------------------------- database


@pytest.fixture(scope="session")
def prefs_db_url(pg_server_url: str) -> Iterator[str]:
    url = create_scratch_database(pg_server_url)
    try:
        command.upgrade(alembic_config(url), "head")
        yield url
    finally:
        drop_scratch_database(pg_server_url, url)


@pytest.fixture
async def engine(prefs_db_url: str) -> AsyncIterator[AsyncEngine]:
    eng = create_engine(prefs_db_url)
    try:
        yield eng
    finally:
        await eng.dispose()


@pytest.fixture
def session_factory(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    return create_session_factory(engine)


@pytest.fixture
async def session(
    session_factory: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncSession]:
    async with session_factory() as s:
        yield s


async def new_user(factory: async_sessionmaker[AsyncSession]) -> UserScope:
    async with session_scope(factory) as s:
        return await make_user(s)


async def new_contact(factory: async_sessionmaker[AsyncSession], scope: UserScope) -> uuid.UUID:
    async with session_scope(factory) as s:
        conn_id = await make_connection(s, scope)
        contact = await ContactRepository(s, scope).get_or_create(
            connection_id=conn_id,
            external_contact_id=f"ext-{uuid.uuid4().hex}",
            display_name="Sam",
        )
        return contact.id


# --------------------------------------------------------------------------- app


def build_app(factory: async_sessionmaker[AsyncSession]) -> FastAPI:
    app = FastAPI()
    app.include_router(router)

    async def test_session() -> AsyncIterator[AsyncSession]:
        async with session_scope(factory) as s:
            yield s

    async def test_scope(x_test_user: str | None = Header(default=None)) -> UserScope:
        if x_test_user is None:
            raise HTTPException(status_code=401, detail="no test user")
        return UserScope(uuid.UUID(x_test_user))

    app.dependency_overrides[get_session] = test_session
    app.dependency_overrides[get_current_user_scope] = test_scope
    return app


def client_for(app: FastAPI) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


@pytest.fixture
async def client(
    session_factory: async_sessionmaker[AsyncSession],
) -> AsyncIterator[httpx.AsyncClient]:
    async with client_for(build_app(session_factory)) as c:
        yield c


@pytest.fixture
async def user(session_factory: async_sessionmaker[AsyncSession]) -> UserScope:
    return await new_user(session_factory)


@pytest.fixture
async def contact_id(
    session_factory: async_sessionmaker[AsyncSession], user: UserScope
) -> uuid.UUID:
    return await new_contact(session_factory, user)


def as_user(scope: UserScope) -> dict[str, str]:
    return {"X-Test-User": str(scope.user_id)}


def full_update(**overrides: Any) -> dict[str, Any]:
    """A valid ``PreferencesUpdate`` body (the contract example), with overrides."""
    body: dict[str, Any] = {
        "intent": {"mode": "serious", "custom_description": None},
        "dealbreakers": ["smoking"],
        "style": {
            "punctuation": "standard",
            "emoji_frequency": "rare",
            "brevity": "short",
            "humor": "playful",
            "tone": "warm",
            "response_pace": "relaxed",
            "style_samples": ["haha that's amazing, where was that?"],
        },
        "automation": {
            "mode": "assisted",
            "active_hours": {
                "timezone": "America/Chicago",
                "windows": [
                    {"days": ["mon", "tue", "wed", "thu", "fri"], "start": "17:00", "end": "22:30"}
                ],
            },
            "reply_delay_seconds": {"min": 60, "max": 600},
            "daily_send_limit": 30,
            "approval_required_for": ["date_or_commitment", "first_message"],
        },
    }
    body.update(overrides)
    return body
