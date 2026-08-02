# platform-bff

Spring Boot service that provides the client-facing API layer for Estoma applications.

## Owns

- Client-oriented request and response shapes.
- Cross-service composition for screens and workflows.
- API routing from clients to internal services.
- Translation between client contracts and service contracts.

## Does Not Own

- Business rules owned by domain services.
- Service databases.
- Authentication credential storage.
- Authorization policy ownership.

## Initial Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
