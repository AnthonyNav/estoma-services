# PR2 Implementation Gaps

This register records the gaps found by the implementation audit against the
canonical `authorization-service` contracts in `estomatologia-platform-docs/main`.
It is a release gate: a gap is `RESOLVED` only when the implementation and regression
coverage close the contractual risk.

## Final validation baseline

- Branch: `authorization-service/feat/authorization-application-recovery`
- Last behavior-changing audit head: `916c0d1`
- GitHub Actions `authorization-service` run #43: `./mvnw -B clean verify` **SUCCESS**
- Architecture remains broker-agnostic and hexagonal; RabbitMQ/wire integration stays in PR3.
- Status convention: `RESOLVED` means no known PR2 blocker remains for that finding.

## Audit Findings And Resolution Status

### G-01 - Change-role partial mutation

- Severity: `BLOCKER`
- Status: `RESOLVED`
- Resolution: optimistic CAS losses now raise `OptimisticLockConflictException`, which escapes the business transaction. `AuthorizationCommandProcessor` persists the terminal rejection only after rollback in a fresh transaction. A later failure can no longer commit the role plus only a subset of required access suspensions.
- Coverage: `AuthorizationContractGapIT.changeRoleRollsBackEveryMutationAndPersistsFailedWhenLaterDatabaseStepFails` forces a failure after the role mutation and verifies both role and access state roll back and no domain Event escapes.

### G-02 - Academic Result correlation metadata is incomplete

- Severity: `HIGH`
- Status: `RESOLVED`
- Resolution: every terminal Academic Result path carries `importId`, `planItemId` and `executionGeneration`, including success, ensure/preserve, rejection, stale generation, recovery `NOT_FOUND`, technical `FAILED` and idempotent replay.
- Coverage: `AuthorizationApplicationIT`, `AuthorizationRecoveryIT` and `AuthorizationGenerationFencingIT` assert the import-plan correlation fields.

### G-03 - Successful Academic Results omit aggregate metadata

- Severity: `HIGH`
- Status: `RESOLVED`
- Resolution: successful `AssignAccountRole` Results use `accountId/account_roles.version`; successful `GrantSystemAccess` Results use `systemAccessId/system_accesses.version`. Academic `changed=false` ensure paths preserve the canonical existing aggregate identity/version instead of returning null metadata.
- Coverage: `AuthorizationApplicationIT.academicGrantIsEnsurePreserveAndDoesNotRestoreSuspendedAccess` asserts canonical aggregate metadata on the ensure path.

### G-04 - Stale Academic generations bypass idempotency

- Severity: `HIGH`
- Status: `RESOLVED`
- Resolution: generation fencing is evaluated together with durable idempotency. Existing `DEFERRED` records are rechecked before replay; an exact redelivery of an older deferred generation is serialized with an Inbox row lock and terminally resolved as `STALE_EXECUTION_GENERATION` without mutation.
- Coverage: `AuthorizationGenerationFencingIT.exactRedeliveryOfDeferredOlderGenerationBecomesTerminallyStale` plus the stale-generation coverage in `AuthorizationApplicationIT`.

### G-05 - Academic causal dependency validation is incomplete

- Severity: `HIGH`
- Status: `RESOLVED`
- Resolution: the Academic Account-provisioning dependency now requires the allowlisted chain `CreateAccount Result SUCCEEDED -> AccountCreated`, exact target aggregate, `Command.causationId == priorResultMessageId`, supported scope/actor and `minimumSourceVersion` when present. Authoritative Account evidence is restricted to the Authentication owner and the documented `CreateAccount/SUCCEEDED` evidence shape.
- Coverage: valid causal deferral/wake-up is covered by `AuthorizationRecoveryIT`; invalid causation is rejected by `AuthorizationContractGapIT.invalidAcademicCausationIsRejectedInsteadOfDeferred`.

### G-06 - Practices provisioning does not require Account projection visibility

- Severity: `BLOCKER`
- Status: `RESOLVED`
- Resolution: derived Practices provisioning requires an existing, trusted Account projection and trusted eligibility projection before creating access. Missing/untrusted state remains fail-closed and can enter controlled reconciliation.
- Coverage: Application/recovery integration tests exercise the fail-closed and recovery paths.

### G-07 - Eligibility pending reason is never persisted

- Severity: `HIGH`
- Status: `RESOLVED`
- Resolution: eligibility preserves `EVENT_PENDING` only when an Account Event is actually pending and `PROJECTION_RECONCILIATION` when authoritative/local evidence requires projection repair. The pending reason is cleared only when the dependency is resolved/no longer pending; reconciliation starts/reuses Account ENTITY recovery where appropriate.
- Coverage: `AuthorizationContractGapIT.eligibilityPersistsProjectionReconciliationAndStartsAccountRecoveryWhenExistenceIsKnownLocally`.

### G-08 - Projection processor defers before applying monotonicity

- Severity: `HIGH`
- Status: `RESOLVED`
- Resolution: stale-version resolution precedes causal deferral; eligibility snapshots remain independently applicable and causal waiting is represented separately.
- Coverage: out-of-order projection tests in `AuthorizationRecoveryIT`.

### G-09 - Inbox deduplication is not independent from command idempotency

- Severity: `HIGH`
- Status: `RESOLVED`
- Resolution: `AuthorizationCommandProcessor` is the canonical inbound boundary. Exact `messageId` redelivery is independent from functional `idempotencyKey` replay; a new delivery of a completed intention emits only a fresh Result and never repeats mutation/Events.
- Coverage: `AuthorizationRecoveryIT.exactRedeliveryIsSilentButNewDeliveryOfSameIntentPublishesOnlyAnotherResult`.

### G-10 - BFF Practices Grant does not validate eligibility

- Severity: `HIGH`
- Status: `RESOLVED`
- Resolution: a BFF/SUPERADMIN Practices Grant now requires a present, supported, eligible and trusted academic eligibility projection in addition to normal role/system compatibility.
- Coverage: `AuthorizationContractGapIT.superadminCannotGrantPracticesWithoutSupportedEligibility`.

### G-11 - Replayed rejected Results do not preserve error details shape

- Severity: `MEDIUM`
- Status: `RESOLVED`
- Resolution: stored terminal Results are reconstructed canonically before replay, preserving the original error/data representation.
- Coverage: command delivery replay tests.

### G-12 - Technical failures have no durable FAILED Result path

- Severity: `HIGH`
- Status: `RESOLVED`
- Resolution: business mutation is rolled back before a persistible terminal technical failure is recorded as sanitized `FAILED` with Inbox, idempotency, audit and Result Outbox in a fresh transaction. A `TechnicalFailureClassifier` port preserves the platform retry contract: Spring/JDBC transient/recoverable failures escape Application for PR3 retry/backoff; only non-retryable terminal failures are persisted immediately as `FAILED`.
- Coverage: forced post-mutation failure in `AuthorizationContractGapIT` proves rollback + durable `FAILED`; `SpringTechnicalFailureClassifierTest` proves transient vs terminal classification.

### G-13 - Academic Grant does not require ALUMNO role

- Severity: `HIGH`
- Status: `RESOLVED`
- Resolution: `producer=academic` + `GrantSystemAccess` now requires the current target role to be `ALUMNO`, in addition to the existing exact Academic scope and `LAVADO_ULTRASONICO` restriction.
- Coverage: `AuthorizationContractGapIT.academicGrantRejectsTargetThatIsNotAlumno`.

### G-14 - ENTITY recovery does not validate snapshot identity

- Severity: `HIGH`
- Status: `RESOLVED`
- Resolution: before any ENTITY snapshot mutation/job advancement, the Result item must match the requested canonical `entityKey`: Person ID, Account ID, or eligibility profile/system tuple.
- Coverage: `AuthorizationRecoveryIT.entityFoundForAnotherAccountIsRejectedBeforeProjectionMutation`.

### G-15 - Deferred replay lacks an atomic claim/fence

- Severity: `HIGH`
- Status: `RESOLVED`
- Resolution: deferred Event replay uses durable `claim_token` + `lease_until` fencing (Flyway V3); stale workers cannot finalize a recovered entry. Deferred Academic Command replay is serialized with `SELECT ... FOR UPDATE`, and generation is rechecked while holding the local Inbox row lock. This satisfies the contract's claim/lock/fencing-equivalent requirement without leaking transport concerns into Domain/Application.
- Coverage: `AuthorizationContractGapIT.deferredInboxClaimIsFencedByToken`, Flyway fencing constraint coverage, causal wake-up tests and exact stale-generation replay coverage.

## Additional audit hardening

The final audit also closed two interactions not explicitly separated in the original 15-item register:

1. **Retry classification remains a PR3 transport concern.** Application now exposes the retry decision through a hexagonal `TechnicalFailureClassifier`; transient infrastructure errors are not prematurely converted to terminal `FAILED`.
2. **Deferred Commands are serialized too.** The original G-15 wording focused on projection Events, but the same no-double-apply invariant is now enforced for Academic Command wake-up using a row lock plus generation fencing.

## Release Gate

All `BLOCKER` and `HIGH` findings in this register are `RESOLVED` and have implementation/regression evidence.

PR2 may proceed to final review provided the current branch still passes:

```bash
./mvnw -B clean verify
```

PR3 remains responsible only for executable wire contracts and transport integration: RabbitMQ topology/listeners/publishers, AsyncAPI/JSON Schema, Outbox Relay publisher-confirm/lease-fencing, consumer retry/DLQ wiring and RabbitMQ end-to-end tests.
