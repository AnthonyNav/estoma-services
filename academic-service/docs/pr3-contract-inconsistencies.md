# PR3 Contract Resolutions

Status: APPROVED FOR IMPLEMENTATION
Date: 2026-08-15
Scope: academic-service student-import PR3

The inconsistencies previously recorded in this file are resolved. They are no longer blockers for PR3 implementation.
The normative decisions are also recorded in
`estomatologia-platform-docs/03-services/shared/academic-service/pr3-contract-resolution.md`,
merged through documentation PR #26.

The resolutions below are technical/contractual alignments derived from the existing V1 model and the merged PR2 implementation. They do not introduce new business rules.

## 1. Plan Item Type

Decision: `UPDATE_STUDENT_PROFILE` is obsolete and must not be used in V1.

Canonical persisted Plan Item type:

```text
CHANGE_STUDENT_SEMESTER
```

Rationale:

- V1 has no generic `UpdateStudentAcademicProfile` operation.
- `ChangeStudentSemester` is the owner-specific mutation for semester changes.
- PR2 already persists and executes `CHANGE_STUDENT_SEMESTER`.

Implementation rule:

```text
UPDATE_STUDENT_PROFILE       -> forbidden / stale documentation
CHANGE_STUDENT_SEMESTER     -> canonical V1 Plan Item
```

Any schema, enum, fixture or integration contract created in PR3 must use `CHANGE_STUDENT_SEMESTER`.

## 2. Result Correlation Fields

Decision: `operationId` and `idempotencyKey` remain envelope metadata and must not be duplicated in `payload.data`.

Canonical coordinated Result shape:

```text
envelope.operationId
envelope.idempotencyKey

payload.status = SUCCEEDED | REJECTED | FAILED | EXPIRED
payload.data {
  importId
  planItemId
  executionGeneration
  ...command-specific canonical data when applicable
}
payload.error?
```

Required correlation responsibilities:

- `messageId`: delivery deduplication / Consumer Inbox.
- `operationId`: logical operation correlation.
- `idempotencyKey`: functional intention within an execution generation.
- `importId + planItemId + executionGeneration`: identifies the current import Plan Item execution.

PR3 receivers and contract schemas must reject or ignore stale generations according to their owner contract, while Academic completes a Plan Item only from a correlated terminal Result belonging to a Command actually published for that Plan Item/generation.

No new payload copy of `operationId` or `idempotencyKey` is introduced.

## 3. Validation / Application Boundary

Decision: `ValidateStudentImport` and `ApplyStudentImport` are internal durable Process Manager phases in V1. They are not RabbitMQ Command/Result surfaces.

External durable administrative Commands remain:

```text
StartStudentImport
ResumeStudentImport
```

Internal execution remains:

```text
StartStudentImport accepted
  -> VALIDATING
  -> StudentImportValidationWorker
  -> VALIDATED or REJECTED
  -> StudentImportApplicationManager
  -> APPLYING
  -> COMPLETED or APPLICATION_FAILED
```

Consequences:

- Do not add `ValidateStudentImport` or `ApplyStudentImport` to `AcademicCommandDispatcher`.
- Do not create `ValidateStudentImportResult` or `ApplyStudentImportResult` wire schemas.
- Do not add RabbitMQ routing, Inbox/Outbox request handling or BFF orchestration for those two internal phases.
- Keep domain/process Events such as `StudentImportValidated`, `StudentImportRejected`, `StudentImportApplicationStarted`, `StudentImportApplicationFailed`, `StudentImportCompleted` and progress Events.
- `FINALIZE_STUDENT_IMPORT` remains an internal Plan Item at the end of the application phase; it is not a public Command.

## 4. PR3 Integration Contract

PR3 should proceed using these canonical rules:

### Academic producer

```text
producer = academic
actor = SYSTEM { service: academic }
```

Plan authorization/context belongs in:

```text
payload.importExecution {
  importId
  planItemId
  executionGeneration
  authorizedByAccountId
  scope
  causalDependencies[]
  authoritativeEvidence[]
}
```

`scope` does not belong in `ActorContext`.

### Coordinated Result routing

```text
exchange = platform.results
routing key = academic
queue = results.academic
```

### Allowed cross-service operations

Users:

```text
CreatePerson
UpdatePerson
```

Authentication:

```text
CreateAccount
ActivateAccount
DeactivateAccount
```

Authorization:

```text
AssignAccountRole
GrantSystemAccess
```

For Authorization, the Academic import may grant only:

```text
LAVADO_ULTRASONICO
```

`PRACTICAS_PROFESIONALES` remains eligibility-derived and must never be planned as `GrantSystemAccess` by the import.

## 5. Canonical Documentation Cleanup

The shared platform documentation still contains stale prose corresponding to the three issues above. PR3 implementation must follow the approved resolutions in this file and the merged PR2 behavior; the stale text must be aligned as documentation cleanup and must not be used to re-open the V1 behavior.

Required canonical cleanup targets:

- `03-services/shared/academic-service/data-model.md`
  - replace `UPDATE_STUDENT_PROFILE` with `CHANGE_STUDENT_SEMESTER`.
- `03-services/shared/academic-service/commands.md`
  - remove `ValidateStudentImport` / `ApplyStudentImport` as RabbitMQ Commands;
  - remove duplicated `operationId` / `idempotencyKey` from coordinated `payload.data` descriptions.
- `03-services/shared/academic-service/student-import.md`
  - describe validation/application as internal durable phases, not wire Commands.
- `03-services/shared/users-service/commands.md`
  - coordinated Result data contains `importId`, `planItemId`, `executionGeneration`; `operationId` and `idempotencyKey` remain in the envelope.

## Historical Cleanup

PR3 is **UNBLOCKED**.

The agent may continue implementation without requesting decisions for the three issues previously recorded here. Any new inconsistency must be evaluated against:

1. merged `develop` implementation,
2. the transversal messaging contract,
3. owner service contracts,
4. the resolutions in this file.

Do not change business behavior merely to match stale implementation or stale prose in another service.

## External Integration Gate

The Academic implementation now publishes Authorization recovery Queries and
consumes their Results. The current `authorization-service` `develop`
implementation does not yet declare `queries.authorization`, its Query
listener, or the corresponding durable Results. Full platform E2E recovery
therefore remains blocked until the compatible Authorization implementation is
merged in its own change set. This PR does not access `authorization-db` or
modify that service.
