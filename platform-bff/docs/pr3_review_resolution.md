# Platform BFF — PR3 Security / Authentication Edge Review Resolution

**Status:** reconstructed after PR1 + PR2 merge; ready for final review only after executable verification.
**Scope:** `platform-bff` Security RPC/JWT/revocation edge plus the minimum owner-contract corrections in `authentication-service`.

## Contract decision — RefreshSession

`POST /api/v1/auth/refresh` does not require a valid access token.

The refresh credential is exactly:

```text
sessionId + refreshToken
```

The BFF sends `RefreshSession` as Security RPC using the canonical public actor:

```text
producer = bff
actor.type = ANONYMOUS
actor.scope = PUBLIC
aggregateId = null
operationId = null
```

Authentication locks/loads `sessionId`, derives `accountId` from the owned Session, validates the refresh token hash and applies replay/grace rules. The client never supplies a trusted account identity and the BFF never parses an expired access JWT to recover one.

## Security RPC

- exchange: `platform.security.rpc`
- routing/queue: `rpc.authentication`
- request TTL: 10 seconds
- BFF timeout: 8 seconds
- automatic retries: 0
- no BFF Outbox
- no BFF Inbox
- no durable Result
- no `operationId`
- request/response secrets are never logged or persisted by BFF

The RPC response trust boundary validates:

- non-null `messageId`;
- `messageKind = RPC_RESPONSE`;
- exact operation/messageType;
- `messageVersion = 1`;
- `producer = authentication`;
- `occurredAt`;
- exact `correlationId`;
- `causationId = request.messageId`;
- `operationId = null`;
- target/aggregate consistency;
- canonical `status/data/error` shape.

Authentication's queue TTL is aligned to 10 seconds so the owner does not expire a request before the BFF's 8-second wait window completes.

## HTTP authentication surface

Implemented:

```text
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
POST /api/v1/auth/password-recovery
POST /api/v1/auth/password-recovery/validate
POST /api/v1/auth/password-recovery/reset
POST /api/v1/me/password
```

Login accepts credentials plus optional `deviceName`; IP address and User-Agent are derived server-side from the HTTP request rather than trusted from the client body.

Logout and change-password derive account/session identity from the locally validated signed JWT principal. They do not trust a client-supplied `sessionId`.

`PASSWORD_CHANGE_REQUIRED` is valid with an access token and no refresh token. That principal may call only change-password and logout; other `/api/v1/**` endpoints require `AUTH_STATE_NORMAL`.

## JWT

BFF validates locally using a configured JWK Set; it performs no runtime HTTP key lookup.

Required properties/claims:

- RS256 header;
- known `kid`;
- signature;
- issuer `estoma-authentication` by default;
- audience `estoma-clients` by default, aligned with Authentication;
- `exp`;
- `iat`;
- `sub` / accountId;
- `personId`;
- `sid`;
- `authState`;
- `jti`.

Public login/refresh/recovery routes skip access-token processing, so an expired bearer cannot block a legitimate refresh. Protected routes reject malformed, expired, wrong-audience, unknown-key or revoked tokens.

## Revocation

The effective rule is:

```text
sid revoked
OR iat <= person cutoff
OR (iat <= account cutoff AND sid != except_session_id)
```

Account cutoff conflict resolution is monotonic:

```text
new timestamp > current timestamp -> new wins
same timestamp + ALL over OTHER    -> ALL wins
older timestamp                    -> ignored
```

A newer `OTHER` may supersede an older `ALL`; this is required for a legitimately new/current session created after an older all-session revocation.

The JWT's signed `personId` is used directly to look up the person cutoff. JWT validity therefore does not depend on `account_person_projection` having already converged.

## Projection event ingestion

There is exactly one Rabbit listener for `events.bff-projections`.

PR3 currently routes these explicit contracts:

```text
authentication / AccountCreated
authentication / AccountDeactivated
authentication / SessionRevoked
authentication / AllAccountSessionsRevoked
authentication / OtherAccountSessionsRevoked
users          / PersonDeactivated
```

Local topology binds only their six canonical routing keys. PR4 extends this dispatcher/queue rather than adding competing listeners.

Processing semantics:

```text
decode -> validate producer/type/version -> Inbox -> projection
```

Inbox and projection mutation share one transaction. Duplicate `messageId` is idempotent. Permanently malformed/untrusted events are quarantined by body SHA-256 and then ACKed. Technical DB/application failures are NACKed/requeued. Sensitive body content is never written to logs/quarantine.

`OtherAccountSessionsRevoked` uses canonical payload field `currentSessionId`; Authentication's prior local name `retainedSessionId` is corrected in the owner contract implementation.

## Distributed refresh single-flight

BFF uses PostgreSQL `pg_advisory_xact_lock` keyed deterministically from `sessionId`.

This provides cross-replica serialization without a permanent locks table or a Flyway migration. The lock is held for the transaction containing the Security RPC call, so at most one effective refresh for the same session is in flight from BFF at a time.

## Persistence / Flyway

PR3 requires no new BFF table.

The obsolete stale-branch `V2__security_refresh_locks.sql` is intentionally not carried forward, avoiding collision with PR2's existing V2 migration.

## Regression coverage

Added/updated coverage for:

- public RefreshSession owner authorization contract;
- owner refresh with anonymous/public BFF context;
- canonical `currentSessionId` event field;
- temporary-password login with null refresh token;
- BFF refresh without JWT principal access;
- logout identity derived from signed principal;
- strict Security RPC causation/correlation/version/producer validation;
- controlled RPC timeout and no retry;
- local JWT validation and rich principal;
- `PASSWORD_CHANGE_REQUIRED` restricted authority;
- expired bearer ignored on public refresh;
- wrong audience and revoked token rejection;
- account cutoff monotonicity and same-timestamp ALL priority using PostgreSQL;
- distributed refresh single-flight using PostgreSQL;
- projection-event quarantine/ACK/NACK behavior.

Existing PR1/PR2 ArchUnit remains the layer gate; Application security code contains no Jackson/JDBC/AMQP dependency.

## Remaining external verification gate

Before merge:

```text
./mvnw -B clean verify
```

must execute when GitHub Actions can obtain a runner, including PostgreSQL/RabbitMQ Testcontainers and ArchUnit.

If Actions again ends before any step (`steps = null`), this is documented as an external CI limitation. It is not reported as green and is not treated as evidence of a code-test failure.
