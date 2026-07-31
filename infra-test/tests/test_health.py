import os

from fastapi.testclient import TestClient

from app.main import APP_NAME, APP_VERSION, app

os.environ["API_KEY"] = "test-api-key"

client = TestClient(app)


def test_health() -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": APP_NAME}


def test_version() -> None:
    response = client.get("/version", headers={"X-API-Key": "test-api-key"})

    assert response.status_code == 200
    assert response.json() == {"service": APP_NAME, "version": APP_VERSION}


def test_version_requires_api_key() -> None:
    response = client.get("/version")

    assert response.status_code == 401
    assert response.json() == {"detail": "Invalid API key"}


def test_version_rejects_invalid_api_key() -> None:
    response = client.get("/version", headers={"X-API-Key": "wrong-key"})

    assert response.status_code == 401
    assert response.json() == {"detail": "Invalid API key"}


def test_openapi_is_available() -> None:
    response = client.get("/openapi.json")

    assert response.status_code == 200
    assert response.json()["info"]["title"] == "Estoma Infra Test"
