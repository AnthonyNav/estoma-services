# academic-service

Spring Boot service that manages student academic information.

## Owns

- Student identifier.
- Semester.
- Academic periods.
- NRC or groups.
- Student-to-NRC relationship.
- Academic situation updates.

## Does Not Own

- Personal identity data.
- Account credentials.
- Roles and permissions.

## Operational endpoints

- `GET /health`, `/health/liveness`, `/health/readiness`, `/health/startup`
- `GET /metrics` and `GET /info`

Business operations are durable RabbitMQ commands on `commands.academic`. The service owns a PostgreSQL/Flyway schema, Consumer Inbox, idempotency records, transactional Outbox, confirmed relay, external status/authorization projections, and the V1 `PRACTICAS_PROFESIONALES` eligibility result. It also persists the `student-import-process` acceptance state and frozen file reference for `StartStudentImport`; XLSX validation and distributed plan application are not implemented yet.
