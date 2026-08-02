# wash-execution-service

Spring Boot service that manages the physical operation of Lavado Ultrasonico appointments.

## Owns

- Student arrival.
- Physical resource assignment.
- Entry and exit state.
- Operational failures and incidents.
- Appointment completion evidence.

## Does Not Own

- Appointment booking.
- Schedule configuration.
- Student point balances.

## Initial Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
