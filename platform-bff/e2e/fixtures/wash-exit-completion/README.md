# Wash exit/completion terminal fixture

This fixture is the child handoff for the existing authorized-entry fixture. `bootstrap` accepts only its exact synthetic `IN_PROGRESS` v2 execution and writes a mode-`0600` manifest with the execution, assignment, actors, resources, deterministic intent keys, and both material sets.

`audit` requires the complete public journey to have converged to owner/BFF/Appointments `COMPLETED` v4, preserves original and final materials as separate evidence, proves the rejected stale operation and discovers operation/message IDs. It does not provision data and is not evidence of a live run.

Cleanup must be invoked through `cleanup`, which passes the child manifest to the authorized fixture's explicit `E2E_AUTHORIZED_TERMINAL_MANIFEST` mode. Without that variable the authorized fixture keeps its original `PENDING_ENTRY|1` and `IN_PROGRESS|2` guards. Terminal cleanup accepts only the exact `EXIT_SUBMITTED|3` partial history or exact `COMPLETED|4` history, refuses pending or ambiguous operations, and is retryable after the owner phase.

The required order is terminal/authorized cleanup, supervisor cleanup, then student cleanup. Keep the private report directory when any phase fails; do not issue a new idempotency key to resolve an ambiguous operation.
