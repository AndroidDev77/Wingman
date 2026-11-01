from datetime import UTC, datetime

from fastapi.testclient import TestClient

from app.main import create_app


def test_health_returns_ok() -> None:
    client = TestClient(create_app())
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["version"]
    ts = datetime.fromisoformat(body["time"])
    assert ts.tzinfo is not None and ts.utcoffset() == UTC.utcoffset(None)


def test_unknown_route_is_404() -> None:
    client = TestClient(create_app())
    assert client.get("/does-not-exist").status_code == 404


def test_health_rejects_post() -> None:
    client = TestClient(create_app())
    assert client.post("/health").status_code == 405
