# jornadas-service

Spring Boot service that manages the Jornadas catalog for the Registro de Jornadas / Prácticas Profesionales domain.

**Status:** V1 implemented and manually verified end-to-end (publish/edit/cancel, optimistic concurrency, functional rejections, automatic finalization, dead-lettering of malformed commands) against a local Postgres + RabbitMQ. Deliberate V1 simplification: no Transactional Outbox / Consumer Inbox / idempotency enforcement yet — see "Known simplifications" below.

## Owns

- Jornada (type, name, date, schedule, place, total quota, document deadline, description, allowed semesters, status).
- Tipo de jornada catalog and its allowed semesters.
- Automatic finalization of a Jornada whose date has passed.

## Does Not Own

- Registro, waitlist, or real-time available quota — that is `registros`.
- Vigencia of a student's general documentation — that is `cumplimiento-documental`.
- Attendance, incidents, or constancias — that is `asistencia-constancias` (deferred to Fase 2).
- Personas, credentials, roles, academic enrollment, or notifications — the shared services (`users-service`, `authentication-service`, `authorization-service`, `academic-service`, `communications-service`).

## Operational endpoints

- `GET /health`, `/health/liveness`, `/health/readiness`, `/health/startup`
- `GET /metrics` and `GET /info`

Business operations are durable RabbitMQ commands on `commands.jornadas`: `PublicarJornada`, `EditarJornada`, `CancelarJornada`, `ConfigurarTipoJornada`. The service owns a PostgreSQL/Flyway schema (`src/main/resources/db/migration/V1__jornadas_core.sql`).

`jornadas` publishes confirmed facts (`JornadaPublicada`, `JornadaEditada`, `JornadaCancelada`, `JornadaFinalizada`) on `platform.events`; it does not expose a business REST API — client queries are served from the BFF's `read_models`, populated by those events. The machine-readable contract is [`docs/asyncapi.yaml`](docs/asyncapi.yaml); the operational-only HTTP surface is [`docs/openapi.yaml`](docs/openapi.yaml).

The finalization of a Jornada whose date has passed (`JornadaFinalizada`) is driven by `JornadaFinalizacionWorker`, a scheduled worker (`estoma.jornadas.finalizacion.fixed-delay`, default 1 minute) — not an on-read calculation. See the normative decision in `estomatologia-platform-docs` `03-services/domains/practicas-profesionales/jornadas/events.md` §4.

## Known simplifications (V1, to harden before this goes beyond a demo)

- **No Transactional Outbox.** `ResultAndEventPublisher` publishes directly to RabbitMQ inside the same method that commits the DB write, not via an Outbox relay. A crash between the DB commit and the publish can lose a Result/Event. Every other service in this repo (see `academic-service`) uses Outbox; `jornadas` should converge to that pattern before Fase 2.
- **No Consumer Inbox / idempotency records**, even though the schema already has `outbox_messages`, `processed_messages`, and `idempotency_records` tables (`V1__jornadas_core.sql`) sized for it. A redelivered `PublicarJornada` currently creates a second Jornada instead of being deduplicated by `idempotencyKey`.
- **No retry tiers.** `AmqpTopologyConfig` declares only `commands.jornadas` + its DLQ (no 5s/30s/300s retry queues like `users-service`). A technically-failed command (DB down, etc.) is answered with a `FAILED` Result immediately, not retried.
- **`events.jornadas` is not declared or consumed.** The projections it would feed (`alumno_documentacion_general_proyeccion`, `alumno_semestre_proyeccion` — already in the schema) are unused; they only matter once BFF-facing queries are built, which is out of scope for this iteration.

## Local Development

```bash
docker compose up -d
JORNADAS_DB_URL=jdbc:postgresql://localhost:5433/jornadas_db ./mvnw spring-boot:run
```

Postgres is published on host port **5433**, not 5432 — see the comment in `docker-compose.yml`. If `8080` is also taken on your machine, add `SERVER_PORT=8091` (or any free port) to the command above.

`DemoObserverTopologyConfig` automatically declares two throwaway queues on startup — `demo.results.bff` and `demo.events.jornadas` — bound to `platform.results`/`platform.events` exactly like the real BFF would. No manual queue/binding setup needed: open `http://localhost:15672` (guest/guest) → Queues, they are already there.

To see the whole service work end to end with one command (resets `jornadas_db` first, so it's repeatable):

```bash
python3 scripts/demo.py
```

It publishes `ConfigurarTipoJornada` → `PublicarJornada` → `EditarJornada` → a deliberately stale `EditarJornada` (to show the `JORNADAS.CONFLICTO_VERSION` rejection) → `CancelarJornada`, printing every Result and Event as it arrives, then the final row in `jornada`. Open the RabbitMQ UI while it runs to watch the messages land in `demo.results.bff` / `demo.events.jornadas` live.

Normative domain design lives in `estomatologia-platform-docs` — `03-services/domains/practicas-profesionales/jornadas/` and `02-architecture/domains/practicas-profesionales-architecture.md` (separate repository, not a subdirectory of this one).
