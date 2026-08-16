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
- Projection Events are monotonic and support durable Inbox `APPLIED`/`DEFERRED` semantics.
- Academic `importExecution` is retained in deferred Commands and stale generations cannot mutate state.
- Convergent `PRACTICAS_PROFESIONALES` provisioning is idempotent, race-safe and fail-closed on untrusted projections.
- ENTITY/FULL projection recovery, authoritative snapshots, race-safe `NOT_FOUND`, pagination and wake-up of deferred work are implemented without a broker.
- Authorization exposes broker-agnostic `GetAccountAuthorizationSnapshot` and paginated snapshot Query use cases.

## Deferred To PR3

- RabbitMQ topology/listeners/publishers.
- Executable wire DTOs, AsyncAPI and JSON Schemas.
- Outbox Relay publisher confirms, consumer retry/DLQ and broker integration tests.
- Mapping the already implemented Application Commands/Events/Queries/Results to the platform messaging contracts.

Known implementation gaps are tracked in
[`docs/implementation_gaps.md`](docs/implementation_gaps.md). PR2 remains open
until the `BLOCKER` and `HIGH` items are corrected and revalidated.

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
