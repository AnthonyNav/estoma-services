# PR3 Contract Inconsistencies

Status: BLOCKED
Date: 2026-08-14
Scope: academic-service student-import PR3

No implementation changes were made after these inconsistencies were found.

## 1. Obsolete Plan Item Type

`03-services/shared/academic-service/data-model.md` lists
`UPDATE_STUDENT_PROFILE` in the allowed `student_import_plan_item_type` values
(sections 8.1 and 9).

The same contract family defines `ChangeStudentSemester` as the specific
semester operation, and the approved PR2 implementation uses
`CHANGE_STUDENT_SEMESTER`. There is no generic `UpdateStudentAcademicProfile`
operation in V1.

Required decision: remove `UPDATE_STUDENT_PROFILE` from the canonical data
model and replace it with `CHANGE_STUDENT_SEMESTER`, or explicitly reopen the
V1 command model before continuing PR3.

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

Required decision: confirm that `operationId` and `idempotencyKey` remain only
in the envelope, with import correlation fields in `payload.data`, or define a
new canonical payload shape before PR3 contract schemas are created.

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

Required decision: confirm that validation and application are internal
durable process-manager operations with no RabbitMQ Command/Result surface, or
require their explicit wire contracts, dispatcher handlers, idempotency rules,
and topology before PR3.

References:

- `03-services/shared/academic-service/commands.md:64-67,347,364-368`
- `03-services/shared/academic-service/student-import.md:1115-1127`
- PR2 branch `AcademicCommandDispatcher` allow-list and scheduled worker/
  manager implementation

## Resume Gate

PR3 implementation must remain paused until the canonical documentation is
resolved and the decision is reflected in the implementation branch. After
resolution, re-check the academic command, data-model, event, query and
messaging contracts before creating JSON Schemas or changing RabbitMQ wiring.
