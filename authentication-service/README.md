# authentication-service

Spring Boot service that manages access accounts, credentials, and sessions.

## Current Scope

This service is in scaffold stage. It only exposes operational endpoints and documentation for the implementation contract. Authentication flows, credentials, sessions, messaging consumers, database migrations, and token handling are not implemented yet.

## Service Docs

- `docs/system_contract.md`: implementation contract for the service boundary.
- `docs/execution_plan.md`: phased PR plan for safe delivery.
- `docs/pending_decisions.md`: decisions that must remain explicit before implementation.

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
- `GET /health/startup`
- `GET /health/readiness`
- `GET /health/liveness`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
