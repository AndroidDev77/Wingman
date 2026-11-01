"""With no identity provider configured, the WING-004 auth dependency fails closed.

Also documents ``app.dependency_overrides`` as the testing seam (D-007).
"""

import uuid

from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.db.scope import UserScope
from app.security.deps import get_current_user_scope


def _app() -> FastAPI:
    app = FastAPI()

    @app.get("/protected")
    async def protected(scope: UserScope = Depends(get_current_user_scope)) -> dict[str, str]:  # noqa: B008
        return {"user_id": str(scope.user_id)}

    return app


def test_protected_route_rejected_without_real_auth() -> None:
    resp = TestClient(_app()).get("/protected", headers={"Authorization": "Bearer anything"})
    assert resp.status_code == 401
    assert resp.headers["WWW-Authenticate"] == "Bearer"


def test_dependency_override_is_the_testing_seam() -> None:
    app = _app()
    uid = uuid.uuid4()

    async def fake_scope() -> UserScope:
        return UserScope(user_id=uid)

    app.dependency_overrides[get_current_user_scope] = fake_scope
    assert TestClient(app).get("/protected").json() == {"user_id": str(uid)}
