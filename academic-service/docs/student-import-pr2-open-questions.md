# Student Import PR2: Approved Implementation Decisions

**Status:** APPROVED FOR IMPLEMENTATION. The questions previously recorded in this file are no longer blockers.

This file closes physical/operational decisions that the platform contracts intentionally left open. The functional source of truth remains `estomatologia-platform-docs/main`, especially:

- `03-services/shared/academic-service/student-import.md`;
- `03-services/shared/academic-service/commands.md`;
- `03-services/shared/academic-service/data-model.md`;
- `02-architecture/messaging-architecture.md`.

If this file and those contracts conflict on business behavior, the shared documentation wins. Do not reinterpret business rules in this branch.

## Current Branch Boundary

The branch already implements the durable acceptance boundary for `StartStudentImport`:

- frozen `FULL` context in `student_import_processes`;
- technical plan-item and validation-error tables;
- one active import across the service;
- `SUPERADMIN` authorization through Academic local projections;
- academic-period change protection while an import is active;
- `StudentImportStarted` and `IMPORT_FAILED -> PENDING_IMPORT` recovery on a new initial attempt.

The agent may now continue with file access, checksum verification, XLSX parsing, validation, deterministic planning, Process Manager execution, Result consumption, Apply/Resume behavior and lifecycle events according to the decisions below.

## 1. Secure File Reference Contract

### Decision

Use an application port named conceptually `StudentImportFileStore` and keep the domain/process manager independent of the physical object store.

Production storage is **S3-compatible object storage**:

- MinIO is valid for self-hosted/k3s deployments;
- AWS S3 is valid when deployed on AWS;
- the adapter must not leak provider-specific URLs into the business contract.

The BFF is the upload gateway. It completes the object upload before publishing `StartStudentImport`. Academic is the logical consumer of the frozen object and receives only an opaque reference.

### `fileReference`

V1 canonical form:

```text
UUID textual representation
```

The configured bucket/prefix is infrastructure configuration, not part of the reference. A typical physical key can be derived as:

```text
<configured-prefix>/<fileReference>.xlsx
```

Do not persist a signed URL, credentials, bucket secrets, local filesystem path or provider-specific URL as `fileReference`.

### Credentials

Academic uses a dedicated service credential/workload identity scoped only to the student-import object prefix. Credentials are injected through the deployment secret mechanism and never travel in RabbitMQ or the database.

### Lifetime

`fileReference` is **not single-use**. The object must remain readable for the entire validation/application lifecycle.

`ResumeStudentImport` does **not** rebuild or reinterpret a validated plan from the XLSX. It resumes the persisted plan. The object is retained only for integrity/audit/recovery needs while the process remains active.

Never delete the object while the process is:

```text
VALIDATING
VALIDATED
APPLYING
APPLICATION_FAILED
```

Default cleanup after terminal `REJECTED` or `COMPLETED`:

```text
7 days
```

The 7-day retention is configuration, not a domain invariant. Changing the retention duration later does not require redesigning the Process Manager.

### File-store failures

A missing/unreachable/temporarily unreadable object after `StartStudentImport` is a **technical storage failure**, not a fabricated XLSX business rejection. Retry it according to §8 and keep the same frozen reference.

A byte-level checksum mismatch is a functional validation rejection:

```text
INVALID_IMPORT_FILE_CHECKSUM
```

Do not silently accept a replacement object under the same reference.

## 2. Checksum Algorithm And Authority

### Decision

V1 checksum is:

```text
SHA-256 over the exact stored object bytes
lower-case hexadecimal
64 characters
```

Example shape:

```text
0123456789abcdef... (64 hex chars total)
```

Do not use S3/MinIO ETag as the authoritative checksum.

### Responsibility

The BFF computes SHA-256 while streaming the upload and sends that value in `StartStudentImport` as the frozen assertion.

Academic recomputes SHA-256 while reading the object. **Academic's verification is authoritative for accepting the object into validation.**

Flow:

```text
BFF upload stream
→ compute SHA-256
→ object committed
→ StartStudentImport(fileReference, fileChecksum)

Academic validation
→ stream exact stored bytes
→ recompute SHA-256
→ compare constant/canonical representation
```

Malformed checksum syntax in `StartStudentImport` is an invalid message. A well-formed checksum that does not match the stored bytes produces `INVALID_IMPORT_FILE_CHECKSUM` during validation.

No alternate checksum algorithm is supported in V1.

## 3. XLSX Parser And Resource Limits

### Parser

Use Apache POI with its event/streaming XLSX APIs. Do **not** load the complete workbook into memory with the normal in-memory workbook model for production validation.

Pin the selected Apache POI version in Maven and use the version's built-in ZIP-bomb protections in addition to the explicit limits below.

### Workbook contract

The workbook must contain **exactly three worksheets and all three must be visible**:

```text
NRC
ALUMNOS
INSCRIPCIONES
```

Reject extra, hidden or renamed worksheets.

Headers:

- compare the documented names exactly after trimming surrounding whitespace;
- preserve documented case and accents;
- column order is irrelevant;
- duplicate headers are rejected;
- unknown columns are rejected;
- completely blank rows after the header are ignored.

Cells:

- formulas are rejected;
- macros are rejected;
- external links are rejected;
- malformed archives are rejected;
- `matricula` and `nrc` must be text cells; never coerce numeric cells because leading zeroes may be significant;
- names and institutional email are text cells;
- `semestre` must represent an integer from `1` to `14` and may be read from the spreadsheet's numeric integer representation;
- dates are not accepted in fields documented as text or numeric semester.

Normalization:

- trim surrounding whitespace;
- normalize text to Unicode NFC before validation/comparison;
- institutional email uses the platform rule `trim + lower-case`;
- matrícula uses the existing Academic student-number normalizer;
- NRC must preserve its textual value and leading zeroes; do not invent a numeric representation.

### Default resource limits

All limits are configuration with these V1 defaults:

```text
compressed object size     = 20 MiB
expanded ZIP budget        = 200 MiB
worksheets                  = exactly 3
NRC data rows               = 10,000
ALUMNOS data rows           = 25,000
INSCRIPCIONES data rows     = 200,000
maximum cell string length  = 4,096 characters
validation parse budget     = 60 seconds per parsing attempt
```

Also reject excessively sparse sheets whose declared coordinates would bypass row/cell budgets. Counters must be based on observed coordinates and processed records, not only the number of materialized Java objects.

Resource-limit or malformed-file violations are controlled validation errors and must not exhaust heap, disk or worker threads.

## 4. Canonical Producer Identity And Results Destination

### Decision already fixed by platform contracts

Internal coordinated Commands use:

```text
producer = academic
actor = SYSTEM { service: academic }
```

Do **not** use `student-import-process` as the producer. That is an internal module name, not a platform `logicalComponentId`.

Results for Commands/Queries initiated by Academic use:

```text
queue name  = results.academic
routing key = academic
```

Important: `results.academic` is the queue name. The routing key on `platform.results` is `academic`.

Academic must declare/bind `results.academic`, consume it durably with Inbox semantics and correlate Results to Plan Items.

### Result metadata

Keep these in the common envelope:

```text
operationId
idempotencyKey
correlationId
causationId
```

For Results responding to import Plan Items, `payload.data` must additionally include:

```text
importId
planItemId
executionGeneration
```

plus the owner-specific successful data documented for that Command.

Do not duplicate `operationId` or `idempotencyKey` inside `data` merely for import correlation.

A Result may complete a Plan Item only when all of these match the persisted current attempt:

```text
importId
planItemId
executionGeneration
expected operation/message type
current logical intention
```

A Result from an older generation is ACKed/deduplicated for delivery but can never complete the current generation.

### Existing Authentication mismatch

`authentication-service/develop` still contains the obsolete implementation identity `student-import-process` / `results.student-import-process`. This is implementation debt, **not a contract decision**. Do not change Academic to match the obsolete implementation.

The later cross-service adaptation must change Authentication to canonical `academic` routing/producer semantics.

## 5. `importExecution` V1 Wire Contract

Use the conceptual contract from `messaging-architecture.md` as the V1 wire structure.

Canonical producer output:

```text
importExecution {
  importId
  planItemId
  executionGeneration
  authorizedByAccountId
  scope
  causalDependencies[]
  authoritativeEvidence[]
}
```

Academic should always produce arrays for dependencies/evidence, including empty arrays. Receivers may temporarily accept the documented singular `causalDependency` form for backward compatibility, but new Academic output should use `causalDependencies[]`.

Each causal dependency contains:

```text
priorCommandType
priorResultMessageId
priorResultStatus = SUCCEEDED
expectedEventType
expectedAggregateId
minimumSourceVersion?
```

Each authoritative evidence item contains:

```text
sourceService
sourceOperation
sourceResultStatus = SUCCEEDED | FOUND
sourceResultMessageId?
entityType
entityId
evidenceVersion?
```

### Scope values

`importExecution.scope` is a narrow allowlist capability, never a generic `GLOBAL` authority.

Use these V1 scopes by owner:

```text
Users
  CREATE_PERSON
  UPDATE_PERSON

Authentication
  CREATE_ACCOUNT
  ACTIVATE_ACCOUNT
  DEACTIVATE_ACCOUNT

Authorization
  ASSIGN_STUDENT_ROLE
  GRANT_STUDENT_SYSTEM_ACCESS

Academic local execution
  REGISTER_COURSE_SECTION
  ACTIVATE_COURSE_SECTION
  DEACTIVATE_COURSE_SECTION
  CREATE_STUDENT_PROFILE
  CHANGE_STUDENT_SEMESTER
  ACTIVATE_STUDENT_PROFILE
  DEACTIVATE_STUDENT_PROFILE
  ADD_STUDENT_ENROLLMENT
  REACTIVATE_STUDENT_ENROLLMENT
  REMOVE_STUDENT_ENROLLMENT
  FINALIZE_STUDENT_IMPORT
```

`PRACTICAS_PROFESIONALES` is never a `GRANT_STUDENT_SYSTEM_ACCESS` Plan Item.

The `ActorContext` technical identity and `importExecution.scope` are different concepts. Do not place the capability into the actor as a substitute for `importExecution`.

### Receiver behavior

Each owner validates:

```text
producer == academic
actor == SYSTEM/academic
operation is allowlisted
scope matches operation
importExecution fields are structurally valid
causal/evidence entities match the target operation
executionGeneration is not stale
```

For a valid missing prerequisite:

```text
future Event guaranteed by causal dependency
→ DEFERRED / EVENT_PENDING

entity authoritatively known but projection missing/stale and no future Event is guaranteed
→ DEFERRED / PROJECTION_RECONCILIATION

confirmed absence or incompatible state
→ functional REJECTED
```

The receiver persists its own deferred state. Academic never asks the receiver to read `academic-db`.

## 6. Cross-Service Readiness And Dependency Order

This is an implementation dependency, not a new business decision.

Current reality:

- Users has most of the required base behavior and the authoritative Person-resolution Query contract;
- Authentication implements the account capabilities but its import producer/result identity is stale and it does not yet implement the final `importExecution` semantics;
- Authorization implementation is still not at the documented import-capability boundary;
- Academic must add the local SYSTEM/academic execution path and the Result consumer/Process Manager.

### Approved implementation order

On this Academic PR2 branch, proceed in this order:

```text
1. StudentImportFileStore port + S3-compatible adapter contract
2. checksum validation
3. streaming XLSX parser + safety limits
4. full functional validation
5. authoritative Person-resolution Queries required during validation
6. deterministic complete Plan construction + expected versions + generated IDs
7. results.academic queue/listener/Inbox/correlation
8. Academic SYSTEM/academic local Plan Item execution
9. Process Manager scheduling, Apply, failure persistence and Resume
10. finalization: COMPLETED + READY + Outbox in one local transaction
```

The Academic side should publish the canonical wire contract now even if an existing target implementation is stale.

Do not bypass an unready service by:

- accessing its database;
- adding synchronous HTTP/gRPC coordination;
- fabricating a successful Result;
- changing Academic's producer identity to an obsolete requester name.

Cross-service compatibility changes and full live E2E integration are separate integration work. PR2 can and should test Academic orchestration with contract fixtures/test doubles until those owners are compatible.

## 7. Read Models, Validation Error Batches And Progress

### Query/read-model decision

Do not add a synchronous Academic query API merely to expose import status to the client.

The BFF's documented read models remain the V1 client-facing mechanism:

```text
student_import_read_model
student_import_validation_error_read_model
```

They are fed by Academic events.

### Validation-error batches

`StudentImportRejected` contains only aggregate rejection information and error count.

Publish `StudentImportValidationErrorsAvailable` in deterministic batches of:

```text
100 errors per event by default
maximum configurable batch size = 200
```

Batch ordering is based on the persisted `sequence`. Each batch carries stable `batchNumber` and `batchCount` so the BFF can reconstruct completeness.

The BFF may paginate its projection by `sequence`; recommended UI/API default page size is 50 and maximum 200. This pagination rule is a BFF adapter concern, not Academic domain state.

### Progress events

Always publish lifecycle progress at:

```text
application start
resume accepted
application failure
completion
```

During large application runs, publish an additional `StudentImportProgressUpdated` when either condition is reached since the last progress event:

```text
100 more Plan Items completed
OR
5 additional percentage points completed
```

Throttle non-terminal progress events to at most one every 5 seconds. Terminal/lifecycle events are never suppressed by throttling.

Never publish one progress Event per Plan Item.

### Access and retention

V1 administrative visibility is `SUPERADMIN` only. Expanding import/error visibility to another role later is an authorization/business change, but it does not block PR2.

Recommended technical retention defaults:

```text
terminal XLSX object          = 7 days
validation error/detail rows  = 30 days after terminal state
technical Plan Item detail    = 30 days after terminal state
```

Retention durations are configuration. Preserve whatever minimum audit identifiers/summaries are required after detailed technical cleanup; never retain XLSX content inside Postgres.

## 8. Retry, Missing Results, Reconciliation And Manual Resolution

### Validation/storage/query retries

Technical failures before official data is changed do not become fake functional XLSX errors.

Use exponential/capped retry with these defaults:

```text
5 seconds
30 seconds
5 minutes
then 15 minutes capped
```

After 8 consecutive failures, raise an operational alert, but keep the same import/context and continue capped recovery attempts. Do not accept a different file under the same import.

Functional validation errors, including malformed XLSX/schema and checksum mismatch, produce the documented `REJECTED` flow and no official data changes.

### Missing Command Result during APPLYING

A missing Result is ambiguous: the remote effect may already have committed.

Do not create a new generation just because the Result is late.

For a Plan Item waiting for a Result:

```text
wait 2 minutes
→ republish the same logical Command
→ same executionGeneration
→ same commandIdempotencyKey
→ new messageId
```

Retry waits:

```text
2 minutes
5 minutes
10 minutes
10 minutes
10 minutes
```

If no Result is observed after 5 publish attempts or 30 minutes (whichever threshold is reached first), transition the import to:

```text
APPLICATION_FAILED
recoverable = true
failureCode = RESULT_TIMEOUT
```

The affected Plan Item preserves the ambiguous generation and all command metadata. `ResumeStudentImport` must **not** create a new generation for it until reconciliation proves whether the intended effect is already satisfied.

### Terminal Results

```text
SUCCEEDED
→ Plan Item COMPLETED

REJECTED
→ effect confirmed not applied for this intention
→ Plan Item FAILED
→ import APPLICATION_FAILED
→ after cause correction, Resume may create a new generation

FAILED
→ terminal technical result; treat effect as potentially ambiguous unless the owner contract explicitly proves no effect
→ Plan Item FAILED
→ import APPLICATION_FAILED
→ reconcile before any new generation

EXPIRED
→ terminal; Plan Item FAILED
→ import APPLICATION_FAILED
→ new generation only if the effect is confirmed unsatisfied and a new attempt is still valid
```

No blind republish with a new idempotency key after `FAILED` or ambiguous timeout.

### Reconciliation and compatible guard rebase

Reconciliation uses authoritative owner state/projection-recovery mechanisms. It never edits a remote database and never uses Academic assumptions as evidence.

A compatible expected-version rebase is allowed only under the conditions already documented in `student-import.md`:

```text
same businessKey
same plannedEffect
same target entity
same generatedEntityId
current state compatible with the exact planned effect
```

Persist rebase audit append-only. Recommended table/record shape:

```text
importId
planItemId
executionGeneration
previousExpectedVersions
newExpectedVersions
reason
resolvedByAccountId
resolvedAt
```

Never mutate `businessKey`, `plannedEffect` or generated IDs during a rebase.

### Manual resolution authority

The only normal administrative continuation is `ResumeStudentImport` by `SUPERADMIN` via BFF after the underlying cause/reconciliation is resolved.

Never instruct operators to edit `academic-db` or another service database to force completion.

There is no fixed business SLA in V1. Operational alert timing is technical configuration; default alerts should fire for imports with no progress for 30 minutes in `VALIDATING`/`APPLYING` and immediately on `APPLICATION_FAILED`.

## 9. Plan Item Naming Correction

The old residual Plan Item name:

```text
UPDATE_STUDENT_PROFILE
```

is invalid in V1 because `UpdateStudentAcademicProfile` was removed from the command surface.

Use:

```text
CHANGE_STUDENT_SEMESTER
```

for semester changes.

Personal-data changes remain `UPDATE_PERSON`. Academic status changes remain `ACTIVATE_STUDENT_PROFILE` / `DEACTIVATE_STUDENT_PROFILE`.

Do not reintroduce a generic StudentAcademicProfile update operation.

## 10. Business Decisions

There is **no unresolved core business decision blocking PR2**.

The following business rules are already fixed and must not be reopened by implementation:

- import mode is `FULL` only;
- first FULL import is required before the period becomes `READY`;
- validation is all-or-nothing from the business perspective;
- a rejected reimport over an already `READY` period preserves the previous official snapshot;
- new student Accounts are created `INACTIVE` and without credentials;
- an existing student Account can be automatically reactivated only under the documented academic-absence rule and normal credential preconditions;
- `ADMINISTRATIVE`/`SECURITY` account deactivation is not silently reversed by import;
- an INACTIVE Person is never automatically reactivated by the import;
- existing institutional email conflicts require administrative review;
- the import never changes `studentNumber`;
- only explicit `LAVADO_ULTRASONICO` SystemAccess may be a Grant Plan Item in V1;
- Practices access is derived by Authorization from `StudentAcademicEligibilityChanged`, never by a Plan Item;
- `SUSPENDED`/`REVOKED` SystemAccess is preserved and never auto-restored;
- `FAILED` or ambiguous effects require reconciliation before a new execution generation;
- `COMPLETED + AcademicPeriod READY` is one local Academic transaction.

Two defaults remain intentionally configurable and are not blockers:

1. terminal XLSX/detail retention duration;
2. future expansion of read visibility beyond `SUPERADMIN`.

Changing either later does not require changing the import business model.

## 11. Agent Instruction

Proceed with implementation. Do not stop again for the questions closed in this document.

Only stop and report if implementation uncovers a **new** contradiction that changes one of the business rules in §10 or would require introducing a new cross-service capability not present in the approved contracts.
