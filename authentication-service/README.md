# authentication-service

Spring Boot service that manages access accounts, credentials, and sessions.

## Owns

- Access accounts.
- Login by student identifier or username.
- Passwords and temporary passwords.
- Password recovery.
- Sessions and logout.
- Account lock, deactivation, and credential lifecycle.

## Does Not Own

- Personal identity data.
- Roles and permissions.
- Academic status.

## Initial Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
