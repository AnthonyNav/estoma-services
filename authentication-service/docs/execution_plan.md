# authentication-service Execution Plan

## Goal

Deliver `authentication-service` safely through small pull requests into `develop`. The plan has three phases. Phase 1 closes all technical decisions this service has authority over, so Phase 2 can proceed without intermediate approval gates. Phase 3 is intentionally left as a placeholder for later scoping.

## Branch And PR Policy

- All changes target `develop` through pull requests.
- No direct commits to `develop`.
- Each PR must keep secrets out of Git.
- Each PR must include verification notes.
- Phase 2 may be delivered as several small, independently reviewable commits or sub-PRs on the same branch. Splitting it does not require re-approval of already-resolved decisions, because Phase 1 removes the prerequisite gates that existed in the previous 10-PR plan.

## Phase 1 (PR1): Scaffold And Complete System Contract

Scope:

- Spring Boot service scaffold (already in place).
- `docs/system_contract.md`: service boundary, ownership, persistence boundary, messaging interfaces, mandatory invariants, prohibited behavior, and the full set of v1 technical decisions (security parameters, session/token policy, lockout policy, recovery policy, migration tooling, Outbox operational policy, observability parameter names, internal architecture).
- `docs/execution_plan.md` (this file).
- `docs/pending_decisions.md`: only what genuinely remains open (deferred to Phase 3, or owned by `estoma-infra`/platform team outside this service's control).
- Hexagonal package skeleton (`domain`, `application`, `infrastructure`, alongside the existing `contracts`), documented with `package-info.java`, no business logic yet.
- Canonical enum/constant names for defined commands, RPC operations, events, login types, account statuses, exchanges, and queues (already implemented in `contracts`).
- Operational health endpoints for startup, readiness, and liveness.

Out of scope:

- Authentication logic, password hashing implementation, token generation implementation.
- RabbitMQ consumers or publishers.
- Database migrations with real tables.
- Outbox Relay implementation.

Acceptance criteria:

- Service builds and tests continue passing.
- `system_contract.md` has no open technical decision that blocks starting Phase 2 (all v1 values are concrete, not "TBD").
- `pending_decisions.md` contains only items that are explicitly deferred to Phase 3 or explicitly out of this service's control.
- The hexagonal package skeleton exists and is documented; no premature interfaces or implementations.
- Code only exposes safe foundation constants and operational endpoints.

## Phase 2 (PR2): Build And Finish The Microservice

Single milestone covering all functional behavior. No external approvals block sub-parts of this phase — every technical decision needed to implement it was resolved in Phase 1. Suggested internal order:

1. **Runtime infrastructure shell**: typed configuration (service, database, RabbitMQ, observability, security), startup/readiness/liveness/metrics behavior, safe logging baseline with the redaction rules from `system_contract.md`, Docker/runtime verification.
2. **Database migrations**: Flyway migrations under `src/main/resources/db/migration/` implementing the full schema documented in the platform's `data-model.md` (`accounts`, `account_credentials`, `sessions`, `password_recovery_requests`, `person_status_projection`, `student_number_login_sync_projection`, `security_rpc_idempotency_records`, `processed_messages`, `outbox_messages`), with the constraints and indexes already specified there.
3. **Messaging shell**: durable command consumer for `commands.authentication-service`, ephemeral RPC consumer for `rpc.authentication`, result publisher for `platform.results`, Outbox publisher implementing the relay policy from `system_contract.md`, Consumer Inbox for durable events.
4. **Person projection consumers**: consume `PersonCreated`, `PersonActivated`, `PersonDeactivated`; update `person_status_projection` idempotently. Implemented against the conceptual payload already assumed by `person_status_projection` (`personId`, `status`, `sourceVersion`) — Phase 3 formalizes this against what `users-service` actually publishes.
5. **Durable account administration**: `CreateAccount`, `ActivateAccount`, `DeactivateAccount`, `ChangeLoginIdentifier`, `RevokeAllAccountSessions`, with correlated results through `platform.results` and non-sensitive events through Outbox.
6. **Administrative temporary password RPC**: `SetTemporaryPassword`, `RegenerateTemporaryPassword`, `security_rpc_idempotency_records` behavior, Direct Reply-To response, no secret persistence in plain text.
7. **Login, refresh, and logout**: `AuthenticateAccount`, `RefreshSession`, `RevokeSession`, failed attempts, temporary account locks, session persistence, token generation and refresh-token rotation, per the parameters fixed in `system_contract.md`.
8. **Password recovery**: `RequestPasswordRecovery`, `ValidateRecoveryCode`, `ResetPassword`, recovery code hashing and single-use behavior, session revocation after reset. The integration event toward `communications-service` is produced per `events.md`; the final delivery contract is Phase 3 work.
9. **Student number synchronization**: consume `StudentNumberCorrected`, apply ordered synchronization through `student_number_login_sync_projection`, publish `LoginIdentifierChanged` or `StudentLoginIdentifierSynchronizationFailed`. Implemented against the conceptual payload already documented in this service's `events.md`/`data-model.md` — Phase 3 formalizes it against what `academic-service` actually publishes.

Out of scope:

- Anything requiring a payload contract not yet ratified with `users-service`, `academic-service`, or the BFF beyond what this service's own docs already assume (see Phase 3).

Acceptance criteria:

- All commands, RPC operations, and events listed in `system_contract.md` are implemented and tested.
- All mandatory invariants hold under test.
- No secret ever appears in logs, metrics, traces, audit, Outbox, Inbox, DLQ, or results.
- Service is deployable and passes its own health checks against a real database and RabbitMQ instance.

## Phase 3 (PR3): Refine Input Contracts — Deferred

Not scoped yet. When picked up, its purpose is to formalize, with the `users-service`, `academic-service`, and BFF teams, the exact payloads `authentication-service` requires to receive (`PersonCreated`/`PersonActivated`/`PersonDeactivated`, `StudentNumberCorrected`, and the durable command envelopes from the BFF), and to reconcile Phase 2's implementation against whatever those teams actually publish.

## Safe Delivery Rules

- Do not introduce secrets in durable messages.
- Prefer database constraints over in-memory checks for critical invariants.
- Keep Phase 2 sub-parts independently reviewable even though they are not gated on external approval.
- Update this plan when a phase's scope changes.
