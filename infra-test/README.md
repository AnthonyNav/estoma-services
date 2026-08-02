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
API_KEY=local-key ../mvnw spring-boot:run -f pom.xml
```

## Test

```bash
../mvnw clean verify -f pom.xml
```

## Container

```bash
docker build -f infra-test/Dockerfile -t infra-test .
docker run --rm -e API_KEY=local-key -p 8080:8080 infra-test
```
