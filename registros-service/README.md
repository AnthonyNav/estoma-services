# registros-service

Spring Boot service that manages the pre-registro lifecycle for Registro de Jornadas / Prácticas Profesionales.

**Status:** V1 implemented and manually verified end-to-end (crear/confirmar/rechazar/cancelar, cupo en tiempo real, cascada por Jornada cancelada, cierre automático a Completado cuando la Jornada finaliza, rechazo funcional por falta de cupo/semestre no permitido) against a local Postgres + the shared platform RabbitMQ. Deliberate V1 simplifications — see "Known simplifications" below.

## Owns

- Registro (jornadaId, alumnoId, estado, motivo de rechazo).
- Cupo disponible en tiempo real por Jornada (via a local projection — never jornadas-service's own data).

## Does Not Own

- El catálogo de Jornadas (tipo, nombre, cupo total, fecha) — eso es `jornadas`; solo se proyecta localmente lo estrictamente necesario para validar un pre-registro.
- Vigencia de documentación general del Alumno — eso sería `cumplimiento-documental` (no existe todavía).
- Semestre vigente del Alumno — eso sería una proyección de `academic-service` (no implementada en V1; ver simplificaciones).
- Personas, credentials, roles, notificaciones — los servicios compartidos.

## Operational endpoints

- `GET /health`, `/health/liveness`, `/health/readiness`, `/health/startup`
- `GET /metrics` and `GET /info`

Business operations are durable RabbitMQ commands on `commands.registros`: `CrearRegistro`, `ConfirmarRegistro`, `RechazarRegistro`, `CancelarRegistroPorAlumno`. The service owns a PostgreSQL/Flyway schema (`src/main/resources/db/migration/V1__registros_core.sql`) and consumes jornadas-service's domain events (`events.registros`, bound to `platform.events` with routing key `jornadas.#`) to keep its local Jornada projection (cupo, semestres permitidos, estado) current — it never joins across service databases.

`registros` publishes confirmed facts (`RegistroCreado`, `RegistroConfirmado`, `RegistroRechazado`, `RegistroCanceladoPorAlumno`, `RegistroCanceladoPorJornadaCancelada`, `RegistroCompletado`) on `platform.events`. The machine-readable contract is [`docs/asyncapi.yaml`](docs/asyncapi.yaml); the operational-only HTTP surface is [`docs/openapi.yaml`](docs/openapi.yaml).

## Known simplifications (V1, to harden before this goes beyond a demo)

- **No lista de espera.** `CrearRegistro` is rejected outright (`REGISTROS.SIN_CUPO_DISPONIBLE`) when there is no cupo, instead of queueing the Alumno and auto-assigning later. `RegistroIngresoListaEspera`/`RegistroAsignadoDesdeListaEspera` are not implemented.
- **No `cumplimiento-documental` validation.** That service does not exist yet — `CrearRegistro` does not check the Alumno's documentación general vigente.
- **No `academic-service` semester projection.** `semestreAlumno` travels explicit in the `CrearRegistro` payload instead of being resolved from a local projection fed by `StudentSemesterChanged` — the BFF is expected to fill it from the session in the meantime.
- **No Transactional Outbox / Consumer Inbox / idempotency records**, same simplification as `jornadas-service` — the schema has `outbox_messages`/`processed_messages`/`idempotency_records` sized for it, unused in V1.

## Local Development

```bash
# RabbitMQ is shared platform infra — start it via jornadas-service first if it's not already up:
#   (cd ../jornadas-service && docker compose up -d)
docker compose up -d
REGISTROS_DB_URL=jdbc:postgresql://localhost:5434/registros_db SERVER_PORT=8092 ./mvnw spring-boot:run
```

Postgres is published on host port **5434** (jornadas-service already took 5433; a pre-existing Homebrew `postgresql@14` owns 5432 on at least one dev machine in this project — see jornadas-service/docker-compose.yml). Pick a free `SERVER_PORT` if `8080`/`8091` are already taken.

`DemoObserverTopologyConfig` automatically declares `demo.results.bff` (shared with jornadas-service — RabbitMQ's queue declaration is idempotent) and `demo.events.registros` on startup — open `http://localhost:15672` (guest/guest) and they're already there, no manual binding needed.

```bash
python3 scripts/demo.py
```

Publishes, in order: `ConfigurarTipoJornada` + `PublicarJornada` against **jornadas-service** (must be running) to get a real Jornada to register against, then `CrearRegistro` → `ConfirmarRegistro` on **registros-service**, plus a second `CrearRegistro` that deliberately exhausts cupo to show `REGISTROS.SIN_CUPO_DISPONIBLE`, and cancels the Jornada to show the cascade (`RegistroCanceladoPorJornadaCancelada`).

Normative domain design lives in `estomatologia-platform-docs` — `03-services/domains/practicas-profesionales/registros/` and `02-architecture/domains/practicas-profesionales-architecture.md` (separate repository, not a subdirectory of this one).
