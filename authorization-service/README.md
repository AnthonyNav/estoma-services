# authorization-service

Spring Boot service that decides which systems and actions each account can access.

## Owns

- Systems.
- Roles.
- Permissions.
- Role assignment to accounts.
- Access to Lavado Ultrasonico and Practicas Profesionales.
- Access activation, suspension, and revocation.

## Does Not Own

- Password validation.
- Sessions.
- Personal identity data.

## Initial Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
