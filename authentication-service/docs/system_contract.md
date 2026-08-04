# authentication-service System Contract

## Status

Initial implementation contract for PR1. This document constrains the scaffold and future implementation work. It does not define final code-level schemas for messages, tokens, or database migrations.

## Source Of Truth

This contract is derived from the platform documentation repository:

- `03-services/shared/authentication-service/README.md`
- `03-services/shared/authentication-service/commands.md`
- `03-services/shared/authentication-service/data-model.md`
- `03-services/shared/authentication-service/events.md`
- `03-services/shared/authentication-service/interfaces.md`
- `02-architecture/messaging-architecture.md`
- `02-architecture/security-observability.md`
- `02-architecture/data-consistency-architecture.md`
- `02-architecture/decisions/0001-comunicacion-interna-mediada-por-mensajes.md`

If this file conflicts with the platform documentation, the conflict must be resolved in the documentation source before implementing behavior.

## Ownership

`authentication-service` owns:

- access accounts;
- login identifiers for authentication;
- credentials and credential lifecycle;
- sessions;
- refresh and revocation;
- password recovery;
- temporary passwords;
- failed attempts and temporary locks.

`authentication-service` does not own:

- personal identity data;
- roles, permissions, or system access;
- academic profile, semester, NRC, or official student number source;
- email provider integration;
- client-facing HTTP APIs.

## Persistence Boundary

The service writes only to `authentication-db`.

Initial conceptual tables:

- `accounts`
- `account_credentials`
- `sessions`
- `password_recovery_requests`
- `person_status_projection`
- `student_number_login_sync_projection`
- `security_rpc_idempotency_records`
- `processed_messages`
- `outbox_messages`

There must be no foreign keys to databases owned by other services. References such as `personId`, `accountId`, and `studentAcademicProfileId` are logical references.

## Operational HTTP Interface

The service may expose only internal operational endpoints:

- `/health/startup`
- `/health/readiness`
- `/health/liveness`
- `/metrics`

The current scaffold also exposes simple `/health` and `/version` endpoints for local operational verification. These endpoints must not authenticate users or administer accounts.

## Functional Input Interfaces

Durable business commands use RabbitMQ:

```text
platform.commands -> commands.authentication-service -> authentication-service
```

Initial durable commands:

- `CreateAccount`
- `ActivateAccount`
- `DeactivateAccount`
- `ChangeLoginIdentifier`
- `RevokeAllAccountSessions`
- `RequestPasswordRecovery`

Durable commands produce correlated non-sensitive results through:

```text
platform.results -> results.bff
```

Sensitive operations use ephemeral security RPC:

```text
platform.security.rpc -> rpc.authentication -> Direct Reply-To
```

Initial RPC operations:

- `AuthenticateAccount`
- `RefreshSession`
- `RevokeSession`
- `ValidateRecoveryCode`
- `ResetPassword`
- `ChangePassword`
- `SetTemporaryPassword`
- `RegenerateTemporaryPassword`

`ValidateMfaCode` is reserved for a future MFA capability and is not part of the first implementation.

## RPC Security Rules

Ephemeral security RPC must follow these rules:

- messages are transient;
- requests require short TTL and BFF timeout;
- `correlationId` and `replyTo` are required;
- responses use RabbitMQ Direct Reply-To;
- no BFF Outbox;
- no durable Inbox;
- no `platform.results`;
- no `operationId`;
- no DLQ;
- no automatic late retries by the BFF;
- no persisted request or response payloads;
- no persisted passwords, codes, OTPs, access tokens, refresh tokens, or generated temporary passwords.

`SetTemporaryPassword` and `RegenerateTemporaryPassword` require an `idempotencyKey` protected by `security_rpc_idempotency_records`. A repeated completed request must not generate or resend a secret.

## Events Produced

Events are non-sensitive integration facts and must be recorded through Transactional Outbox in the same transaction as the local change.

Initial produced events:

- `AccountCreated`
- `AccountActivated`
- `AccountDeactivated`
- `LoginIdentifierChanged`
- `StudentLoginIdentifierSynchronizationFailed`
- `PasswordChanged`
- `TemporaryPasswordSet`
- `TemporaryPasswordRegenerated`
- `SessionRevoked`
- `AllAccountSessionsRevoked`
- `PasswordRecoveryRequested`

The following facts are internal only in the first delivery and must not be published to `platform.events`:

- `AuthenticationSucceeded`
- `AuthenticationFailed`
- `SessionCreated`
- `SessionRenewed`

## Events Consumed

Initial consumed events:

- `PersonCreated`
- `PersonActivated`
- `PersonDeactivated`
- `StudentNumberCorrected`

Durable event consumption must use Consumer Inbox or equivalent idempotence. ACK happens only after the local transaction commits.

## Mandatory Invariants

- Each `accountId` is unique and immutable.
- Each account belongs to a logical `personId`.
- Login identifiers are normalized before persistence.
- `loginType + normalized login identifier` is unique.
- `CreateAccount` creates an inactive account without a credential.
- `ActivateAccount` requires a configured credential.
- Inactive accounts cannot authenticate.
- Inactive persons cannot use any related account.
- Passwords, temporary passwords, recovery codes, and tokens are never stored in plain text.
- Refresh tokens and recovery tokens are stored only as hashes.
- Revoked or expired sessions cannot be renewed.
- Password recovery tokens are single-use.
- Local changes and Outbox messages share one transaction.
- Durable consumed messages are idempotent.

## Prohibited Behavior

The service must not:

- expose public client APIs;
- receive direct client requests;
- allow BFF HTTP or gRPC calls for functional behavior;
- access another service database;
- administer roles or permissions;
- send emails directly;
- publish passwords, password hashes, tokens, codes, OTPs, or signing keys;
- write sensitive RPC request or response payloads to logs, metrics, traces, audit records, Outbox, Inbox, DLQ, or results.

## PR1 Acceptance Criteria

PR1 is complete when:

- the existing service scaffold remains buildable;
- the service has a local `docs/` directory;
- this system contract is present;
- the phased execution plan is present;
- pending decisions are explicitly documented;
- no authentication logic, token logic, password hashing, RabbitMQ consumers, or database migrations are implemented prematurely.
