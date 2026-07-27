from fastapi import FastAPI

APP_NAME = "infra-test"
APP_VERSION = "0.1.0"

app = FastAPI(
    title="Estoma Infra Test",
    description="Minimal service to validate Estoma platform delivery.",
    version=APP_VERSION,
)


@app.get("/health", tags=["system"])
def health() -> dict[str, str]:
    return {"status": "ok", "service": APP_NAME}


@app.get("/version", tags=["system"])
def version() -> dict[str, str]:
    return {"service": APP_NAME, "version": APP_VERSION}
