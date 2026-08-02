# appointments-service

Spring Boot service that manages Lavado Ultrasonico appointment lifecycle before execution.

## Owns

- Appointment creation.
- Appointment rescheduling and cancellation.
- Availability calculation using wash administration configuration.
- QR generation and validation state.
- Student point balances and deductions.

## Does Not Own

- Resource configuration.
- Physical arrival, assignment, failures, or exit.
- Authentication or authorization decisions.

## Initial Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
