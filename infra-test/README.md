# infra-test

Minimal FastAPI microservice used to validate the Estoma platform pipeline.

## Endpoints

- `GET /health`
- `GET /version`
- `GET /docs`
- `GET /openapi.json`

## Local Run

```bash
python -m venv .venv
source .venv/bin/activate
pip install -e '.[test]'
uvicorn app.main:app --reload --host 0.0.0.0 --port 8080
```

## Test

```bash
pytest
```

## Container

```bash
docker build -t infra-test ./infra-test
docker run --rm -p 8080:8080 infra-test
```
