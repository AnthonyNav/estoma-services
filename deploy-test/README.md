# deploy-test

Minimal Spring Boot service used to validate path-scoped CI/CD and GitOps deployment automation.

## Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`

## Local Run

```bash
../mvnw spring-boot:run -f pom.xml
```

## Test

```bash
../mvnw clean verify -f pom.xml
```

## Container

```bash
docker build -f deploy-test/Dockerfile -t deploy-test .
docker run --rm -p 8080:8080 deploy-test
```
