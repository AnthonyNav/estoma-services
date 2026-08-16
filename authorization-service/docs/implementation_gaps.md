# PR2 Implementation Gaps

This register records the gaps found by the implementation audit against the
canonical authorization-service contracts in
`estomatologia-platform-docs/main`. It is updated after the remote recovery
implementation was integrated so each finding has an explicit current status.

## Baseline

- Branch: `authorization-service/feat/authorization-application-recovery`
- Integrated remote baseline: `e569164`
- Local follow-up: `ad97e13` isolates runtime integration state between tests
- Local validation after integration: `nix develop /home/anthony/Projects/Estoma/development --command ./mvnw -B clean verify`, 32 tests passed
- Status convention: `OPEN` means unresolved, `PARTIAL` means only part of the contract is covered, and `RESOLVED` means the current implementation and regression coverage address this finding

## Audit Findings And Resolution Status

### G-01 - Change-role partial mutation

- Severity: `BLOCKER`
- Status: `OPEN`
- Area: Command atomicity
- Evidence: `src/main/java/com/estoma/authorization/application/command/AuthorizationCommandDispatcher.java:620-635` and `:229-246`
- Problem: `ChangeAccountRole` updates `account_roles` and begins suspending accesses before a later suspension conflict is converted into a terminal `REJECTED` Result in the same transaction.
- Impact: A rejected command can commit a new role and only a subset of the required suspensions without the corresponding successful domain Events.
- Contract: `commands.md`, ChangeAccountRole atomicity requirements around lines 240-248.
- Closure: Make the entire command mutation atomic. A rejected Result must leave the role and all access rows unchanged; a successful Result must persist the complete role change, derived suspensions, audit and Outbox intents together.
- Current status: The happy path is covered, but the implementation still catches a suspension conflict as a normal rejected Result after the role update.

### G-02 - Academic Result correlation metadata is incomplete

- Severity: `HIGH`
- Status: `OPEN`
- Area: Academic Commands
- Evidence: `AuthorizationCommandDispatcher.java:552-586` and `:676-714`
- Problem: Successful Academic `AssignAccountRole` and `GrantSystemAccess` Results do not preserve `importId`, `planItemId` or `executionGeneration`.
- Impact: Academic consumers cannot correlate terminal Results with the import plan or protect completion of a newer execution generation.
- Contract: `commands.md`, Academic Result requirements around lines 127-130 and 338-343.
- Closure: Include all three fields in every terminal Academic Result, including successful, rejected and replayed Results.
- Current status: No corrective commit or regression assertion for these fields is present.

### G-03 - Successful Academic Results omit aggregate metadata

- Severity: `HIGH`
- Status: `PARTIAL`
- Area: Result, audit and Outbox metadata
- Evidence: `AuthorizationCommandDispatcher.java:552-575` and `:676-701`
- Problem: Successful Academic Assign/Grant paths create Results without `aggregateId` and `aggregateVersion`.
- Impact: Audit and Outbox records lose the canonical aggregate identity/version even when the Result data contains an entity identifier.
- Contract: `data-model.md`, Result aggregate metadata requirements around lines 1087-1090.
- Closure: Populate aggregate metadata from the mutated aggregate and use the same values in the Result, audit record and Outbox intent.
- Current status: New mutation paths propagate aggregate metadata, but ensure/replay paths still return null aggregate metadata. The new recovery ingress decodes and re-emits stored metadata where available.

### G-04 - Stale Academic generations bypass idempotency

- Severity: `HIGH`
- Status: `PARTIAL`
- Area: Academic generation fencing and idempotency
- Evidence: `AuthorizationCommandDispatcher.java:132-143`, `:193-205` and `:328-330`
- Problem: Stale-generation rejection happens before `insertIfAbsent`, and the terminal stale path does not persist a command idempotency record. An existing `DEFERRED` record can also be returned before the generation is rechecked.
- Impact: Repeated stale commands can emit repeated terminal Results/audit entries, and an obsolete deferred intent can remain active after a newer generation exists.
- Contract: `commands.md`, generation and idempotency requirements around lines 106, 383-384 and 430-442.
- Closure: Fence the generation and idempotency key in one durable decision. Replays must return the original Result, and obsolete deferred commands must be terminally resolved without duplicate side effects.
- Current status: `9452d5e` fixes generation checking when replaying a deferred command, but normal stale rejection still occurs before idempotency persistence and an existing deferred record is returned before generation is rechecked.

### G-05 - Academic causal dependency validation is incomplete

- Severity: `HIGH`
- Status: `PARTIAL`
- Area: `importExecution` validation
- Evidence: `AuthorizationCommandDispatcher.java:855-866` and `CausalDependency.java:14-24`
- Problem: `deferredOrMissing` accepts broad `Account*` event types and matching entity evidence without validating owner, permitted operation, `causationId`, `priorResultMessageId`, prior Result status or `minimumSourceVersion`.
- Impact: An Academic command can become `DEFERRED` from insufficient or unrelated evidence instead of being rejected fail-closed.
- Contract: `messaging-architecture.md`, causal dependency/evidence requirements around lines 650-686.
- Closure: Validate the full causal chain, authoritative owner, allowed scope, prior successful Result, expected event/aggregate and minimum source version before allowing `DEFERRED`.
- Current status: `9452d5e` and `ee0c334` constrain the source and require traceable evidence, but prior command type, causation correlation, source operation and minimum source version are not all validated.

### G-06 - Practices provisioning does not require Account projection visibility

- Severity: `BLOCKER`
- Status: `RESOLVED`
- Area: Derived provisioning
- Evidence: `src/main/java/com/estoma/authorization/application/command/AuthorizationCommandDispatcher.java:77-88` and `PracticesProvisioningService.java:25-74`
- Problem: Provisioning checks eligibility, role and catalog state but does not verify the local Account projection.
- Impact: A `SystemAccess` can be created for an account that is absent or not trustworthy during projection lag or rebuild.
- Contract: `commands.md`, Practices provisioning requirements around lines 273-283.
- Closure: Require a present, applicable Account projection before provisioning. Missing or rebuilding Account state must remain pending/reconciliation rather than creating access.
- Resolution: `9777170` and `6507f70` enforce trusted Account recovery state; Application and recovery integration tests cover fail-closed provisioning.

### G-07 - Eligibility pending reason is never persisted

- Severity: `HIGH`
- Status: `OPEN`
- Area: Projection reconciliation
- Evidence: `ProjectionEventProcessor.java:140-153` and `PracticesProvisioningService.java:67-103`, `:169-175`
- Problem: Eligibility projection processing always writes `pendingReason = null`; provisioning only clears the field and never records why evaluation is pending.
- Impact: `EVENT_PENDING` and `PROJECTION_RECONCILIATION` cannot drive wake-up, recovery or operational diagnosis.
- Contract: `events.md`, pending reason requirements around lines 182-204.
- Closure: Persist the exact pending reason and causal evidence when evaluation cannot complete, and clear it only after a successful authoritative reconciliation.
- Current status: Eligibility projection writes still use `pendingReason = null`; missing prerequisites are not persisted as the required pending reason.

### G-08 - Projection processor defers before applying monotonicity

- Severity: `HIGH`
- Status: `RESOLVED`
- Area: External projection Inbox processing
- Evidence: `ProjectionEventProcessor.java:112-119` and `:208-225`
- Problem: `causallyApplicable=false` returns `DEFERRED` before consulting the current projection version. This can defer stale events and prevent eligibility snapshots from being applied when the contract requires snapshot application plus a separate pending reason.
- Impact: Stale events can degrade projection trust, and eligibility changes can remain unapplied indefinitely.
- Contract: `interfaces.md`, projection monotonicity and eligibility requirements around lines 118-140 and 169-188.
- Closure: Check current version and terminal Inbox state first; ignore stale events monotonically, apply eligible snapshots, and represent causal waiting separately through `pendingReason`.
- Resolution: `cfa6d9e` moved monotonicity ahead of causal handling and `AuthorizationRecoveryIT` covers out-of-order event application.

### G-09 - Inbox deduplication is not independent from command idempotency

- Severity: `HIGH`
- Status: `RESOLVED`
- Area: Inbox processing
- Evidence: `AuthorizationCommandProcessor.java:73-91` and `AuthorizationApplicationConfig.java:202-224`
- Problem: The result of `insertIfAbsent` is ignored, so an already `APPLIED` `messageId` can be processed again when presented with a different command idempotency key.
- Impact: The same inbound message can cause a second mutation or duplicate Event/Result intents.
- Contract: `data-model.md`, Inbox deduplication requirements around lines 485-529.
- Closure: Treat Inbox `messageId` deduplication as an independent terminal gate. An already applied message must return its recorded outcome without executing a new intent.
- Resolution: `2c5bbae` and `06ebb7e` introduced `AuthorizationCommandProcessor` as the canonical `@Primary` ingress; `AuthorizationRecoveryIT` covers exact redelivery versus a new delivery of the same intent. The lower-level dispatcher remains an internal delegate.

### G-10 - BFF Practices Grant does not validate eligibility

- Severity: `HIGH`
- Status: `OPEN`
- Area: Authority and GrantSystemAccess
- Evidence: `AuthorityEvaluator.java:131-133` and `AuthorizationCommandDispatcher.java:658-675`
- Problem: The `SUPERADMIN` BFF path returns authority before eligibility is checked, and the Grant handler validates role/system compatibility only.
- Impact: A Practices system access can be granted to an account without supported academic eligibility.
- Contract: `commands.md`, Practices compatibility/eligibility requirements around lines 361-365.
- Closure: Enforce the documented compatible-and-eligible predicate for Practices grants, including the BFF path, while preserving the intended explicit administrative authority rules.
- Current status: `SUPERADMIN` authority returns before eligibility is checked and the Grant handler validates compatibility only.

### G-11 - Replayed rejected Results do not preserve error details shape

- Severity: `MEDIUM`
- Status: `RESOLVED`
- Area: Result replay
- Evidence: `JacksonStoredApplicationResultDecoder.java:15-31` and `AuthorizationCommandProcessor.java:121-147`
- Problem: Rejected error details are stored as a JSON wrapper such as `{"details":...}` but replayed as that full serialized wrapper rather than the original `error.details` value.
- Impact: A replay with the same fingerprint does not return the same Result representation.
- Contract: `commands.md`, deterministic idempotent replay requirements around lines 85-92.
- Closure: Store and reconstruct the exact terminal Result error object so first execution and replay have identical code, details and data.
- Resolution: `0e38c3e` and `d3ee7a3` added canonical stored-result decoding and replay through `AuthorizationCommandProcessor`. The lower-level dispatcher retains the legacy representation but is not the canonical delivery ingress.

### G-12 - Technical failures have no durable FAILED Result path

- Severity: `HIGH`
- Status: `OPEN`
- Area: Terminal failure handling
- Evidence: `AuthorizationCommandDispatcher.java:229-246` and `:160-178`
- Problem: The dispatcher catches functional/domain exceptions only; unexpected technical failures escape without a persisted idempotency record, Outbox Result, or audit entry.
- Impact: A terminal technical failure cannot be observed or replayed through the contractual `FAILED` Result state.
- Contract: `commands.md`, technical failure requirements around lines 397-406.
- Closure: Convert terminal technical failures to a durable, sanitized `FAILED` Result with idempotency, audit and Outbox persistence while preserving rollback of business mutations.
- Current status: Only functional/domain exceptions are converted to terminal Results; unexpected technical exceptions still escape without durable `FAILED` state.

### G-13 - Academic Grant does not require ALUMNO role

- Severity: `HIGH`
- Status: `OPEN`
- Area: Academic authority and GrantSystemAccess
- Evidence: `AuthorityEvaluator.java:208-213` and `AuthorizationCommandDispatcher.java:665-675`
- Problem: Academic Grant validates the system and import scope but does not require the target role to be `ALUMNO`.
- Impact: A compatible non-student role could receive an Academic student-system grant.
- Contract: `commands.md`, Academic Grant requirements around lines 308-318.
- Closure: Require the existing account role to be `ALUMNO` before Academic Grant can ensure or create access, and add a rejection regression test for another role.

### G-14 - ENTITY recovery does not validate snapshot identity

- Severity: `HIGH`
- Status: `OPEN`
- Area: Projection recovery result handling
- Evidence: `ProjectionRecoveryResultHandler.java:167-210` and `:352-364`
- Problem: ENTITY recovery validates snapshot type and count but does not prove that the returned account/person/profile identity matches the requested `entityKey` before applying it.
- Impact: A valid snapshot for a different entity could mutate the local projection or wake deferred work for the wrong account.
- Contract: `projection-recovery.md`, ENTITY identity requirements around lines 84-104 and 268-275.
- Closure: Validate the requested entity key against every returned snapshot item before mutation; reject mismatches without advancing the recovery cursor or waking deferred work.

### G-15 - Deferred projection replay lacks an atomic claim/fence

- Severity: `HIGH`
- Status: `OPEN`
- Area: Recovery replay concurrency
- Evidence: `ProcessedMessageRepository.java:9-35` and `ProjectionEventProcessor.java:79-119`
- Problem: Deferred projection messages are read and replayed without a durable claim/fencing transition for the same `messageId`.
- Impact: Multiple recovery workers can apply the same deferred event concurrently and emit duplicate provisioning or wake-up effects.
- Contract: `projection-recovery.md`, Inbox claim/fencing requirements around lines 331-343.
- Closure: Add an atomic claim for deferred messages, require the claim token on replay, and make stale/replayed claims terminally harmless.

## Release Gate

PR2 must not be considered complete while any `BLOCKER` or `HIGH` item is
`OPEN` or `PARTIAL`. Each correction must add or update a regression test and
rerun:

```bash
nix develop /home/anthony/Projects/Estoma/development --command ./mvnw -B clean verify
```

This register is a tracking document, not an exception or a waiver of the
canonical contracts.
