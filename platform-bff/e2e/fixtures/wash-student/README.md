# Isolated WASH-STU fixture

This fixture supplies only prerequisites that the current synthetic `e2e-*` account does not own:

- local BFF and Appointments role/access projections;
- one student profile and one enrollment in the already-active academic period;
- one synthetic course section;
- one same-service-date time slot and its capacity projection.

It does **not** insert, update or cancel an appointment. Schedule and cancellation always travel
through the public BFF, RabbitMQ and the Appointments owner. It does not modify an existing
academic period, system catalogue entry, Authentication account or Users person. When both BFF and
Appointments lack an active academic period, it creates the same isolated `E2E-*` period in those
two projections and removes it during cleanup; any partial or conflicting real state still fails
closed.

## Database executor boundary

Set `E2E_PSQL_EXEC` to a private executable with this contract:

```text
<executor> <database-name>
```

The executor reads SQL on stdin, uses `ON_ERROR_STOP`, emits only unaligned tuples and obtains
credentials/connectivity at runtime. `psql-exec.example.sh` demonstrates the contract for a direct
DSN; cluster-specific SSH, kubeconfig, hostnames and passwords stay outside Git.

The bootstrap refuses to run unless all of these are true:

- the login identifier begins with `e2e-` and resolves to the supplied account in the BFF;
- its current projected role is absent or exactly `E2E_TESTER`;
- it has no existing Lavado access, academic profile or appointment for the service date;
- BFF critical projections are `READY`;
- BFF and Appointments agree on the active academic period, or both have none so the fixture can
  create its isolated period;
- Appointments projections are reliable;
- the complete synthetic slot, including its end at `now + 70 minutes`, remains within the current
  local service date.

Generated IDs, the synthetic-period flag and the original role-presence flags live in
`target/e2e/wash-student/fixture.env` with mode `0600`. The file is ignored by Git and is required
for exact cleanup. Cleanup targets only the generated identifiers, the two run-scoped idempotency
keys and appointment(s) created for that account/slot. It restores `E2E_TESTER` or removes the
temporary role row according to the manifest. Existing `E2E_TESTER` role identifiers and source
versions are not overwritten; the fixture refuses pre-existing Lavado access, so cleanup removes
only the access row identified by the generated `system_access_id`.

Cleanup also waits while an availability refresh is retryable (`FAILED` with a non-null
`next_attempt_at`) and removes the refresh result's BFF inbox record before deleting the refresh.
If cleanup refuses because a command, retryable refresh or owner outbox message is still active, do
not delete the manifest. Investigate the operation and retry cleanup with the same runtime executor.

## Manual lifecycle

Normally use `../../runner/run-wash-student.sh`, which discovers the account through a real BFF
login, bootstraps, runs the Postman workflow and executes cleanup in a trap. If Newman aborts, the
trap reads the exported environment from its private temporary directory, attempts logout with the
runtime token and then deletes that state; tokens and passwords are never written to reports.

For investigation, bootstrap and cleanup can be invoked independently. Values below are runtime
placeholders, never repository defaults:

```sh
E2E_ACCOUNT_ID="$RUNTIME_ACCOUNT_ID" \
E2E_LOGIN_IDENTIFIER="$RUNTIME_LOGIN" \
E2E_PSQL_EXEC="$PRIVATE_PSQL_EXEC" \
./e2e/fixtures/wash-student/fixture-db.sh bootstrap

E2E_PSQL_EXEC="$PRIVATE_PSQL_EXEC" \
./e2e/fixtures/wash-student/fixture-db.sh cleanup
```
