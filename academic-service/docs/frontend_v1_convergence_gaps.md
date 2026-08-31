# Frontend V1 Backend Convergence Gaps — academic-service

Status: `IMPLEMENTED` — consumer adoption remains in `communications-service`.

Scope: Academic owner data required by the closed Front V1 session-context and Wash mailbox retention flows.

This document intentionally separates two concerns that must not be conflated:

- student/system academic eligibility is already sufficiently modeled and recoverable for the BFF;
- the current Academic Period is recoverable with the full date information required by Communications.

## Architectural boundary

`academic-service` owns academic profiles, academic eligibility and the Academic Period lifecycle. It may publish owner-authoritative facts and recovery snapshots. It does **not** own Communications retention policy or BFF HTTP composition.

Consumers must project Academic state locally and must not introduce synchronous Academic reads in request/notification paths.

## Resolved baseline — system-scoped eligibility already exists

The existing `StudentAcademicEligibilityChanged` contract is system-scoped/versioned and carries the account relationship required by the BFF. Academic recovery also covers eligibility.

Therefore this convergence work does **not** require a new eligibility Event or a duplicated eligibility concept. The BFF gap is to actually include the existing projection in its effective-system composition.

## GAP-ACADEMIC-01 — Current Academic Period cannot be fully reconstructed with its retention dates

- Severity: `HIGH`
- Status: `CLOSED` — owner contract implemented by `GetActiveAcademicPeriodSnapshot`.
- Owner: `academic-service`
- Consumer: `communications-service`
- Affected flow: Wash Mailbox V1 semester retention

### Current state

`GetActiveAcademicPeriodSnapshot` is requester-scoped to `communications` and returns the authoritative active period as `FOUND`, with `academicPeriodId`, `code`, `status`, `startDate`, `endDate` and `sourceVersion`. It returns `NOT_FOUND` when no period is `ACTIVE`.

The Result preserves the aggregate ID and source version in the envelope, so a consumer can protect its local projection from stale or duplicate recovery data.

### Impact

Communications needs local period state to calculate the Wash inbox message `expiresAt` according to the closed semester-retention policy. Without recoverable period dates, a new/rebuilt instance would be forced to choose one of three unsafe behaviors:

1. synchronously query Academic while processing a communication;
2. invent a fallback/infinite expiration;
3. process messages using stale/missing period state.

All three are incompatible with the desired V1 architecture.

### Proposed solution

The implemented owner contract is an authoritative **current Academic Period snapshot** suitable for local consumer reconstruction:

```text
academicPeriodId
periodCode or canonical period identifier
status
startDate
endDate
sourceVersion
```

The snapshot also includes `snapshotGeneratedAt`. The recovery contract identifies authoritative absence without requiring the consumer to infer dates from unrelated Events.

### Recovery semantics

The owner contract must support:

- clean consumer bootstrap;
- projection rebuild;
- targeted reconciliation after missing/out-of-order delivery;
- idempotent duplicate processing;
- monotonic source-version handling;
- authoritative absence when no active period exists.

An activation/change observed incrementally and an authoritative recovery of the same owner state must result in equivalent consumer state.

### Dependencies

- `communications-service` GAP-COMM-01 depends on this gap.
- Communications remains responsible for deciding which messages use semester retention and for calculating message expiration from its local projection.
- BFF effective-system composition uses the already-existing Academic eligibility projection and does not depend on this period-recovery change.

### Acceptance criteria

- A fresh Communications instance can recover the current active Academic Period and its dates without a synchronous Academic read.
- Recovery can authoritatively represent the absence of an active period.
- Incremental activation/update and rebuild converge to the same projected period.
- Recovery is idempotent and source-version aware according to existing Academic conventions.
- Existing eligibility behavior/contracts are unchanged.
- No communication-channel, inbox or expiration policy is moved into Academic.

### Explicit non-solutions

Do not:

- make Communications call Academic synchronously when handling `AppointmentCancelled`;
- publish a Wash-specific retention decision from Academic;
- add a second eligibility mechanism to solve an unrelated period-recovery issue;
- assume that a consumer will always have observed the original period activation Event;
- encode Practices retention policy as a side effect of closing the Wash requirement.

## Consumer-side expectation

After this owner gap closes, Communications will maintain its own local Academic Period projection with readiness/recovery. If the required period state is not ready, Communications must fail closed for behavior that requires it rather than silently choosing an unbounded expiration.

That fail-closed policy is a Communications responsibility; this service's responsibility is to make the authoritative owner state recoverable.

## Service-level Definition of Done

`academic-service` is complete for these Front V1 convergence gaps when:

- the active Academic Period, including retention-relevant dates, is fully recoverable;
- absence/current-state semantics are authoritative;
- incremental and rebuilt consumer states can be equivalent;
- the existing system-scoped eligibility contract remains the single eligibility source;
- no synchronous consumer dependency or Communications policy is introduced into Academic;
- Academic contract/recovery regression suites remain green.
