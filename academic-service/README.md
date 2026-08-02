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

## Initial Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
