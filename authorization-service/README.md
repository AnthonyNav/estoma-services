# authorization-service

Spring Boot service that owns Authorization catalogs, one role per account and
one `SystemAccess` per account/system. It does not validate passwords, manage
sessions or expose a business HTTP API.

## PR1 Foundation

- Java 25 / Spring Boot 4.1.
- PostgreSQL with Flyway and `JdbcClient`; JPA/Hibernate is not used.
- Hexagonal boundaries enforced with ArchUnit.
- V1 catalogs and role/system/permission seed are deterministic.
- Account roles, system accesses, external projections and technical Inbox,
  idempotency, Outbox, audit and recovery tables are present.

## PR2 Application Behavior And Recovery

- Six durable Commands are implemented through broker-agnostic Application use cases.
- BFF/Academic authority, optimistic locking, terminal Results and Event intents are persisted atomically.
- Exact delivery dedupe (`messageId`) is separate from functional idempotency (`idempotencyKey`).
- Academic Results preserve import-plan correlation and canonical aggregate metadata.
- Projection Events are monotonic and support durable Inbox `APPLIED`/`DEFERRED` semantics.
- Academic `importExecution` is retained in deferred Commands and stale generations cannot mutate state.
- Deferred Event replay uses lease/fencing; deferred Command wake-up is transactionally serialized.
- Convergent `PRACTICAS_PROFESIONALES` provisioning is idempotent, eligibility-aware, race-safe and fail-closed on untrusted projections.
- ENTITY/FULL projection recovery, authoritative snapshots, race-safe `NOT_FOUND`, pagination and wake-up of deferred work are implemented without a broker.
- Terminal technical failures can produce durable `FAILED`; retryable Spring/JDBC failures remain visible to the PR3 transport retry policy through a hexagonal classifier port.
- Authorization exposes broker-agnostic `GetAccountAuthorizationSnapshot` and paginated snapshot Query use cases.

The PR2 audit register is in [`docs/implementation_gaps.md`](docs/implementation_gaps.md).
All recorded `BLOCKER` and `HIGH` findings are resolved and covered by regression tests.

## Deferred To PR3

- RabbitMQ topology/listeners/publishers.
- Executable wire DTOs, AsyncAPI and JSON Schemas.
- Outbox Relay publisher confirms, consumer retry/DLQ and broker integration tests.
- Mapping the already implemented Application Commands/Events/Queries/Results to the platform messaging contracts.

## Operational Endpoints

- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`

All other HTTP routes are denied. Authorization has no `ValidateAccess` endpoint.

## Local Development

```bash
docker compose up -d
./mvnw -B clean verify
```

Runtime and migration database credentials can be supplied with:

- `AUTHORIZATION_DB_URL`
- `AUTHORIZATION_DB_RUNTIME_USER`
- `AUTHORIZATION_DB_RUNTIME_PASSWORD`
- `AUTHORIZATION_DB_MIGRATION_USER`
- `AUTHORIZATION_DB_MIGRATION_PASSWORD`

The runtime role name is part of the database grant contract. Rotate its
password through the deployment secret; if the role name changes, apply the
runtime grants before starting the service. The fallback credentials above are
for local development only and must be overridden in deployed environments.
