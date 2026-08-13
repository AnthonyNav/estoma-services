# Student Import PR2: Open Questions And Integration Gaps

**Status:** Needs cross-service decisions before validation and distributed application can be implemented.

## Delivered Boundary

This branch implements the durable acceptance boundary for `StartStudentImport`:

- frozen `FULL` import context in `student_import_processes`;
- technical tables for plan items and validation errors;
- one active import across the service;
- idempotency by `importId`, `fileReference`, and `fileChecksum`;
- `SUPERADMIN` authorization through Academic's local projections;
- protection against an academic-period change while an import is active;
- `StudentImportStarted` and the `IMPORT_FAILED -> PENDING_IMPORT` recovery transition.

It deliberately does not fetch, parse, validate, plan, apply, or resume an import. Accepting an import is not a claim that the referenced file is readable or valid.

## Blocking Decisions

### 1. Secure File Reference Contract

`fileReference` is opaque in the durable command. Academic needs an allowlisted adapter to read the bytes during validation, but the platform documentation does not choose one.

Decide:

- storage owner and backend: BFF-owned temporary storage, S3/MinIO, signed URL, or another mechanism;
- reference grammar and validation, including expiry and single-use behavior;
- service identity/credential used by Academic to retrieve the object;
- whether Academic can read it again during `ResumeStudentImport`, and required retention period;
- deletion policy after `REJECTED`, `COMPLETED`, and unresolved `APPLICATION_FAILED`;
- error mapping when the object is missing, expired, unreadable, or changes after acceptance.

Required outcome: a port with a tested implementation can return a byte stream plus an authoritative checksum without putting the file, URL credentials, or file contents in RabbitMQ, Postgres, Inbox, Outbox, DLQ, metrics, or logs.

### 2. Checksum Algorithm And Authority

The command carries `fileChecksum`, but neither its algorithm nor who computes it is closed.

Decide:

- algorithm and canonical encoding, recommended `SHA-256` lower-case hexadecimal over the exact stored bytes;
- whether BFF supplies an assertion that Academic must recompute, or Academic is the only authority;
- maximum size and streaming behavior before calculating the checksum;
- whether a mismatch is `INVALID_IMPORT_FILE_CHECKSUM` and whether the referenced object may be read again.

Required outcome: the validator can prove that the plan was built from the exact frozen object and can reject any replacement during validation, application, or resume.

### 3. XLSX Parser And Resource Limits

The service has no XLSX dependency or parser yet. The template contract defines data rules but not operational limits.

Decide:

- library and version, including a maintained parser suitable for streaming XLSX input;
- maximum compressed size, expanded size, sheets, rows, cells, string length, formula policy, and processing time;
- protection against ZIP bombs, malformed archives, external links, macros, formulas, and excessively sparse sheets;
- header matching rules: exact case/accents/order versus canonicalized matching;
- blank-row policy and whether unknown columns are rejected;
- charset/normalization policy for NRC, matrícula, names, and email fields;
- whether dates/numeric spreadsheet cells are accepted for values documented as text.

Required outcome: malformed or hostile files fail as controlled validation errors without exhausting service resources or exposing file contents.

### 4. Result Consumer Destination And Producer Identity

The Process Manager must receive correlated Results from Users, Authentication, and Authorization. Current contracts are not aligned:

- shared documentation requires coordinated Commands with `producer = academic` and `actor = SYSTEM { service: academic }`;
- `users-service` already recognizes `academic` for its import operations;
- `authentication-service` currently authorizes and routes the same operations under `producer = student-import-process` and exposes `results.student-import-process`;
- `academic-service` currently has no `results.academic` queue, binding, listener, Inbox consumer, or Result correlation handler;
- `authorization-service` is still a scaffold and has no implementation of its documented import Commands.

Decide:

- one canonical producer identity: `academic` as documented, or `student-import-process` across every service and document;
- one Results routing key: recommended `results.academic`, owned by the logical component, not an internal module name;
- exact common Result envelope and `data` fields for every coordinated Command;
- whether `operationId`, `idempotencyKey`, `importId`, `planItemId`, and `executionGeneration` are envelope fields, Result data, or both;
- versioning/migration plan for Authentication's existing allowlist and result routing.

Required outcome: a Result from an old generation cannot complete a current Plan Item, and every receiver can validate the same identity without consulting `academic-db`.

### 5. `importExecution` Payload Schema And Receiver Validation

The architecture documents a conceptual `importExecution`, but no shared schema or receiver implementation exists.

Decide:

- JSON schema, required versus optional fields, and compatibility/versioning rules;
- exact allowed `scope` values per target command;
- canonical format of `causalDependency`/`causalDependencies` and `authoritativeEvidence`;
- which receiver persists `DEFERRED`, `EVENT_PENDING`, and `PROJECTION_RECONCILIATION`, and how it resumes safely;
- evidence retention and validation rules for `ResolvePersonByInstitutionalEmail -> FOUND` and prior successful Results.

Required outcome: Users, Authentication, Authorization, and Academic reject an over-broad or forged technical command consistently and can recover from event/result reordering without in-memory state.

### 6. Cross-Service Command Readiness

The validated plan depends on operations that have not all reached a compatible implementation boundary.

| Owner | Required capability | Current gap |
| --- | --- | --- |
| Users | Resolve Person, Create Person, constrained Update Person | Base support exists; Result/context correlation for plan items must be confirmed. |
| Authentication | Create/activate/deactivate student account with causal deferral | Producer identity and Result destination mismatch; `importExecution` validation is absent. |
| Authorization | Assign `ALUMNO`; grant only `LAVADO_ULTRASONICO`; consume eligibility for Prácticas | Contract is documented but service implementation is not present. |
| Academic | Local profile/NRC/enrollment commands under technical actor | Current dispatcher only authorizes BFF `SUPERADMIN`; internal allowlisted actor/scope path is not implemented. |

Required outcome: publish versioned, tested wire contracts before enabling `ApplyStudentImport`. The Process Manager must not silently substitute direct database access or synchronous service calls.

### 7. Read Models, Error Batches, And Operational Visibility

The BFF must observe aggregate status, summary, progress, and validation errors, but this repository has no query contract or read-model publisher for imports.

Decide:

- query owner and transport for import summary, progress, and paged validation errors;
- maximum errors per `StudentImportValidationErrorsAvailable` event and pagination/cursor semantics;
- progress publication cadence and which phase boundaries generate `StudentImportProgressUpdated`;
- retention and access control for file metadata, errors, and plan diagnostics;
- operational metrics, alerts, and dashboard fields for imports stuck in `VALIDATING`, `APPLYING`, or `APPLICATION_FAILED`.

Required outcome: the BFF can provide actionable feedback without receiving PII-heavy XLSX data or one event per row/Plan Item.

### 8. Retry, Timeout, Reconciliation, And Manual Resolution

The contracts require durable recovery but leave operating policy open.

Decide:

- timeout for a missing Query or Command Result and whether timeout leads to `APPLICATION_FAILED` or a durable retry;
- retry budgets and backoff per file retrieval, validation query, coordinated command, and Result publication;
- exact procedure and authority for reconciliation when an external effect is ambiguous;
- audit representation for a compatible expected-version rebase during `ResumeStudentImport`;
- escalation owner and SLA for non-recoverable failures.

Required outcome: a `FAILED` Result never causes an unsafe duplicate external effect, and a restart reconstructs all pending work from durable state.

## Important Implementation Follow-Ups

These are not cross-service decisions, but must be completed before this branch claims full PR2 behavior:

- invoke `ValidateStudentImport` after acceptance and persist `VALIDATED` or `REJECTED` atomically with aggregate events;
- transition the period to `IMPORT_FAILED` only when the first import's validation is rejected; preserve `READY` after a rejected reimport;
- construct the complete plan and expected versions before changing official academic data;
- block or version-fence individual Academic mutations that touch Plan Items while the import is `APPLYING` or `APPLICATION_FAILED`;
- implement `ApplyStudentImport`, `ResumeStudentImport`, local `FINALIZE_STUDENT_IMPORT`, and all lifecycle events;
- add `results.academic` Inbox handling and monotonic Result/event correlation;
- test parser safety, validation rules, full-plan determinism, out-of-order messages, restart recovery, partial application, and atomic `COMPLETED + READY` finalization.

## Review Questions

1. Do we standardize the technical producer and Results route as `academic` / `results.academic`?
2. Which component owns temporary XLSX storage, and what concrete secure-reference contract will it publish?
3. Can we approve the checksum and XLSX resource-limit defaults before adding the parser?
4. Which service delivers the Authorization import allowlist and `importExecution` support, and in which dependency order?
5. What is the approved recovery policy for a missing Result or ambiguous external effect?
