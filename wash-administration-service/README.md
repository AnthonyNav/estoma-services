# wash-administration-service

Spring Boot service that manages configuration for the Lavado Ultrasonico operation.

## Owns

- Schedules.
- Time slots.
- Resource capacity.
- Valid service days and holidays.
- Configuration rules for appointment availability.

## Does Not Own

- Appointment booking.
- Student point balances.
- Arrival, assignment, or exit execution state.

## Initial Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
