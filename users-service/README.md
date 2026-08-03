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

## Business interface

This service exposes no business REST API. Every business operation
(create/update/activate/deactivate a Person) arrives as a durable command
over RabbitMQ; every confirmed fact leaves as a durable result or event
over RabbitMQ. See `docs/system-contract.md` and `docs/asyncapi.yaml` for
the full contract.

## Operational endpoints

- `GET /health`
- `GET /health/startup`
- `GET /health/readiness`
- `GET /health/liveness`
- `GET /metrics` (Prometheus exposition format)
- `GET /info`

Everything else is denied by `HttpSecurityConfig`. See
`docs/openapi.yaml` for the operational surface and
`docs/system-contract.md` §11-§12 for the full observability/security
contract.
