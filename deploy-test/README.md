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
./mvnw spring-boot:run
```

## Test

```bash
./mvnw clean verify
```

## Container

```bash
docker build -t deploy-test .
docker run --rm -p 8080:8080 deploy-test
```

## CI/CD

Trusted pushes to `develop` publish an immutable GHCR image and open a GitOps
pull request that updates the development deployment through Argo CD.
