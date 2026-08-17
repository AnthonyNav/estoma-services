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

- `docs/system_contract.md` defines the V1 implementation boundary and invariants for this service.
- `docs/execution_plan.md` defines the four implementation PRs and final Definition of Done.

Canonical platform documentation in `AnthonyNav/estomatologia-platform-docs` always wins if these local implementation notes drift.

## Current Scaffold Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`

The V1 target keeps Actuator as the operational HTTP surface and does not expose business REST endpoints. Business communication is introduced through the durable RabbitMQ contracts described in the implementation plan.
