# Wash Execution PR4 — Distributed bounded-context E2E

This harness is the executable gate for `wash-execution-service` PR4.

It starts real containers for:

- `appointments-service` + its own PostgreSQL;
- `wash-administration-service` + its own PostgreSQL;
- `wash-execution-service` + its own PostgreSQL;
- `authorization-service` + its own PostgreSQL;
- RabbitMQ;
- a Python runner that talks only through RabbitMQ and test-fixture PostgreSQL access.

No service shares a database and no business HTTP/gRPC shortcut is used.

## Run

From `wash-execution-service`:

```bash
./mvnw -B clean verify
bash e2e/verify.sh
```

`verify.sh` builds the current checkout, runs all distributed scenarios, restarts `wash-execution-service`, verifies durable Outbox/Inbox behavior after restart, and always removes containers/volumes when it exits.

## Fixture boundary

Static prerequisites are fixtures, not alternate business implementations:

- Wash Administration owner configuration is inserted into its owner DB, then published by the **real Wash Administration Outbox Relay**;
- Academic and Authorization bootstrap facts are emitted as canonical RabbitMQ Events;
- Authorization owner rows are also seeded so its real recovery Query can be exercised;
- projection `READY` markers are only bootstrap fixtures; PR4 later deliberately degrades and rebuilds the three Execution projections using real owner Queries/Results.

After bootstrap, business transitions under test use the real RabbitMQ Commands, owner service, Transactional Outbox, consumer Inbox/projection path.

## Scenarios

The runner covers the V1 PR4 gate:

1. NORMAL appointment → arrival → authorization → assignment → exit → completion.
2. JOURNEY preferred resource and NORMAL fallback.
3. IMMUNOCOMPROMISED physical release → exact 3h CleaningPeriod → automatic completion.
4. IMMUNO cancellation without physical use releases preventive reservation and creates no cleaning.
5. MISSED IMMUNO without use releases preventive reservation.
6. ENTRY_REJECTED IMMUNO without use creates no physical cleaning.
7. Authorized without resource → `PENDING_REASSIGNMENT` → first `INITIAL` assignment.
8. Failure during execution → pending → `REASSIGNMENT`.
9. Failure without alternative → clinic cancellation.
10. Operational capacity loss propagates to future Appointments.
11. A delayed execution from another slot does not consume capacity of the current slot.
12. Cleaning is anchored to actual `releasedAt`, not planned `slot.endsAt`.
13. Concurrent race for the last `Tank + AppointmentTimeSlot` capacity.
14. Concurrent assign-vs-disable serialization.
15. Authoritative recovery of Appointments, Wash Administration and Authorization projections, including a concurrent higher-version Event.

Additional regression gates cover:

- arrival evidence and `entryAuthorizedAt`;
- distinct same-version Wash Execution Events;
- symmetric IMMUNO preventive reservation;
- exact duplicate delivery;
- service restart with a durable pending Outbox obligation.

## Expected result

The runner exits non-zero on the first violated invariant and prints the scenario name. Success ends with:

```text
main distributed scenarios: PASS
restart/outbox/inbox scenario: PASS
PR4 distributed E2E: PASS
```

Do not merge PR4 if either `clean verify` or this distributed gate fails.
