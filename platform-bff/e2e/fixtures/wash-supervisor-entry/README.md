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

Cleanup must run before the supervisor and student fixtures. It waits for BFF operations, owner
Outbox rows and deferred Inbox work to settle, then verifies final convergence. The Wash Execution
phase removes only rows identified by the synthetic appointment or the two idempotency keys and
restores the exact original owner role. The BFF phase removes only the two operations and the
execution projection. It deliberately leaves the appointment and its Appointments evidence to the
student fixture, which owns and cleans those rows.

The two database phases are independently retryable. If cleanup fails after the Wash Execution
commit, keep `entry-fixture.env` and invoke cleanup again with the same private executor. Never delete
the manifest manually and never purge shared RabbitMQ queues.

The private executor follows the same contract as the other fixtures:

```text
<executor> <database-name>
```

It obtains connectivity and credentials at runtime, reads SQL on stdin with `ON_ERROR_STOP`, and
returns unaligned tuples only. No host, credential, account identifier or token belongs in Git.
