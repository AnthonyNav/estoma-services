# authentication-service V1 System Contract

## Authority

The normative source is `estomatologia-platform-docs/03-services/shared/authentication-service/` and its related messaging, security, data-consistency and BFF documents. This file records the implementation contract; platform documentation wins on any conflict.

## Ownership and Boundary

Authentication owns accounts, login identifiers, credentials, sessions, lockout, password recovery and temporary passwords. It never owns Person data, roles, permissions, academic data, email delivery, or a public HTTP business API. It reads and writes only `authentication-db`.

Functional input is RabbitMQ only:

- durable Commands: `platform.commands` -> `commands.authentication`;
- durable Results: `platform.results`;
- integration events: `platform.events`;
- ephemeral security RPC: `platform.security.rpc` -> `rpc.authentication` -> Direct Reply-To.

RPC requires correlation and reply-to, persists neither request nor response, uses no Inbox, Result, Outbox request, DLQ or late automatic retry. Secrets never enter durable messages, logs, metrics, traces, audit records or message tables.

## Required V1 Behavior

- Durable Commands: `CreateAccount`, `ActivateAccount`, `DeactivateAccount`, `ChangeLoginIdentifier`, `RevokeAllAccountSessions`, `RequestPasswordRecovery`.
- Security RPC: `AuthenticateAccount`, `RefreshSession`, `RevokeSession`, `ValidateRecoveryCode`, `ResetPassword`, `ChangePassword`, `SetTemporaryPassword`, `RegenerateTemporaryPassword`, `GetRecoveryCodeForDelivery`.
- `GetRecoveryCodeForDelivery` accepts only `producer=communications` with `SYSTEM { service: communications }`; it returns a transient HMAC-derived code only while the recovery remains valid.
- Durable input is deduplicated by Inbox `messageId`; functional intention uses `idempotencyKey`. They are never interchangeable.
- Authentication validates technical producer, operation, `ActorContext`, declared contractual scope, compatible target, idempotency and local invariants. Upstream BFF/Authorization remains responsible for functional administrative authorization.
- A missing or inactive Person is fail-closed for login, refresh, recovery and account activation.

## Security Parameters

- Argon2id: 19456 KiB, two iterations, parallelism one, 16-byte salt and 32-byte output.
- Permanent passwords have a minimum length of 15 and must differ from the current password. A local common/compromised password verifier is part of V1.
- Temporary passwords are CSPRNG-generated, 16 characters, hash-only and fixed at 24 hours. Their first use creates `PASSWORD_CHANGE_REQUIRED` for 15 minutes with no refresh token.
- Login locks after five atomic failures for 15 minutes and resets on success.
- Recovery is an HMAC-derived six digit code with a ten minute lifetime, five validation attempts and a five minute request cooldown. Plaintext codes and simple hashes of the small code space are prohibited.
- Refresh tokens are opaque 256-bit values stored only as SHA-256 hashes. Rotation uses a locked current/previous pair with a 30-second retry grace window; a replay outside it revokes the session.
- JWT uses RS256 for at most 15 minutes with `iss`, `aud`, `sub`, `sid`, `personId`, `iat`, `exp`, `jti` and `authState`. Staging/production require configured private key, public key, stable `kid`, issuer and audience. Ephemeral keys are local/test only. No runtime JWKS endpoint exists.

## Persistence and Operations

Flyway migrations are additive. The V1 schema includes account status history, credentials, sessions with current/previous refresh state, recovery state and derivation context, Person and student projections, command/RPC idempotency, Inbox and Outbox.

All critical flows share a local transaction: Inbox, local change and derived Outbox records commit together; durable consumers acknowledge only after that commit. The relay claims with `FOR UPDATE SKIP LOCKED`, uses publisher confirms and exponential backoff, and marks messages `FAILED` after ten attempts. Failed rows remain durable for operational replay, requeue or reconciliation.

Liveness checks process health only. Readiness requires database, RabbitMQ, consumers and relay. Startup validates configuration, migrations, keys and required boot dependencies without making a transient external outage trigger a liveness restart loop.

## Verification

`./mvnw clean verify` runs unit/domain/security tests plus PostgreSQL and RabbitMQ Testcontainers integration tests. V1 requires contract suites for envelopes, Commands, RPC and events; concurrency tests for refresh, lockout, recovery, password/login changes and idempotency; and a broker-backed smoke flow from Person creation through recovery reset and session revocation.
