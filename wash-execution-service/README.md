# wash-execution-service

Spring Boot service that manages the physical operation of Lavado Ultrasonico appointments.

## Owns

- Student arrival.
- Entry validation result.
- Physical resource assignment and reassignment.
- Operational resource unavailability and restoration.
- Exit submission and completion evidence.
- Cleaning periods derived from IMMUNOCOMPROMISED executions.

## Does Not Own

- Appointment booking or `AppointmentTimeSlot` ownership.
- Schedule and administrative resource configuration.
- Student point balances.
- Identity, roles, or system access.
- Communications delivery.

## Implementation Contracts

Read these local implementation contracts in this order:

1. `docs/pr0_contract_resolution.md` — mandatory PR0 resolution that closes pre-implementation ambiguities and unblocks PR1.
2. `docs/system_contract.md` — V1 implementation boundary and invariants.
3. `docs/execution_plan.md` — PR1–PR4 implementation sequence and Definition of Done.

Canonical platform documentation in `AnthonyNav/estomatologia-platform-docs` always wins if these local implementation notes drift. The PR0 resolution specifically fixes the physical Authorization projection names, durable `DEFERRED/APPLIED` Inbox semantics, the `queries.wash-execution` recovery surface, and same-version Event handling required by Appointments.

## Current Scaffold Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`

The V1 target keeps Actuator as the operational HTTP surface and does not expose business REST endpoints. Business communication is introduced through the durable RabbitMQ contracts described in the implementation plan.

## Implementation Gate

PR0 is considered resolved when the canonical documentation PR and this repository PR are merged. After that, PR1 (Domain + Persistence + Concurrency) is unblocked; no Java behavior is implemented by PR0 itself.
