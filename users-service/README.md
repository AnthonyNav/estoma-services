# users-service

Spring Boot service that manages personal identity for people in the Estoma platform.

## Owns

- Names and last names.
- Institutional email.
- Optional phone.
- Global person identifier.
- Global person status.
- Relationship between a person and multiple accounts.

## Does Not Own

- Passwords.
- Sessions.
- Roles.
- Student identifier, semester, or academic status.

## Initial Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
