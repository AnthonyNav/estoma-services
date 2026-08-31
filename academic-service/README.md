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

Business operations are durable RabbitMQ commands on `commands.academic`. The service owns a PostgreSQL/Flyway schema, Consumer Inbox, idempotency records, transactional Outbox, confirmed relay, external status/authorization projections, and the V1 `PRACTICAS_PROFESIONALES` eligibility result.

The service also owns the durable `student-import-process` module for `FULL` academic imports: frozen object references and SHA-256 integrity checks, bounded streaming XLSX validation, authoritative Person resolution, deterministic Plan Items, coordinated `SYSTEM/academic` Commands, Result correlation and retry fencing, controlled `ResumeStudentImport` recovery, audited expected-version rebases, local Academic effects/eligibility, and atomic `COMPLETED + READY` finalization.

PR3 adds the durable recovery boundary: `queries.academic` serves eligibility
snapshots for Authorization, appointment configuration snapshots, and the
active Academic Period snapshot for Communications. `results.academic` dispatches
import Results separately from Authorization recovery Results, and
`projection_recovery_jobs` persists entity/full rebuild progress, cursors,
attempts and query correlation. The machine-readable contract is
[`docs/asyncapi.yaml`](docs/asyncapi.yaml); the normative decisions are in
`pr3-contract-resolution.md` in the shared platform documentation.

Cross-service receiver adaptations in Authentication/Authorization/BFF and full platform E2E validation remain integration work outside this Academic PR3 boundary. In particular, Authorization must implement the compatible `queries.authorization` consumer and durable Results before projection recovery can converge end to end. Academic emits the canonical contracts documented by `estomatologia-platform-docs/main` rather than preserving legacy receiver identities.

The physical/operational decisions that previously blocked PR2 are closed in [`docs/student-import-pr2-open-questions.md`](docs/student-import-pr2-open-questions.md). Despite the historical filename, that file is now the approved implementation-decision record and remains subordinate to the shared platform contracts.
