# PR3 Contract Inconsistencies

Status: RESOLVED
Date: 2026-08-15
Scope: academic-service student-import PR3

The normative decisions are resolved by
`estomatologia-platform-docs/03-services/shared/academic-service/pr3-contract-resolution.md`,
merged through documentation PR #26. This file remains as the audit trail for
the original gate.

## 1. Obsolete Plan Item Type

`03-services/shared/academic-service/data-model.md` lists
`UPDATE_STUDENT_PROFILE` in the allowed `student_import_plan_item_type` values
(sections 8.1 and 9).

The same contract family defines `ChangeStudentSemester` as the specific
semester operation, and the approved PR2 implementation uses
`CHANGE_STUDENT_SEMESTER`. There is no generic `UpdateStudentAcademicProfile`
operation in V1.

Resolution: `UPDATE_STUDENT_PROFILE` is obsolete. PR3 uses
`CHANGE_STUDENT_SEMESTER`.

References:

- `03-services/shared/academic-service/data-model.md:412-435,619-637`
- `03-services/shared/academic-service/commands.md:53,69`
- `academic-service/docs/student-import-pr2-open-questions.md:606-626` on the
  PR2 branch

## 2. Result Correlation Fields Are Duplicated In One Contract

`03-services/shared/academic-service/commands.md:366` says Results for
internal Commands must include `operationId` and `idempotencyKey` inside
`payload.data`.

The transversal envelope contract defines both as envelope fields, and the
approved PR2 decisions explicitly say that `payload.data` should add only
`importId`, `planItemId` and `executionGeneration`; `operationId` and
`idempotencyKey` must not be duplicated there.

Resolution: `operationId` and `idempotencyKey` remain only in the envelope;
`payload.data` contains `importId`, `planItemId` and
`executionGeneration` when import correlation applies.

References:

- `02-architecture/messaging-architecture.md:549-564,775-787`
- `03-services/shared/academic-service/commands.md:366`
- `academic-service/docs/student-import-pr2-open-questions.md:231-264` on the
  PR2 branch

## 3. Validation/Application Command Boundary Is Unclear

`03-services/shared/academic-service/commands.md:64-67` documents
`ValidateStudentImport` and `ApplyStudentImport` as Commands with Results.
`student-import.md` also describes them as operations.

The approved PR2 implementation does not route either operation through
`AcademicCommandDispatcher`. It runs validation and application through the
durable scheduled `StudentImportValidationWorker` and
`StudentImportApplicationManager`; the dispatcher currently accepts only the
BFF commands, including `StartStudentImport` and `ResumeStudentImport`.

Resolution: validation and application are internal durable Process Manager
phases. The only external workflow Commands are `StartStudentImport` and
`ResumeStudentImport`; no wire contracts are created for the two phases.

References:

- `03-services/shared/academic-service/commands.md:64-67,347,364-368`
- `03-services/shared/academic-service/student-import.md:1115-1127`
- PR2 branch `AcademicCommandDispatcher` allow-list and scheduled worker/
  manager implementation

## Historical Cleanup

The canonical repository still retains historical references in
`data-model.md` and `commands.md`. The resolution document explicitly gives
precedence to the decisions above and records cleanup as non-blocking. PR3
must not reintroduce the obsolete enum or wire Commands.

## External Integration Gate

`academic-service` now publishes the Authorization recovery Queries and
consumes their Results, but the current `authorization-service` `develop`
implementation does not yet declare `queries.authorization`, its Query
listener, or the corresponding durable Results. Full platform E2E recovery
therefore remains blocked until the compatible Authorization implementation is
merged in its own change set. This PR does not access `authorization-db` or
modify that service.
