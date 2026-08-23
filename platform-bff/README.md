# platform-bff

Spring Boot service that provides the client-facing API and composition boundary for Estoma applications.

## Owns

- Client-oriented HTTP request/response contracts.
- Durable client operation tracking and edge idempotency.
- Local CQRS/read-model composition for screens.
- Translation from client intent to owner Commands or Authentication Security RPC.
- Local JWT validation and derived authorization/session-revocation projections.

## Does Not Own

- Business rules owned by domain services.
- Service databases outside `bff-db`.
- Authentication credential/session ownership.
- Authorization policy ownership.
- Hidden multi-owner sagas or Process Manager behavior.
- Direct HTTP/gRPC business calls to microservices.

Internal V1 business communication uses the canonical RabbitMQ contracts. Normal client reads are served from BFF-local typed read models; the BFF does not synchronously query owners per screen request.

## Implementation docs

- [`docs/system_contract.md`](docs/system_contract.md) — local implementation contract.
- [`docs/execution_plan.md`](docs/execution_plan.md) — PR1–PR6 implementation sequence and gates.
- [`docs/pr2_correction_gate.md`](docs/pr2_correction_gate.md) — mandatory corrections/rebase gate for the currently stacked PR2 before it can be reviewed for merge.
- [`docs/pr2_retention_addendum.md`](docs/pr2_retention_addendum.md) — mandatory PR2 correction for idempotency retention: `expiresAt` starts at terminal state, not at submit.
- [`docs/pr2_owner_contract_addendum.md`](docs/pr2_owner_contract_addendum.md) — owner-precedence correction for `CancelAppointmentTimeSlot` and its PR2 registry/wire gate.

## Initial operational endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
