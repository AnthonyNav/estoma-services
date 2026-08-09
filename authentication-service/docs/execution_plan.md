# authentication-service V1 Execution Plan

## Goal

Deliver an operational, secure, deployable and contract-compatible
`authentication-service` V1. This branch does not declare the complete
platform ready: BFF, Communications, Users and Academic are simulated at the
messaging boundary where their implementations are unavailable.

## Branch Strategy

- Repository: `AnthonyNav/estoma-services`.
- Base: `develop`.
- Integration branch: `authentication-service/feat/v1-operational`.
- `authentication-service/feat/messaging-shell` is reference material only.
  Each of its commits must be reviewed and ported selectively; it was built
  against older contracts and must not be blindly cherry-picked.

## Delivery Sequence

1. **Contract sync**: synchronize local documentation, constants, DTOs,
   envelope vocabulary, queues and routing keys with platform V1.
2. **Persistence V1**: additive Flyway migrations, constraints, indexes,
   repositories, and migration tests from an empty and current schema.
3. **Security/domain**: Argon2id, 15-character password policy, local common
   password verifier, temporary-password rules and authorization boundary.
4. **Sessions/recovery/concurrency**: lockout, HMAC recovery, cooldown,
   current/previous refresh rotation, replay detection and fail-closed Person
   checks using row locks or an equivalent transactional guarantee.
5. **Selective messaging-shell port**: port only reviewed RabbitMQ
   infrastructure compatible with V1.
6. **Commands/Results/Events**: durable Commands, Result V1, events, Consumer
   Inbox, strict envelope validation and ACK after commit.
7. **Security RPC**: Direct Reply-To, no durable secret storage, administrative
   RPC checks and `GetRecoveryCodeForDelivery` restricted to Communications.
8. **Outbox relay**: publisher confirms, `FOR UPDATE SKIP LOCKED`, backoff,
   ten-attempt terminal `FAILED` state, and operational replay/reconciliation.
9. **JWT configuration**: configured key, `kid`, issuer and audience; ephemeral
   keys are local/test only and no JWKS endpoint is exposed.
10. **Health/metrics/cleanup**: liveness, readiness, startup, metrics and
    durable cleanup workers without dependency-induced restart loops.
11. **Contract tests**: envelope, command, RPC and event contract suites.
12. **Integration/concurrency tests**: PostgreSQL and RabbitMQ Testcontainers,
    including races and duplicate delivery.
13. **Full V1 smoke**: execute the documented temporary-password, session and
    recovery lifecycle through real broker and database containers.

## Verification Gate

`./mvnw clean verify` must pass. The suite must prove migration compatibility,
multi-instance JWT verification with the same configured key, no secret in
durable messaging or observability data, and the V1 smoke flow.
