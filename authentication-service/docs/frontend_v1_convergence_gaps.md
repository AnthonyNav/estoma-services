# Frontend V1 Backend Convergence Gaps — authentication-service

Status: `CLOSED`

Scope: backend convergence required by the closed Front V1 flows for real session context (`GET /api/v1/me`), Wash administration account presentation reuse, and voluntary password change (`AUTH-04`).

This document is an implementation gap register. It does not redefine Authentication business ownership, create a new synchronous dependency, or reopen behavior that is already implemented and compatible with V1.

## Architectural boundary

`authentication-service` remains the owner of credentials, login identifier, login type, authentication state and sessions. Consumers that need presentation data must receive it through durable asynchronous/recoverable contracts and project it locally.

The BFF must not call Authentication synchronously to compose `/api/v1/me` or WASH-ADM-04 reads.

## GAP-AUTH-01 — Account presentation is not deterministically reconstructible

- Severity: `BLOCKER`
- Status: `CLOSED`
- Owner: `authentication-service`
- Consumers: `platform-bff`
- Affected flows: `GET /api/v1/me`, WASH-ADM-04 account presentation

### Current state

Authentication owns the current `loginIdentifier`, but the existing owner-side propagation/recovery surface is insufficient to reconstruct that value after a clean BFF bootstrap or projection rebuild:

- `AccountCreated` identifies the account/login type but does not provide the current login identifier value required by the client context.
- `LoginIdentifierChanged` does not provide the new identifier value required to update a consumer projection.
- the current Account status recovery/snapshot surface does not provide the current login identifier.

Incremental processing therefore cannot be treated as equivalent to a deterministic rebuild for account presentation.

### Impact

Without an owner-authoritative and recoverable value, the BFF cannot safely expose the closed `/api/v1/me` contract:

```text
accountId
personId
displayName
loginType
loginIdentifier
institutionalEmail
roleCode
availableSystemCodes
```

The same missing datum affects administrative account presentation. Solving `/me` and WASH-ADM-04 independently would duplicate the same root problem and create divergent account views.

### Resolution

Authentication now exposes one owner-authoritative Account Presentation
projection contract, documented in
[`account_presentation_recovery.md`](account_presentation_recovery.md). It is
incrementally maintainable through `AccountPresentationUpdated` and recoverable
through BFF-only Queries:

```text
GetAccountPresentationSnapshot(accountId)
ListAccountPresentationSnapshotsPage(cursor?, limit=200)
```

The recoverable item contains:

```text
accountId
personId
loginType
loginIdentifier
accountStatus
sourceVersion
```

The contract supports both:

1. incremental changes when account presentation changes; and
2. authoritative recovery/snapshot reconstruction for bootstrap, rebuild and reconciliation.

If the existing account Events are extended instead of introducing a dedicated presentation Event, all consumers must still have a recovery path with the same semantic data. The solution must preserve monotonic source-version semantics and idempotent replay.

### Dependencies

- No functional dependency on Users: `institutionalEmail` remains owned by `users-service`.
- `platform-bff` GAP-BFF-02 depends on this gap.
- `/api/v1/me` and WASH-ADM-04 must consume the same local BFF account presentation model after this gap closes.

### Acceptance criteria

- A fresh consumer can reconstruct the current login identifier without a synchronous Authentication read.
- An identifier change updates the consumer projection with the new authoritative value.
- Incremental projection state and rebuilt projection state are equivalent for the same owner history.
- Recovery is idempotent, source-version aware and safe under duplicate/out-of-order delivery according to existing platform conventions.
- Account status remains owner-authoritative and is reconstructed together with presentation data needed by consumers.
- No credential secret, password material, recovery code or token is added to durable presentation Events/Results.
- `/me` and WASH-ADM-04 can reuse this single owner-side solution.

### Explicit non-solutions

Do not:

- query Authentication synchronously from the BFF at HTTP request time;
- persist a second independently editable login identifier in the BFF;
- derive the identifier from `institutionalEmail`, `personId`, role or another service's state;
- create separate owner contracts only for `/me` and WASH-ADM-04.

## GAP-AUTH-02 — AUTH-04 needs dedicated integration and concurrency regression coverage

- Severity: `HIGH`
- Status: `CLOSED` — core behavior and dedicated regression coverage are implemented
- Owner: `authentication-service`
- Affected flow: `AUTH-04` voluntary password change

### Verified V1 baseline already implemented

The current `ChangePassword` owner flow already contains the required V1 mechanics and must not be redesigned as part of this gap:

- validates active Account/Person state;
- locks the current Session through `findByIdForUpdate`;
- verifies the current session is valid;
- applies the Password Policy;
- in `NORMAL` auth state, verifies `currentPassword` owner-side;
- rotates the current refresh token;
- updates the credential hash;
- keeps the same current `sessionId`;
- revokes other active sessions for the account;
- emits the corresponding password/session revocation Events;
- returns new access/refresh credentials with `authState=NORMAL`.

`RefreshSession` also serializes on the Session row and the JDBC repository uses a database row lock, providing an implementation-level defense against the `Refresh` vs `ChangePassword` race.

### Remaining risk

The important invariants above are not frozen by a dedicated AUTH-04 integration/concurrency matrix. A future refactor could reintroduce stale-token, partial-revocation or current-session invalidation behavior without a targeted failure.

### Proposed solution

Add owner-side integration/concurrency tests around the existing implementation. No new public business behavior is required.

Required scenarios:

1. valid voluntary password change from `NORMAL` succeeds;
2. returned `sessionId` is exactly the current session;
3. returned access and refresh tokens are newly rotated and usable;
4. the previous refresh token for the current session is no longer usable;
5. other active sessions for the account are revoked;
6. the current session remains valid after the change;
7. the new password authenticates and the previous password no longer authenticates;
8. wrong `currentPassword` is rejected without partial mutation;
9. password-policy rejection produces no partial mutation;
10. concurrent `RefreshSession` and `ChangePassword` are serialized so no stale refresh result can overwrite the owner state;
11. forced transactional failure cannot commit credential/session changes partially;
12. duplicate/replayed transport delivery preserves existing idempotency guarantees.

### Acceptance criteria

- The scenarios above run against the real persistence/transaction implementation used by Authentication integration tests.
- Concurrency coverage proves the row-lock/transaction invariant, not only mocked call ordering.
- No additional session is created for a successful voluntary password change.
- Other-session revocation is committed atomically with the password/session mutation.
- Existing `AUTH-02`/login/refresh behavior remains green.

### Non-blocking HTTP note

The BFF currently normalizes owner `REJECTED` Security RPC outcomes to its generic authentication rejection response. The closed V1 documentation considers that behavior compatible and non-blocking. Changing public rejection semantics is therefore **not** part of this Authentication gap and must not be changed for AUTH-04 in isolation.

## Service-level Definition of Done

`authentication-service` is complete for these Front V1 convergence gaps when:

- Account Presentation is owner-authoritative, incrementally consumable and fully recoverable;
- no synchronous consumer read is needed to obtain `loginIdentifier`;
- rebuild and incremental state are equivalent;
- AUTH-04's current implementation is protected by dedicated integration and real concurrency tests;
- existing Authentication contract/integration suites remain green;
- no secret or credential material is introduced into durable presentation contracts.
