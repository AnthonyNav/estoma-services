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
- [`docs/admin_http_contract.md`](docs/admin_http_contract.md) — PR6 concrete `/api/v1/admin/**` route-to-owner matrix.
- [`docs/pr2_correction_gate.md`](docs/pr2_correction_gate.md) — mandatory corrections/rebase gate identified before PR2 reconstruction.
- [`docs/pr2_retention_addendum.md`](docs/pr2_retention_addendum.md) — PR2 idempotency retention correction: `expiresAt` starts at terminal state, not at submit.
- [`docs/pr2_owner_contract_addendum.md`](docs/pr2_owner_contract_addendum.md) — owner-precedence correction for `CancelAppointmentTimeSlot` and its registry/wire gate.
- [`docs/pr2_review_resolution.md`](docs/pr2_review_resolution.md) — applied PR2 corrections, regression coverage and remaining gates.
- [`docs/pr3_review_resolution.md`](docs/pr3_review_resolution.md) — Security RPC, refresh, JWT, revocation and projection-event corrections applied for PR3.

## Initial operational endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
