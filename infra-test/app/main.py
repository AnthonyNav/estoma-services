import os

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.security import APIKeyHeader

APP_NAME = "infra-test"
APP_VERSION = "0.2.0"
API_KEY_HEADER = "X-API-Key"

api_key_header = APIKeyHeader(name=API_KEY_HEADER, auto_error=False)

app = FastAPI(
    title="Estoma Infra Test",
    description="Minimal service to validate Estoma platform delivery.",
    version=APP_VERSION,
)


def require_api_key(api_key: str | None = Depends(api_key_header)) -> None:
    expected_api_key = os.getenv("API_KEY")
    if not expected_api_key:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="API key is not configured",
        )

    if api_key != expected_api_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid API key",
        )


@app.get("/health", tags=["system"])
def health() -> dict[str, str]:
    return {"status": "ok", "service": APP_NAME}


@app.get("/version", tags=["system"], dependencies=[Depends(require_api_key)])
def version() -> dict[str, str]:
    return {"service": APP_NAME, "version": APP_VERSION}
