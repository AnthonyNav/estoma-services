# Wash exit/completion terminal fixture

This fixture is the child handoff for the existing authorized-entry fixture. `bootstrap` accepts only its exact synthetic `IN_PROGRESS` v2 execution and writes a mode-`0600` manifest with the execution, assignment, actors, resources, deterministic intent keys, and both material sets.

`audit` requires the complete public journey to have converged to owner/BFF/Appointments `COMPLETED` v4, preserves original and final materials as separate evidence, proves the rejected stale operation and discovers operation/message IDs. It does not provision data and is not evidence of a live run.

Cleanup must be invoked through `cleanup`, which passes the child manifest to the authorized fixture's explicit `E2E_AUTHORIZED_TERMINAL_MANIFEST` mode. Without that variable the authorized fixture keeps its original `PENDING_ENTRY|1` and `IN_PROGRESS|2` guards. Terminal cleanup accepts only the exact `EXIT_SUBMITTED|3` partial history or exact `COMPLETED|4` history, refuses pending or ambiguous operations, and is retryable after the owner phase.

The required order is terminal/authorized cleanup, supervisor cleanup, then student cleanup. Keep the private report directory when any phase fails; do not issue a new idempotency key to resolve an ambiguous operation.

For a local database-level check, run from `platform-bff`:

```sh
e2e/scripts/test-wash-exit-completion-postgres.sh
```

The test starts one network-disabled PostgreSQL 16 container limited to two CPUs and 512 MiB,
applies the real Platform BFF, Wash Execution, and Appointments migrations, and inserts only fixed
synthetic rows. It exercises terminal cleanup for `EXIT_SUBMITTED|3` and `COMPLETED|4`, a retry
after an injected failure between the owner and BFF phases, refusal of a foreign terminal state,
refusal of ambiguous BFF operations, and the real `audit-clean` queries.

This is a database fixture test, not a live HTTP certification. To keep its seed focused, it removes
the synthetic Appointments-owned rows directly before `audit-clean`; it does not exercise the
supervisor and student parent fixture cleanup chain.

The live runner prepares a fresh student's ALUMNO/ACTIVE projections in Wash Execution
using student-authorization.sh. Its separate private manifest survives failures; cleanup
refuses live appointment dependencies or changed authorization and runs after entry cleanup.
A failed initial exit can be cleaned only while execution remains IN_PROGRESS v2 and both
owner and BFF prove the exact ACTOR_NOT_AUTHORIZED rejection. Other ambiguous outcomes
remain preserved. The PostgreSQL CI regression exercises both guards.
