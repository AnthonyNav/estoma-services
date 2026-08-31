# Frontend V1 Backend Convergence Gaps — communications-service

Status: `CLOSED`

Scope: backend convergence required by the closed Wash Mailbox V1 flow, specifically semester retention and the capacity-loss cancellation communication.

This register documents only remaining Communications-owned work. Existing inbox storage, expiration machinery and inbox-visit monotonicity are treated as resolved baseline and must not be reimplemented.

## Architectural boundary

`communications-service` owns durable Communication/Inbox/Email behavior and channel delivery. It consumes owner facts asynchronously and maintains local projections required to make communication decisions.

For this scope:

- Academic owns Academic Period facts;
- Appointments owns cancellation facts;
- Communications owns whether a qualifying cancellation becomes a communication, its channels/content and inbox expiration behavior;
- the BFF owns HTTP mailbox composition and effective-access enforcement.

No synchronous cross-service read belongs in the communication handling path.

## Resolved baseline — do not reopen

The current implementation already provides important mechanics needed by Mailbox V1:

- inbox messages persist an `expiresAt` value;
- storage/index/scheduler mechanics exist for expiration;
- inbox expiration events/mechanics already exist;
- duplicate-safe command/event infrastructure exists;
- `RegisterInboxVisit` applies a monotonic `upsertIfNewer` rule;
- therefore the closed `T_open` boundary can be preserved even when the visit command arrives later.

These are not gaps. New work should extend them rather than create parallel implementations.

## GAP-COMM-01 — Academic Period is not locally projected/recoverable for retention decisions

- Severity: `BLOCKER`
- Status: `CLOSED` — `academic_period_projections` is owner-versioned; bootstrap/retry uses the durable Academic Query/Result contract.
- Owner: `communications-service`
- Upstream dependency: `academic-service` GAP-ACADEMIC-01
- Affected flow: Wash Mailbox V1 semester retention

### Implemented behavior

Communications consumes `AcademicPeriodActivated` and `AcademicPeriodClosed`, stores per-period source versions, and fences its current state with a recovery query `causationId`. Startup/retry recovery is sent through the Outbox to `platform.queries` / `academic`; only the matching Academic Result can make the projection `READY`.

The Wash policy converts `endDate` to the first local instant after the final academic date using the explicit `estoma.communications.wash.institutional-time-zone` configuration (default `America/Mexico_City`). It reuses the existing `expiresAt` worker and applies no global Practices policy.

### Required behavior

Communications must consume and project the Academic Period locally using the same event/recovery architecture expected for other owner projections.

The local projection must contain enough state to identify the authoritative current period and its retention-relevant dates, sourced from Academic rather than duplicated configuration.

The projection requires explicit readiness/recovery semantics. Behavior that requires current period data must fail closed while the projection is not trustworthy.

### Proposed solution

1. Consume the canonical Academic Period lifecycle Event(s) relevant to active/current period state.
2. Add a local Academic Period read model with source-version monotonicity.
3. Add recovery/bootstrap support using the owner contract introduced by GAP-ACADEMIC-01.
4. Track projection readiness consistently with Communications' existing recovery/operational patterns.
5. Resolve a Wash inbox expiration from the locally projected active period when building the qualifying communication.
6. Preserve the current expiration worker/storage path; only provide it the correct owner-derived `expiresAt`.

### Fail-closed rule

If the required Academic Period projection is `BOOTSTRAPPING`, `REBUILDING`, `DEGRADED`, missing or otherwise not authoritative, Communications must not silently create an effectively permanent Wash inbox message.

The processing/recovery strategy may defer or retry according to existing durable platform patterns, but it must not:

- invent an arbitrary future date;
- use an infinite/default retention;
- synchronously query Academic as a fallback;
- drop the INBOX channel while continuing EMAIL and report full success.

### Scope protection

The closed requirement is for the Wash mailbox. Semester expiration must therefore be applied through an explicit system/communication policy. Closing this gap does **not** establish that every future `PRACTICAS_PROFESIONALES` inbox message expires at the Academic Period boundary.

### Acceptance criteria

- A fresh Communications instance can rebuild the current Academic Period locally.
- Incremental projection and recovery/rebuild converge to equivalent period state.
- Projection updates are idempotent and source-version aware.
- A Wash inbox message that requires semester retention receives an owner-derived `expiresAt`.
- Required behavior fails closed when the period projection is not authoritative.
- Existing expiration worker/index/event behavior is reused.
- No synchronous Academic read is introduced.
- No global Practices retention rule is created implicitly.

## GAP-COMM-02 — `AppointmentCancelled` capacity-loss adapter is not aligned with closed Mailbox V1 behavior

- Severity: `BLOCKER`
- Status: `CLOSED` — qualifying events now create the canonical two-channel Wash communication through the existing in-process request path.
- Owner: `communications-service`
- Upstream event: Appointments `AppointmentCancelled`
- Dependency: GAP-COMM-01 for the INBOX expiration value
- Affected flow: Wash appointment cancelled by clinic because of capacity loss

### Implemented behavior

The adapter already has the important functional filter and it must be preserved:

```text
cancellationOwner == CLINIC
AND cancellationSubreason == CAPACITY_LOSS
```

The adapter preserves the exact `CLINIC + CAPACITY_LOSS` predicate and requests `INBOX + EMAIL` through `RequestCommunicationService`. It uses `LAVADO_ULTRASONICO`, factual cancellation copy, the source appointment as its stable idempotency key, and the local authoritative period for the Inbox expiration.

### Proposed solution

For a qualifying `CLINIC + CAPACITY_LOSS` cancellation:

- normalize the communication system to canonical `LAVADO_ULTRASONICO`;
- request exactly the V1 channels `INBOX + EMAIL`;
- build factual cancellation content that explains the capacity-loss cancellation without promising automatic rescheduling, future contact or a replacement slot;
- calculate the INBOX `expiresAt` from the authoritative local Academic Period projection;
- reuse the existing in-process `RequestCommunication` handling path and transaction/idempotency model;
- preserve the existing max-one-inbox/max-one-email communication invariants.

The adapter should continue to derive its action from the Appointments owner Event. Current Appointments cancellation payload already provides `appointmentType`, and the service's cancellation vocabulary is Wash-oriented; this convergence does not require adding a new `systemCode` to Appointments merely to repair a Communications alias.

### Idempotency requirement

An exact duplicate/redelivery of the same Appointments Event must not create a second Communication, InboxMessage or EmailDelivery. The correction must retain the existing Consumer Inbox/functional idempotency boundary rather than implement ad hoc deduplication in the adapter.

### Acceptance criteria

- `CLINIC + CAPACITY_LOSS` creates one logical communication with exactly one INBOX and one EMAIL channel request.
- the communication uses canonical `LAVADO_ULTRASONICO`.
- INBOX expiration comes from the local active Academic Period.
- user-facing copy contains no automatic rebooking/contact promise.
- duplicate delivery is idempotent.
- the adapter remains in-process; no RabbitMQ self-command round trip is added.
- no change is required to Appointments solely to inject a system alias.

### Explicit non-solutions

Do not:

- weaken the qualifying filter to every appointment cancellation;
- add a frontend-specific `wash-ultrasonico` code to durable contracts;
- create only EMAIL when the period projection is unavailable and call the operation complete;
- promise a new appointment, automatic rescheduling or guaranteed follow-up;
- synchronously fetch the Academic Period;
- promote this Wash-specific cancellation rule to Practices.

## GAP-COMM-03 — Negative regression coverage is insufficient to freeze Wash communication exclusions

- Severity: `MEDIUM`
- Status: `CLOSED` — integration coverage now asserts durable absence for exclusions and exact durable channels for the inclusion path.
- Owner: `communications-service`
- Affected flow: Wash cancellation communication safety

### Regression coverage

`AppointmentCancelledAdapterIT` verifies the qualifying channels/system/expiration/copy and redelivery idempotency. It also verifies that student cancellations (including redelivery), wrong clinic reasons, predicate half-matches, malformed qualifying payloads and unavailable period state leave no Communication, InboxMessage or EmailDelivery.

### Proposed test matrix

At minimum prove that no Communication/Inbox/Email is created when:

- cancellation owner is not `CLINIC`;
- cancellation subreason is not `CAPACITY_LOSS`;
- only one half of the qualifying predicate matches;
- an unsupported/malformed combination is rejected or ignored according to the existing consumed-event contract;
- the same non-qualifying event is redelivered.

Also prove the qualifying path still creates exactly `INBOX + EMAIL`, uses the canonical system code and remains idempotent.

### Acceptance criteria

- negative tests assert absence of durable communication side effects, not only that an adapter method returned early;
- existing communication idempotency and Consumer Inbox semantics remain green;
- the test suite makes the exact V1 inclusion predicate obvious to future maintainers.

## Recommended implementation order

```text
Academic owner recovery contract
        ↓
Communications AcademicPeriod projection + readiness/recovery
        ↓
AppointmentCancelled adapter convergence
        ↓
positive + negative adapter/integration tests
        ↓
BFF Mailbox V1 end-to-end validation
```

The owner contract and BFF mailbox work may proceed in parallel where dependencies allow, but Communications must not finalize the retention-dependent adapter path using temporary/fake period data.

## Service-level Definition of Done

`communications-service` is complete for the closed Wash Mailbox V1 convergence when:

- Academic Period state is local, recoverable, source-versioned and readiness-aware;
- missing/untrusted period state fails closed for retention-dependent INBOX creation;
- qualifying cancellations are exactly `CLINIC + CAPACITY_LOSS`;
- the canonical system is `LAVADO_ULTRASONICO`;
- the qualifying communication produces exactly INBOX + EMAIL;
- copy is factual and contains no rebooking promise;
- INBOX receives correct owner-derived semester expiration;
- duplicate delivery is idempotent;
- negative tests freeze all excluded cancellation variants;
- existing `T_open`/monotonic visit and expiration mechanics remain unchanged;
- no Wash retention/notification rule is silently generalized to Practices.
