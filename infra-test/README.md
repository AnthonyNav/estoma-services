# infra-test

Minimal Spring Boot microservice used to validate the Estoma platform pipeline.

## Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`

`GET /version` requires the `X-API-Key` header and the `API_KEY` environment variable.

## Local Run

```bash
API_KEY=local-key ./mvnw spring-boot:run
```

## Test

```bash
./mvnw clean verify
```

## Container

```bash
docker build -t infra-test .
docker run --rm -e API_KEY=local-key -p 8080:8080 infra-test
```
