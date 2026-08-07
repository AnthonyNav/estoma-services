# authentication-service System Contract

## Status

Implementation contract for Phase 1 (PR1) of a three-phase plan (see `execution_plan.md`). This document constrains the scaffold and all future implementation work. Sections through "Prohibited Behavior" are derived from the platform documentation repository (see "Source Of Truth"). Sections from "Internal Architecture" onward are technical decisions this service has authority to make on its own; they are not derived from the platform documentation and do not require cross-team sign-off.

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

## Internal Architecture

`authentication-service` follows a hexagonal (ports & adapters) architecture:

```text
com.estoma.authentication
├── domain/            # no framework dependencies
│   ├── account/         # Account (aggregate root), value objects, invariants
│   ├── credential/       # Credential, PasswordHash
│   ├── session/           # Session, RefreshToken
│   ├── recovery/           # PasswordRecoveryRequest
│   └── event/                # domain event types, prior to Outbox encoding
├── application/        # use cases; orchestrate domain + ports
│   ├── command/           # durable command handlers
│   ├── rpc/                 # ephemeral RPC operation handlers
│   └── projection/            # PersonCreated/Activated/Deactivated, StudentNumberCorrected handlers
├── infrastructure/     # concrete adapters
│   ├── persistence/       # JPA + Spring Data repositories implementing domain ports
│   ├── messaging/           # RabbitMQ listeners/publishers, envelope (de)serialization
│   ├── security/               # Argon2 password hashing, JWT issuing/verification
│   └── config/                    # Spring `@Configuration` classes
└── contracts/           # wire-name vocabulary (already implemented)
```

`contracts` is not moved or restructured. `LoginType` and `AccountStatus` are pure value enums with no framework dependency and no `wireName()` mapping; `domain` may depend on them directly. Only `AuthenticationCommand`, `AuthenticationEvent`, `AuthenticationRpcOperation`, and `MessagingNames` (which map to wire strings) remain adapter/infrastructure-facing vocabulary.

`domain` must have zero dependency on Spring, JPA, or RabbitMQ. `application` depends on `domain` and on port interfaces declared by `domain` or `application`, never directly on `infrastructure`. `infrastructure` implements those ports and is the only layer allowed to depend on Spring Data, Spring AMQP, or a JWT/hashing library.

Port interfaces (e.g. `AccountRepository`, `PasswordHasher`, `TokenIssuer`, `OutboxPublisher`) are not created in Phase 1: an interface with no real consumer yet is speculative. They are designed in Phase 2 alongside their first concrete use.

Recommended dependencies for Phase 2 (not added to `pom.xml` in Phase 1, since Phase 1 has no functional dependencies):

- `spring-security-crypto` — `Argon2PasswordEncoder`, without the full Spring Security filter chain.
- `io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson` — JWT issuing and verification.
- `spring-boot-starter-data-jpa` + `org.postgresql:postgresql`.
- `flyway-core` + `flyway-database-postgresql`.
- `spring-boot-starter-amqp`.

## Security Parameters (v1)

Values below are this service's own technical decisions. They may be tuned later with production data; they are not a blocker for Phase 2.

### Password hashing

| Parameter | Value |
| --- | --- |
| Algorithm | Argon2id |
| Memory | 19 MiB (19456 KB) |
| Iterations | 2 |
| Parallelism | 1 |
| Salt length | 16 bytes |
| Output length | 32 bytes |

Algorithm and parameters are stored per credential (`hash_algorithm`, `hash_parameters`) so a future cost increase can re-hash on next successful login without a migration.

### Password policy

- Minimum length: 10 characters.
- No forced composition rules (uppercase/symbol/digit requirements).
- New password must differ from the immediately previous password (compare against the current `password_hash` before overwrite; no full history check in v1).
- A breached-password denylist is not implemented in v1; this is a future enhancement, not a Phase 2 blocker.

### Temporary password

- Format: 12 characters, alphanumeric plus symbols, same policy as a user-chosen password.
- Expiration: 24 hours if unused.
- Sets `must_change_password = true`; cleared when `ChangePassword` completes.
- Triggers `AllAccountSessionsRevoked` in the same transaction that sets it (already required by `commands.md` §7.1).

### Access tokens

| Parameter | Value |
| --- | --- |
| Format | JWT |
| Signature | RS256 (asymmetric; BFF verifies with the public key only) |
| Claims | `sub` (accountId), `personId`, `loginType`, `iat`, `exp`, `jti`; `kid` in the header |
| Lifetime | 15 minutes |
| Revocation | none in v1 — the token is left to expire; revocation acts on the session/refresh token, not the access token |

### Refresh tokens

| Parameter | Value |
| --- | --- |
| Format | opaque, 256-bit random value |
| Storage | SHA-256 hash only, in `sessions.refresh_token_hash` |
| Rotation | rotate on every `RefreshSession` call; the previous token is invalidated |
| Grace window | 30 seconds after rotation, to tolerate client-side retry races; exact storage of the previous hash/expiry is a Phase 2 migration-design detail, the policy is fixed here |

### Sessions

| Parameter | Value |
| --- | --- |
| Absolute session lifetime | 30 days from `created_at`; each refresh extends `expires_at` but never past this cap |
| Max concurrent sessions | unbounded in v1 (no artificial cap); `RevokeAllAccountSessions` remains available |
| Device metadata stored | `deviceName`, `ipAddress`, `userAgent`, `createdAt`, `lastUsedAt` |
| Retention after expiry/revocation | 30 days, then scheduled cleanup |
| Revocation after password change | all sessions for the account are revoked (`ChangePassword` and `ResetPassword` both trigger `AllAccountSessionsRevoked`) |

## Lockout Policy (v1)

| Parameter | Value |
| --- | --- |
| Failed attempt limit | 5 |
| Lock duration | 15 minutes, fixed (no escalation in v1) |
| Reset on success | `failed_attempts = 0`, `locked_until = NULL` |

## Password Recovery Parameters (v1)

| Parameter | Value |
| --- | --- |
| Code format | 6-digit numeric code |
| Code storage | SHA-256 hash in `password_recovery_requests.token_hash` |
| Expiration | 30 minutes |
| Validation attempt limit | 5; beyond that the request is invalidated and a new `RequestPasswordRecovery` is required |
| Notification of `PasswordChanged` by email | deferred to Phase 3 — depends on the finalized `communications-service` contract |

## Migration Tooling

- Tool: **Flyway** (native Spring Boot autoconfiguration; plain SQL vs. Liquibase's XML/YAML changelogs; no extra plugin wiring needed on top of `./mvnw clean verify`).
- Location: `src/main/resources/db/migration/`, files named `V{n}__description.sql`.
- Migrations are additive only; an already-applied migration is never edited.
- Isolation level: READ COMMITTED (Postgres default) unless an operation states otherwise.
- Explicit row locking (`SELECT ... FOR UPDATE`) is required for refresh token rotation and for claiming a `security_rpc_idempotency_records` row, matching the transactional semantics already required by `commands.md` §7.1.
- `processed_messages` retention: purge rows older than 30 days.
- `security_rpc_idempotency_records` retention: `expires_at = created_at + 24h`.

The `migrations/` placeholder directory at the service root is removed in Phase 1; it predates this decision and does not match Flyway's classpath convention.

## Outbox Operational Policy

- Relay: single process per pod, polling every 1 second.
- Claim query pattern: `SELECT ... WHERE status = 'PENDING' AND available_at <= now() ORDER BY occurred_at LIMIT 50 FOR UPDATE SKIP LOCKED`.
- Publish with RabbitMQ publisher confirms; mark `PUBLISHED` on ack.
- On failure: increment `attempts`, exponential backoff via `available_at`, mark `FAILED` after 10 attempts.
- `authentication.outbox.failed.total` is an alertable metric (see "Observability Parameters").

## Observability Parameters

### Metrics

```text
authentication.login.attempts.total{result}
authentication.account.locked.total
authentication.session.created.total
authentication.session.revoked.total{reason}
authentication.recovery.requested.total
authentication.recovery.completed.total
authentication.rpc.timeout.total{operation}
authentication.outbox.pending            (gauge)
authentication.outbox.failed.total
authentication.messages.duplicate.total{consumer}
```

### Logging

The field set already defined in the platform's `interfaces.md` §10 (`service`, `environment`, `message_id`, `correlation_id`, `account_id`, `session_id`, `operation`, `result`, `error_code`, `duration`) is confirmed as final. Redaction rule: never pass a full request/response DTO to the logger; construct log lines only from this allowlisted field set.

### Audit

v1 uses structured logs (the fields above) as the audit trail, shipped to the platform's log pipeline. There is no dedicated `audit_log` table in `authentication-db` in v1 — this keeps the persistence boundary unchanged from what is already listed above. A durable, queryable audit store distinct from log retention is a future decision, not a Phase 2 blocker.

## RPC Ephemeral Operational Parameters

| Parameter | Value |
| --- | --- |
| Message TTL | 5 seconds |
| BFF timeout waiting on Direct Reply-To | 5 seconds |
| Rate limiting | out of this service's scope — enforced by the BFF/gateway before a request reaches RabbitMQ; `authentication-service` only enforces account-level lockout |

## PR1 Acceptance Criteria

PR1 (Phase 1) is complete when:

- the existing service scaffold remains buildable;
- the service has a local `docs/` directory;
- this system contract is present and has no open technical decision that blocks starting Phase 2;
- the three-phase execution plan is present;
- `pending_decisions.md` contains only items deferred to Phase 3 or explicitly out of this service's control;
- the hexagonal package skeleton (`domain`, `application`, `infrastructure`) exists, documented via `package-info.java`, with no premature interfaces or implementations;
- operational health endpoints are available for startup, readiness, and liveness checks;
- canonical enum/constant names exist for already-defined commands, RPC operations, events, login types, account statuses, exchanges, and queues;
- no authentication logic, token logic, password hashing, RabbitMQ consumers, or database migrations are implemented prematurely.
