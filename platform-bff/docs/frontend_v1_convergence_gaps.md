# Frontend V1 Backend Convergence Gaps — platform-bff

Status: `IN PROGRESS` — GAP-BFF-01 requires the separate Users Person Presentation recovery producer before the BFF can safely recover historical rows.

Scope: BFF convergence required by the closed Front V1 flows for real session context (`GET /api/v1/me`), Wash Mailbox V1 and voluntary password change (`AUTH-04`).

This document treats the BFF as a composition/read edge. Owner data must arrive through asynchronous/recoverable contracts and be projected locally. Endpoint implementation must fail closed when required projections are not authoritative.

## Architectural boundary

The BFF owns:

- frontend-facing HTTP contracts;
- local projections of owner state;
- read composition across those projections;
- effective-system calculation for the current account;
- system-scoped read authorization at the HTTP edge.

The BFF does not become the owner of login identifiers, person email, system catalog status, system access or academic eligibility, and it must not repair missing projection data with synchronous business reads to owner services.

## Resolved upstream baseline — do not duplicate

The convergence analysis verified these owner-side facts:

- Users `PersonCreated` and `PersonUpdated` already contain `institutionalEmail`.
- Authorization Role and SystemAccess are already recoverable.
- Academic system-scoped eligibility is already versioned/recoverable and does not need a second contract.
- Authentication already implements the core AUTH-04 ChangePassword mechanics.
- Communications already owns the monotonic inbox-visit behavior required by the `T_open` boundary.

Remaining BFF work is therefore projection/composition/edge hardening, plus consumption of the three owner gaps documented in Authentication, Authorization and Academic/Communications.

## GAP-BFF-01 — Person projection discards `institutionalEmail`

- Severity: `HIGH`
- Status: `IN PROGRESS` — incremental projection is implemented; historical recovery is pending the Users Person Presentation contract.
- Owner: `platform-bff`
- Upstream dependency: `users-service` Person Presentation recovery contract
- Affected flows: `GET /api/v1/me`, WASH-ADM-04 presentation

### Current state

The BFF Person read model/persistence already has an `institutionalEmail` field, and Users Events already carry the owner value. The current typed projector nevertheless writes `null` for that field on `PersonCreated`/`PersonUpdated`.

This is a consumer projection bug, not a Users contract gap.

### Required solution

Project the actual `institutionalEmail` from the Users owner Event into the existing Person read model for both creation and update paths, and ensure recovery/rebuild applies the same semantic value.

The existing Users Status Snapshot cannot close the recovery obligation because it contains
only status/version fields. The owner must publish a separate Person Presentation snapshot;
the BFF must rebuild this local read model from that contract before declaring `person` READY.

### Acceptance criteria

- incremental `PersonCreated` persists the owner email;
- incremental `PersonUpdated` updates/clears the email according to the canonical payload semantics;
- recovery/rebuild produces the same Person projection as the equivalent Event history;
- duplicate/out-of-order behavior remains source-version safe;
- no synchronous Users lookup is added;
- WASH-ADM-04 and `/me` read the same Person projection.

## GAP-BFF-02 — No recoverable Account Presentation read model

- Severity: `BLOCKER`
- Status: `IMPLEMENTED` — deployment depends on Authentication GAP-AUTH-01 / PR #118.
- Owner: `platform-bff`
- Upstream dependency: `authentication-service` GAP-AUTH-01
- Affected flows: `GET /api/v1/me`, WASH-ADM-04 presentation

### Current state

The BFF has no complete local Account Presentation projection/recovery surface capable of reconstructing Authentication-owned `loginType`, current `loginIdentifier` and account lifecycle data needed by the closed client context.

### Proposed solution

Consume the single Authentication Account Presentation contract introduced by GAP-AUTH-01 and maintain one BFF read model reusable by all presentation consumers.

The local model should contain only owner presentation/state needed by BFF reads, for example:

```text
accountId
personId
loginType
loginIdentifier
accountStatus
sourceVersion
```

It must participate in the BFF's projection bootstrap/recovery/readiness model and preserve source-version/idempotency semantics.

### Acceptance criteria

- a clean BFF can rebuild Account Presentation without Authentication RPC/HTTP reads;
- incremental updates and rebuilt state are equivalent;
- `/me` and WASH-ADM-04 use the same projection/mapper rather than separate account DTO sources;
- non-ready account projection state fails closed for endpoints that require it;
- no credential/token/password data is persisted in this read model.

## GAP-BFF-03 — Effective-system composition is incomplete and not reusable

- Severity: `BLOCKER`
- Status: `IMPLEMENTED`
- Owner: `platform-bff`
- Upstream dependency: `authorization-service` GAP-AUTHZ-01
- Existing inputs: recoverable SystemAccess and Academic eligibility
- Affected flows: `/api/v1/me`, mailbox summary/messages/visit, future system-scoped BFF reads

### Required semantic rule

The BFF needs one reusable definition of the systems effectively available to the current account. Conceptually:

```text
System.status == ACTIVE
AND SystemAccess.status == ACTIVE
AND AcademicEligibility == eligible when the target system requires that eligibility
```

Role/account lifecycle constraints already enforced by the established BFF security/read model should remain part of the surrounding access boundary; this gap must not invent a second authorization model.

### Current gap

The BFF has SystemAccess and Academic eligibility projection capabilities, but it cannot yet reconstruct System status and does not expose one shared composition abstraction used consistently by `/me` and mailbox authorization.

### Proposed solution

1. Project Authorization System catalog status from GAP-AUTHZ-01.
2. Add a reusable application/read-domain component that calculates effective systems from local projections.
3. Apply Academic eligibility only for systems whose closed business rule requires it; do not make all systems eligibility-gated by accident.
4. Use the same component for `availableSystemCodes` and system-scoped mailbox authorization.
5. Fail closed if any projection required to make a trustworthy decision is not ready.

### Acceptance criteria

- an active SystemAccess to an inactive System is not exposed as available;
- a system requiring Academic eligibility is not exposed when eligibility is missing/ineligible/untrusted;
- systems not requiring that eligibility are not incorrectly removed;
- `/me` and mailbox use the same effective-system decision;
- canonical codes such as `LAVADO_ULTRASONICO` are returned/checked;
- projection readiness failure cannot grant access optimistically;
- no synchronous owner read occurs in the HTTP path.

### Explicit non-solutions

Do not:

- use frontend `selectedSystemCode` as authorization proof;
- duplicate one effective-access algorithm in `/me` and another in mailbox;
- treat SystemAccess alone as final system availability;
- add Wash-specific authorization shortcuts;
- generalize Wash eligibility/retention rules to Practices.

## GAP-BFF-04 — `GET /api/v1/me` real session context is absent

- Severity: `BLOCKER`
- Status: `IMPLEMENTED` — deployment depends on GAP-BFF-01, GAP-BFF-02 and GAP-BFF-03 being authoritative.
- Owner: `platform-bff`
- Dependencies: GAP-BFF-01, GAP-BFF-02, GAP-BFF-03
- Affected flow: shared authenticated shell / system selection

### Closed HTTP contract

The endpoint must expose the current authenticated context with the closed field set:

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

`loginIdentifier` comes from Authentication projection data. `institutionalEmail` comes from Users projection data. `availableSystemCodes` is composed from effective local system state rather than copied from a single owner.

### Proposed solution

Implement `GET /api/v1/me` as a local projection composition only:

1. resolve the current authenticated account from the established security principal/session context;
2. load Account Presentation, Person, Role/SystemAccess/System and Academic eligibility from BFF-local projections;
3. validate required projection readiness;
4. compute `availableSystemCodes` through GAP-BFF-03;
5. return the closed DTO without internal projection metadata.

### Failure/readiness policy

If a required projection is `BOOTSTRAPPING`, `REBUILDING`, `DEGRADED` or otherwise untrusted, the endpoint must fail closed using the BFF's established projection-unavailable semantics. It must not return a partially authoritative context that could cause the frontend to expose an inaccessible system.

### Acceptance criteria

- endpoint exists under `/api/v1/me` and requires authentication;
- response contains exactly the closed client-context semantics listed above;
- no synchronous owner call is made;
- `availableSystemCodes` uses the shared effective-system rule;
- Account/Person composition is also reusable by WASH-ADM-04;
- OpenAPI snapshot/runtime remain aligned;
- unit, security, projection/recovery and contract tests cover the composition and fail-closed cases.

## GAP-BFF-05 — Mailbox messages are account-scoped instead of system-scoped and lack cursor pagination

- Severity: `BLOCKER`
- Status: `IMPLEMENTED`
- Owner: `platform-bff`
- Affected flow: `PLATFORM-01` Wash Mailbox V1

### Current state

The existing mailbox query abstraction reads visible messages by account and summarizes mailbox state by account. Storage/query behavior already provides stable message ordering and filters expired messages; the remaining problem is **not** a hard-coded `50` result cap.

The actual gaps are:

- requested System is not part of the read scope;
- the messages endpoint has no opaque cursor;
- the collection is not explicitly bounded by a validated page limit;
- summary/message behavior can aggregate systems that should not be visible in the current effective context.

### Closed read behavior

Mailbox messages must be read within a canonical `systemCode` scope and use deterministic pagination:

```text
ORDER BY createdAt DESC, messageId <stable tie-breaker>
```

The public cursor must be opaque. The page limit must be bounded/validated rather than allowing an unbounded account collection.

### Proposed solution

Refactor the mailbox projection query/application contract so the message read includes:

```text
accountId
systemCode
limit
cursor?
```

Return a page/envelope with the messages and next cursor semantics required by the closed client contract. The persistence query must apply the same stable order encoded by the cursor and continue excluding expired/not-visible messages.

Mailbox summary/badge calculation must also be filtered so inaccessible systems do not leak counts/state to the client.

### Acceptance criteria

- messages from another system cannot appear in the requested system mailbox;
- canonical system code is part of the read key;
- page size is bounded and validated;
- pagination is stable under equal `createdAt` using `messageId` as deterministic tie-breaker;
- cursor is opaque to the frontend;
- expired messages remain excluded;
- summary/badge data does not disclose inaccessible systems;
- no earlier `T_open` behavior is regressed.

## GAP-BFF-06 — Mailbox reads and visit registration do not enforce shared effective-system access

- Severity: `BLOCKER`
- Status: `IMPLEMENTED` — deployment depends on the effective-system projections being authoritative.
- Owner: `platform-bff`
- Dependency: GAP-BFF-03
- Affected flow: `PLATFORM-01` Wash Mailbox V1

### Current state

The mailbox HTTP/application path accepts a `systemCode` and the visit path captures a server timestamp, but the requested system is not first validated through the same effective-system model required by `/me`.

### Impact

A system-scoped API must not become accessible merely because the caller knows a system code or has historical mailbox rows for it. Summary, messages and visit state must all observe the same current effective-access boundary.

### Proposed solution

Before reading or mutating mailbox view state for a requested system:

1. resolve the current authenticated account;
2. use GAP-BFF-03 to verify that `systemCode` is currently effective for the account;
3. fail closed if access or required projection readiness cannot be established;
4. only then execute the system-scoped mailbox query or register the visit.

The existing visit timestamp boundary must be retained: capture server-side `T_open` according to the closed flow and send it to Communications, whose monotonic `upsertIfNewer` remains authoritative for delayed/out-of-order visit commands.

### Acceptance criteria

- inaccessible/non-effective systems cannot be summarized, listed or visited;
- `/me` and mailbox authorization use the same effective-system component;
- loss/suspension of access immediately changes the BFF decision once the owner projection is applied;
- projection not-ready state cannot grant access;
- the BFF still captures the server visit timestamp required by `T_open`;
- no mailbox-specific copy of SystemAccess/eligibility rules is created.

## GAP-BFF-07 — AUTH-04 edge/OpenAPI regression coverage is incomplete

- Severity: `MEDIUM`
- Status: `IMPLEMENTED`
- Owner: `platform-bff`
- Affected flow: `AUTH-04`

### Verified V1 baseline already implemented

The BFF already exposes:

```text
POST /api/v1/me/password
```

and the current `AuthenticationEdgeService.changePassword()` already:

- resolves the current authenticated account/session;
- sends the Security RPC `ChangePassword`;
- verifies the returned `sessionId` is the same current session;
- requires nonblank rotated access/refresh tokens;
- requires `authState=NORMAL`;
- returns the new credentials to the client.

The Security RPC command is allowlisted; transport timeout/unavailability maps to the BFF unavailable path and is not automatically retried. The committed OpenAPI document already contains the endpoint.

### Remaining risk

There is no sufficiently explicit BFF regression suite proving the AUTH-04-specific endpoint/security/invariant behavior. Existing versioned OpenAPI tests focus primarily on other operation groups and the authentication edge tests do not comprehensively cover `changePassword`.

### Proposed solution

Add targeted BFF tests without redesigning the endpoint:

- endpoint requires an authenticated current session;
- edge sends the current account/session context to the owner RPC;
- success requires the same `sessionId`;
- blank/missing rotated tokens are rejected as invalid owner response;
- non-`NORMAL` result is rejected for this voluntary path;
- owner functional rejection follows the existing generic authentication rejection mapping;
- owner timeout/unavailable maps to `503`/current BFF unavailable contract;
- no automatic retry of the password mutation occurs;
- versioned OpenAPI/runtime contract explicitly protects `/api/v1/me/password`.

### Non-blocking rejection semantics

Current generic owner `REJECTED` normalization is V1-compatible and shared with existing authentication behavior. Changing it to a new semantic HTTP status/error taxonomy is **not** required to close AUTH-04 and must not be changed for this route alone.

### Frontend integration note

Backend success returns a new access/refresh pair for the same session. The frontend must atomically replace both tokens and serialize this sensitive operation against automatic refresh so a stale client refresh response cannot overwrite the new pair. That is a Front session-state responsibility, not a BFF domain gap.

## Dependency order

The main dependency graph is:

```text
Authentication GAP-AUTH-01
        ↓
BFF GAP-BFF-02 ──────────────┐
                              │
Users Events (already ready) → GAP-BFF-01
                              │
Authorization GAP-AUTHZ-01    ↓
        ↓                 GAP-BFF-03
Existing Academic eligibility ↓
                       ┌───────┴────────┐
                       ↓                ↓
                 GAP-BFF-04       GAP-BFF-06
                   GET /me          mailbox guard
                                        ↓
                                  GAP-BFF-05
                                  mailbox read
```

AUTH-04 GAP-BFF-07 can proceed independently in parallel with owner projection work.

## Service-level Definition of Done

### Real session context

- `GET /api/v1/me` exposes the closed context fields.
- `loginIdentifier` is Authentication-owned projection data.
- `institutionalEmail` is Users-owned projection data and is no longer discarded.
- `availableSystemCodes` applies System status + SystemAccess + Academic eligibility where applicable.
- all composition is local; no synchronous owner reads.
- incremental and rebuilt projections are equivalent.
- required projection `BOOTSTRAPPING`/`REBUILDING`/`DEGRADED` states fail closed.
- WASH-ADM-04 reuses the same Account/Person presentation solution.

### Wash Mailbox V1

- canonical `LAVADO_ULTRASONICO` is used.
- only effective/accessible systems can be summarized, listed or visited.
- messages are system-scoped and cursor-paginated with a bounded limit.
- stable order is `createdAt DESC` plus deterministic `messageId` tie-breaker.
- summary/badge does not leak inaccessible system state.
- visit requires effective access and preserves server-captured `T_open` semantics.
- existing Communications monotonic visit behavior is not duplicated in the BFF.

### AUTH-04

- existing endpoint behavior remains same-session and returns the rotated token pair.
- owner rejection/unavailability mapping remains contract-compatible.
- no mutation retry is introduced.
- dedicated edge/security/OpenAPI regression coverage is green.

### Global BFF quality gate

- committed OpenAPI and runtime contract remain identical under the existing snapshot gate;
- projection, recovery, security and application tests are green;
- no synchronous cross-service business read is introduced;
- no Wash-specific rule is promoted to `PRACTICAS_PROFESIONALES` by default.
