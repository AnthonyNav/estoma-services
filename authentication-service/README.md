# authentication-service

Spring Boot service that manages access accounts, credentials, and sessions.

## Current Scope

Runtime infrastructure and database schema are in place: typed configuration, an HTTP surface locked down to operational endpoints only, structured logging, and the full Flyway-managed schema. Authentication flows, credentials, sessions, and messaging consumers are not implemented yet — see `docs/execution_plan.md`.

## Service Docs

- `docs/system_contract.md`: implementation contract for the service boundary.
- `docs/execution_plan.md`: phased PR plan for safe delivery.
- `docs/pending_decisions.md`: decisions that must remain explicit before implementation.

## Local Development

```bash
docker compose up -d
./mvnw spring-boot:run
```

No environment variables are required; `docker-compose.yml` matches the defaults baked into `application.yml`.

## Owns

- Access accounts.
- Login by student identifier or username.
- Passwords and temporary passwords.
- Password recovery.
- Sessions and logout.
- Account lock, deactivation, and credential lifecycle.

## Does Not Own

- Personal identity data.
- Roles and permissions.
- Academic status.

## Operational Endpoints

Served by Actuator health groups, not a custom controller (`management.endpoints.web.base-path` is `/`, so these are the real paths, not `/actuator/*`):

- `GET /health`
- `GET /health/startup`
- `GET /health/readiness`
- `GET /health/liveness`
- `GET /metrics`
- `GET /info`

Everything else is denied by `HttpSecurityConfig`.
