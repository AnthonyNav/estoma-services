from fastapi import FastAPI

APP_NAME = "deploy-test"
APP_VERSION = "0.1.1"

app = FastAPI(
    title="Estoma Deploy Test",
    description="Minimal service to validate path-scoped CI/CD automation.",
    version=APP_VERSION,
)


@app.get("/health", tags=["system"])
def health() -> dict[str, str]:
    return {"status": "ok", "service": APP_NAME}


@app.get("/version", tags=["system"])
def version() -> dict[str, str]:
    return {"service": APP_NAME, "version": APP_VERSION}
