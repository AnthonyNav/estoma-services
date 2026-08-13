# wash-administration-service

Spring Boot service that manages configuration for the Lavado Ultrasonico operation.

## Owns

- Service calendars and validity periods.
- Schedules.
- Administrative closures.
- Cabins, tanks, and fixed resource-priority rules.
- Configured capacity for each tank.

## Does Not Own

- Academic periods or holidays owned by another service.
- Concrete appointment time slots.
- Appointment booking.
- Student point balances.
- Arrival, assignment, or exit execution state.

## Implementation Contracts

- `docs/system_contract.md` defines the service implementation boundary.
- `docs/execution_plan.md` defines the three implementation PRs.
- `docs/pr1_contract_alignment.md` records the canonical-doc reconciliation performed while closing PR1.

Canonical platform documentation always wins if these local implementation notes drift.

## Operational Endpoints

- `GET /health`
- `GET /health/startup`
- `GET /health/readiness`
- `GET /health/liveness`
- `GET /info`
- `GET /metrics`

The service does not expose business REST endpoints. Business operations are
introduced through the durable messaging contract in PR3.
