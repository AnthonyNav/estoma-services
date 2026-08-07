# authentication-service Pending Decisions

## Purpose

This file tracks what is genuinely still open after Phase 1 (PR1). Everything this service had authority to decide on its own — password hashing, token format, session/lockout/recovery policy, migration tooling, Outbox operational policy, observability parameter names — was resolved in `docs/system_contract.md` during Phase 1 and is no longer listed here.

## Deferred To Phase 3

These require agreement with other services' teams before they can be finalized. Phase 2 proceeds using the conceptual payloads already documented in this service's own `events.md`/`data-model.md`/`commands.md`; Phase 3 formalizes and reconciles them.

- Exact payload contract for `PersonCreated`, `PersonActivated`, `PersonDeactivated` as actually published by `users-service`.
- Exact payload contract for `StudentNumberCorrected` as actually published by `academic-service`.
- Final durable command envelopes expected from the BFF (`CreateAccount`, `ActivateAccount`, `DeactivateAccount`, `ChangeLoginIdentifier`, `RevokeAllAccountSessions`, `RequestPasswordRecovery`) — actor data, `Idempotency-Key`, `correlationId` conventions.
- Whether `PasswordChanged` (and related credential events) should trigger an email notification — depends on the finalized `communications-service` contract.
- Concrete routing keys, final result envelopes, and event versioning at the platform level.

## Out Of This Service's Control

Owned by `estoma-infra` / the platform team. Not a blocker for Phase 2.

- Secret management strategy beyond raw Kubernetes Secrets.
- Resource requests/limits and HPA metrics.
- Database user and database creation workflow.
- Physical RabbitMQ topology ownership per environment.
