# PR2 Implementation Gaps

This register records the gaps found by the implementation audit against the
canonical authorization-service contracts in
`estomatologia-platform-docs/main`. These items remain open; the current PR2
branch was published with the gaps explicitly recorded for resolution before
the implementation is considered complete.

## Baseline

- Branch: `authorization-service/feat/authorization-application-recovery`
- Recorded commit: `49013e8`
- Local validation at recording time: `nix develop /home/anthony/Projects/Estoma/development --command ./mvnw -B clean verify`, 26 tests passed
- Status convention: `OPEN` means the gap is not yet corrected or revalidated

## Open Gaps

### G-01 - Change-role partial mutation

- Severity: `BLOCKER`
- Status: `OPEN`
- Area: Command atomicity
- Evidence: `src/main/java/com/estoma/authorization/application/command/AuthorizationCommandDispatcher.java:529-543` and `:148-164`
- Problem: `ChangeAccountRole` updates `account_roles` and begins suspending accesses before a later suspension conflict is converted into a terminal `REJECTED` Result in the same transaction.
- Impact: A rejected command can commit a new role and only a subset of the required suspensions without the corresponding successful domain Events.
- Contract: `commands.md`, ChangeAccountRole atomicity requirements around lines 240-248.
- Closure: Make the entire command mutation atomic. A rejected Result must leave the role and all access rows unchanged; a successful Result must persist the complete role change, derived suspensions, audit and Outbox intents together.

### G-02 - Academic Result correlation metadata is incomplete

- Severity: `HIGH`
- Status: `OPEN`
- Area: Academic Commands
- Evidence: `AuthorizationCommandDispatcher.java:323-350`, `:471-501` and `:584-617`
- Problem: Successful Academic `AssignAccountRole` and `GrantSystemAccess` Results do not preserve `importId`, `planItemId` or `executionGeneration`.
- Impact: Academic consumers cannot correlate terminal Results with the import plan or protect completion of a newer execution generation.
- Contract: `commands.md`, Academic Result requirements around lines 127-130 and 338-343.
- Closure: Include all three fields in every terminal Academic Result, including successful, rejected and replayed Results.

### G-03 - Successful Academic Results omit aggregate metadata

- Severity: `HIGH`
- Status: `OPEN`
- Area: Result, audit and Outbox metadata
- Evidence: `AuthorizationCommandDispatcher.java:472-477` and `:585-592`
- Problem: Successful Academic Assign/Grant paths create Results without `aggregateId` and `aggregateVersion`.
- Impact: Audit and Outbox records lose the canonical aggregate identity/version even when the Result data contains an entity identifier.
- Contract: `data-model.md`, Result aggregate metadata requirements around lines 1087-1090.
- Closure: Populate aggregate metadata from the mutated aggregate and use the same values in the Result, audit record and Outbox intent.

### G-04 - Stale Academic generations bypass idempotency

- Severity: `HIGH`
- Status: `OPEN`
- Area: Academic generation fencing and idempotency
- Evidence: `AuthorizationCommandDispatcher.java:106-124`, `:247-265`
- Problem: Stale-generation rejection happens before `insertIfAbsent`, and the terminal stale path does not persist a command idempotency record. An existing `DEFERRED` record can also be returned before the generation is rechecked.
- Impact: Repeated stale commands can emit repeated terminal Results/audit entries, and an obsolete deferred intent can remain active after a newer generation exists.
- Contract: `commands.md`, generation and idempotency requirements around lines 106, 383-384 and 430-442.
- Closure: Fence the generation and idempotency key in one durable decision. Replays must return the original Result, and obsolete deferred commands must be terminally resolved without duplicate side effects.

### G-05 - Academic causal dependency validation is incomplete

- Severity: `HIGH`
- Status: `OPEN`
- Area: `importExecution` validation
- Evidence: `AuthorizationCommandDispatcher.java:739-758` and `CausalDependency.java:14-24`
- Problem: `deferredOrMissing` accepts broad `Account*` event types and matching entity evidence without validating owner, permitted operation, `causationId`, `priorResultMessageId`, prior Result status or `minimumSourceVersion`.
- Impact: An Academic command can become `DEFERRED` from insufficient or unrelated evidence instead of being rejected fail-closed.
- Contract: `messaging-architecture.md`, causal dependency/evidence requirements around lines 650-686.
- Closure: Validate the full causal chain, authoritative owner, allowed scope, prior successful Result, expected event/aggregate and minimum source version before allowing `DEFERRED`.

### G-06 - Practices provisioning does not require Account projection visibility

- Severity: `BLOCKER`
- Status: `OPEN`
- Area: Derived provisioning
- Evidence: `src/main/java/com/estoma/authorization/application/projection/PracticesProvisioningService.java:25-74`
- Problem: Provisioning checks eligibility, role and catalog state but does not verify the local Account projection.
- Impact: A `SystemAccess` can be created for an account that is absent or not trustworthy during projection lag or rebuild.
- Contract: `commands.md`, Practices provisioning requirements around lines 273-283.
- Closure: Require a present, applicable Account projection before provisioning. Missing or rebuilding Account state must remain pending/reconciliation rather than creating access.

### G-07 - Eligibility pending reason is never persisted

- Severity: `HIGH`
- Status: `OPEN`
- Area: Projection reconciliation
- Evidence: `ProjectionEventProcessor.java:88-100` and `PracticesProvisioningService.java:75-82`, `:135-138`
- Problem: Eligibility projection processing always writes `pendingReason = null`; provisioning only clears the field and never records why evaluation is pending.
- Impact: `EVENT_PENDING` and `PROJECTION_RECONCILIATION` cannot drive wake-up, recovery or operational diagnosis.
- Contract: `events.md`, pending reason requirements around lines 182-204.
- Closure: Persist the exact pending reason and causal evidence when evaluation cannot complete, and clear it only after a successful authoritative reconciliation.

### G-08 - Projection processor defers before applying monotonicity

- Severity: `HIGH`
- Status: `OPEN`
- Area: External projection Inbox processing
- Evidence: `ProjectionEventProcessor.java:67-75`
- Problem: `causallyApplicable=false` returns `DEFERRED` before consulting the current projection version. This can defer stale events and prevent eligibility snapshots from being applied when the contract requires snapshot application plus a separate pending reason.
- Impact: Stale events can degrade projection trust, and eligibility changes can remain unapplied indefinitely.
- Contract: `interfaces.md`, projection monotonicity and eligibility requirements around lines 118-140 and 169-188.
- Closure: Check current version and terminal Inbox state first; ignore stale events monotonically, apply eligible snapshots, and represent causal waiting separately through `pendingReason`.

### G-09 - Inbox deduplication is not independent from command idempotency

- Severity: `HIGH`
- Status: `OPEN`
- Area: Inbox processing
- Evidence: `AuthorizationCommandDispatcher.java:104` and `:183-206`
- Problem: The result of `insertIfAbsent` is ignored, so an already `APPLIED` `messageId` can be processed again when presented with a different command idempotency key.
- Impact: The same inbound message can cause a second mutation or duplicate Event/Result intents.
- Contract: `data-model.md`, Inbox deduplication requirements around lines 485-529.
- Closure: Treat Inbox `messageId` deduplication as an independent terminal gate. An already applied message must return its recorded outcome without executing a new intent.

### G-10 - BFF Practices Grant does not validate eligibility

- Severity: `HIGH`
- Status: `OPEN`
- Area: Authority and GrantSystemAccess
- Evidence: `AuthorityEvaluator.java:113-115` and `AuthorizationCommandDispatcher.java:573-580`
- Problem: The `SUPERADMIN` BFF path returns authority before eligibility is checked, and the Grant handler validates role/system compatibility only.
- Impact: A Practices system access can be granted to an account without supported academic eligibility.
- Contract: `commands.md`, Practices compatibility/eligibility requirements around lines 361-365.
- Closure: Enforce the documented compatible-and-eligible predicate for Practices grants, including the BFF path, while preserving the intended explicit administrative authority rules.

### G-11 - Replayed rejected Results do not preserve error details shape

- Severity: `MEDIUM`
- Status: `OPEN`
- Area: Result replay
- Evidence: `AuthorizationCommandDispatcher.java:252-256` and `:331-340`
- Problem: Rejected error details are stored as a JSON wrapper such as `{"details":...}` but replayed as that full serialized wrapper rather than the original `error.details` value.
- Impact: A replay with the same fingerprint does not return the same Result representation.
- Contract: `commands.md`, deterministic idempotent replay requirements around lines 85-92.
- Closure: Store and reconstruct the exact terminal Result error object so first execution and replay have identical code, details and data.

### G-12 - Technical failures have no durable FAILED Result path

- Severity: `HIGH`
- Status: `OPEN`
- Area: Terminal failure handling
- Evidence: `AuthorizationCommandDispatcher.java:148-165`
- Problem: The dispatcher catches functional/domain exceptions only; unexpected technical failures escape without a persisted idempotency record, Outbox Result, or audit entry.
- Impact: A terminal technical failure cannot be observed or replayed through the contractual `FAILED` Result state.
- Contract: `commands.md`, technical failure requirements around lines 397-406.
- Closure: Convert terminal technical failures to a durable, sanitized `FAILED` Result with idempotency, audit and Outbox persistence while preserving rollback of business mutations.

## Release Gate

PR2 must not be considered complete while any `BLOCKER` or `HIGH` item is
`OPEN`. Each correction must add or update a regression test and rerun:

```bash
nix develop /home/anthony/Projects/Estoma/development --command ./mvnw -B clean verify
```

This register is a tracking document, not an exception or a waiver of the
canonical contracts.
