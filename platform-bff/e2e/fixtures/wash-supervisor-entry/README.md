# Isolated rejected-entry fixture

This fixture supplies the minimum Wash Execution projections needed to exercise
`RegisterWashArrival` followed by a rejected `DecideWashEntry` through the deployed BFF and owner.
It creates no cabin, tank, priority, closure, unavailability or assignment.

It must run after both existing fixtures are active and after the student workflow has scheduled its
appointment:

- `../wash-student/fixture-db.sh` owns the student, slot and appointment prerequisites;
- `../wash-supervisor/fixture-db.sh` owns the supervisor's local BFF authorization;
- this fixture owns only the supervisor authorization and slot projection inserted in
  `wash_execution_db`, plus the execution-side artifacts created by the two commands.

Bootstrap reads the authoritative slot from Appointments, waits for the scheduled appointment Event
to appear in Wash Execution, requires all three owner projections to be `READY`, refuses existing
Lavado access or an existing slot projection, and preserves an absent or exact `E2E_TESTER` role.
The runner must use the manifest's default idempotency keys:

```text
<student RUN_KEY>-register-arrival
<student RUN_KEY>-decide-entry
```

After the HTTP operations converge, `audit` requires one `ENTRY_REJECTED` execution at version 2,
one rejected validation with a reason, no resource assignment, published versioned arrival and
rejection Events, and matching BFF/Appointments projections.
It retains a private `entry-audit.env` containing only the synthetic IDs and original role snapshot
for an independent post-cleanup audit; it contains no credentials or tokens and must not enter Git.

Cleanup must run before the supervisor and student fixtures. It waits for BFF operations, owner
Outbox rows and deferred Inbox work to settle, then verifies final convergence. The Wash Execution
phase removes only rows identified by the synthetic appointment or the two idempotency keys and
restores the exact original owner role, including the consumed synthetic appointment and slot
projections. The BFF phase removes only the two operations and the execution projection. It
deliberately leaves the appointment and its Appointments evidence to the student fixture, which
owns and cleans those rows.

The two database phases are independently retryable. If cleanup fails after the Wash Execution
commit, keep `entry-fixture.env` and invoke cleanup again with the same private executor. Never delete
the manifest manually and never purge shared RabbitMQ queues.

## Failure recovery

The wrapper builds Newman before creating the time-limited slot. If a decision-phase request fails,
it still attempts ordered cleanup: entry, student, supervisor. It never removes a dependent fixture
after the previous cleanup fails. All login bodies, tokens and exported environments are removed
even when database cleanup is refused; only non-secret manifests/evidence remain.

An interrupted `PENDING_ENTRY v1` can be cleaned only when the exact student/appointment/slot
matches, there is no start, validation or resource assignment, and this run's arrival Event was
published with its canonical idempotency key. Cleanup additionally waits for Appointments to store
the arrival evidence and mark that exact source message `APPLIED`, and for BFF to converge.
Other execution paths fail closed. This recovery deletes only synthetic test data; it is not a
business cancellation or an operator recovery procedure for real users.

To retry a partial cleanup, preserve the report directory and use the same private executor and
database names. Run `fixture-db.sh cleanup` with `E2E_REPORT_DIR` (and any overridden manifest
paths) first; only after it succeeds run the student fixture cleanup with
`E2E_FIXTURE_MANIFEST=<report>/student-fixture.env`, then the supervisor fixture cleanup.
The owner-role restore compares the installed code/version/timestamp in the same transaction;
it refuses a concurrent role change instead of overwriting it.

If prepare began but entry bootstrap never produced a manifest, the wrapper deliberately keeps
both upstream manifests. Inspect their exact appointment/student/slot in Appointments and Wash
Execution. If the appointment was scheduled and projected, fix the readiness/bootstrap problem,
then run entry `bootstrap` and `cleanup` before upstream cleanup. If no owner projection or
execution exists, independently verify that absence before cleaning the student and supervisor
fixtures. Do not delete manifests or force projection readiness. A global recovery during this
workflow can invalidate the preconditions; resolve it before retrying with fresh fixtures.

After cleanup, independently check the captured IDs in all three databases and the relevant queue
backlogs. Deleting the consumed synthetic `AppointmentScheduled` marker does not prevent a later
broker redelivery from rebuilding its projection; a delayed residual must be investigated using
the retained audit IDs, never by purging shared queues.

The private executor follows the same contract as the other fixtures:

```text
<executor> <database-name>
```

It obtains connectivity and credentials at runtime, reads SQL on stdin with `ON_ERROR_STOP`, and
returns unaligned tuples only. No host, credential, account identifier or token belongs in Git.
