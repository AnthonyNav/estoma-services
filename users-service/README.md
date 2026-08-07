# users-service

Spring Boot service that manages personal identity for people in the Estoma platform.

## Owns

- Names and last names.
- Institutional email.
- Optional phone.
- Global person identifier.
- Global person status.
- Relationship between a person and multiple accounts.

## Does Not Own

- Passwords.
- Sessions.
- Roles.
- Student identifier, semester, or academic status.

## Business interface

This service exposes no business REST API. Every business operation
(create/update/activate/deactivate a Person) arrives as a durable command
over RabbitMQ; every confirmed fact leaves as a durable result or event
over RabbitMQ. See `docs/system-contract.md` and `docs/asyncapi.yaml` for
the full contract.

## Operational endpoints

- `GET /health`
- `GET /health/startup`
- `GET /health/readiness`
- `GET /health/liveness`
- `GET /metrics` (Prometheus exposition format)
- `GET /info`

Everything else is denied by `HttpSecurityConfig`. See
`docs/openapi.yaml` for the operational surface and
`docs/system-contract.md` §11-§12 for the full observability/security
contract.

## Local development

`docker-compose.yml` starts a local PostgreSQL and RabbitMQ that already
match this service's own defaults, so no environment variables are
needed for local development:

```bash
docker compose up -d
./mvnw spring-boot:run
```

The service self-provisions its own AMQP topology on startup
(`estoma.messaging.topology-declare` defaults to `true`) — commands,
DLQ, and retry tiers all appear automatically. Verify:

```bash
curl localhost:8080/health/readiness
curl localhost:8080/metrics | grep estoma_outbox
```

RabbitMQ's management UI is at `http://localhost:15672` (guest/guest).

Run the tests (unit tests only, fast, no Docker required beyond the
JVM itself):

```bash
./mvnw clean test
```

Run everything, including the Testcontainers-backed integration tests
(needs Docker; spins up its own throwaway PostgreSQL/RabbitMQ
containers, independent of `docker-compose.yml`):

```bash
./mvnw clean verify
```

Build the container image:

```bash
docker build -t users-service .
```

## Configuration

All configuration is environment-variable driven with working local
defaults (`src/main/resources/application.yml`); nothing below is
required to run the service locally.

| Variable | Default | Purpose |
|---|---|---|
| `USERS_DB_URL` | `jdbc:postgresql://localhost:5432/users_db` | Datasource and Flyway JDBC URL |
| `USERS_DB_RUNTIME_USER` / `USERS_DB_RUNTIME_PASSWORD` | `users_service` / `users_service` | Runtime DB role (least privilege) |
| `USERS_DB_MIGRATION_USER` / `USERS_DB_MIGRATION_PASSWORD` | same as runtime | Separate migration DB role (security-observability.md:235) |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | AMQP broker |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `guest` / `guest` | Per-component AMQP credentials |
| `RABBITMQ_VHOST` | `/` | AMQP virtual host |
| `ESTOMA_MESSAGING_TOPOLOGY_DECLARE` | `true` | `false` in staging/prod, where `estoma-infra` owns the topology via GitOps |
| `ESTOMA_ENVIRONMENT` | `local` | Deployment environment name, in every structured log line |

See `docs/system-contract.md` §9-§12 for the full rationale behind each
of these.
