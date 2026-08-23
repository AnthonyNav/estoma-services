# appointments-service

Spring Boot service that owns the concrete Lavado Ultrasonico appointment agenda and the official lifecycle of appointments.

## Status

V1 implementation contract prepared. Production business behavior is not implemented yet.

Local implementation documents:

- `docs/system_contract.md` — executable local contract and architecture constraints.
- `docs/execution_plan.md` — PR-by-PR implementation strategy and acceptance criteria.

Canonical product/domain/architecture documentation lives in `AnthonyNav/estomatologia-platform-docs/main` and always prevails if local documentation drifts.

## Logical Component ID

```text
appointments
```

RabbitMQ contract namespaces use `appointments`, never `appointments-service`.

## Owns

```text
AppointmentTimeSlot
Appointment
ExceptionalAppointmentAuthorization
```

It also maintains local technical projections needed to make decisions without synchronous service-to-service calls.

## Does Not Own

- Wash Administration calendars, schedules, closures, cabins or tanks.
- Physical arrival, resource assignment, failures, reassignment, exit or cleaning.
- Academic information as source of truth.
- Roles or SystemAccess as source of truth.
- Authentication/session state.
- Communications delivery.

## Important V1 Rules

- Appointments are not edited or rescheduled; change means cancel + create a new appointment.
- `Tank.configuredCapacity` is the physical configured capacity source; `Cabin` has no configured capacity of its own.
- Points are derived from appointment outcomes; there is no independent persisted point balance.
- Only `COMPLETED` counts toward the daily completed-appointment limit.
- A consumed exceptional authorization is restored after `CANCELLED + CLINIC`, but not after `CANCELLED + STUDENT`, `MISSED` or `ENTRY_REJECTED`.
- Administrative changes can recalculate future materialized slots and cancel eligible `SCHEDULED` appointments as `CLINIC / CAPACITY_LOSS`.
- Entry-authorized and `IN_PROGRESS` appointments are not retroactively cancelled by administrative schedule changes.
- The QR is a protected representation of `appointmentId`, not a domain entity or entry authorization.
- Internal business communication is RabbitMQ-based; no business REST/gRPC shortcuts between services.

## Target Architecture

Hexagonal architecture:

```text
com.estoma.appointments
├── domain
├── application
├── infrastructure
└── contracts
```

The domain must remain independent from Spring, JDBC, RabbitMQ and wire DTOs. Architecture boundaries are enforced with ArchUnit.

## Implementation Order

```text
PR1 → Domain + Persistence + Concurrency Foundation
PR2 → Complete Application Behavior Without RabbitMQ
PR3 → Wire Contracts + RabbitMQ + Projection Recovery
PR4 → Bounded-context E2E + Failure Hardening
```

See `docs/execution_plan.md` for the full gates and Definition of Done.

## Operational Endpoints

The scaffold currently exposes temporary health/version endpoints. The target is Spring Actuator only:

```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
/actuator/prometheus
```

No HTTP business endpoints are part of V1.
