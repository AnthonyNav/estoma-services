# communications-service

Spring Boot service that delivers platform communications (internal inbox and email) requested by other domains.

## Current Scope

Runtime infrastructure is in place: typed configuration, an HTTP surface locked down to operational endpoints only, and structured logging. No business logic, no database schema beyond the empty Flyway location, and no messaging consumers are implemented yet — see `docs/execution_plan.md`.

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

- The `Communication` aggregate: origin, recipient, system, type, priority, and status.
- Internal inbox messages and visits.
- Email deliveries and their delivery attempts.
- Provider selection, fallback, and retry orchestration for email.
- A `recipient_projection` derived from Person and Account events.

## Does Not Own

- Business decisions that trigger a communication (the requesting domain decides *when*; this service decides *how* to deliver).
- Identity, account, or session data.

## Operational Endpoints

Served by Actuator health groups, not a custom controller (`management.endpoints.web.base-path` is `/`, so these are the real paths, not `/actuator/*`):

- `GET /health`
- `GET /health/startup`
- `GET /health/readiness`
- `GET /health/liveness`
- `GET /metrics`
- `GET /info`

Everything else is denied by `HttpSecurityConfig`.
