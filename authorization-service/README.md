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
- RabbitMQ, wire DTOs, recovery orchestration and snapshot Queries remain PR3.

## PR2 Application Behavior

- Six durable Commands are dispatched directly through Application without a broker.
- BFF/Academic authority, optimistic locking, terminal Results and Event intents are persisted atomically.
- Projection Events are monotonic and support Inbox `APPLIED`/`DEFERRED`.
- Academic `importExecution` is retained in deferred command payloads.
- Convergent `PRACTICAS_PROFESIONALES` provisioning is idempotent and race-safe.

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
