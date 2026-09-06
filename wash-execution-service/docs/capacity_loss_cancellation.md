# CAPACITY_LOSS cancellation

`CancelWashExecutionByClinic` retains its existing wire fields and supervisor/actor,
version, pending-reassignment and idempotency checks. Only the exact subreason
`CAPACITY_LOSS` additionally requires:

- Ready appointment and wash-administration projections.
- An available appointment context in SCHEDULED or IN_PROGRESS, with an active slot.
- No eligible resource according to the same allocator used by reassignment candidates.

If a candidate exists, the owner publishes the usual rejected command result with
`CAPACITY_ALTERNATIVE_AVAILABLE`. Execution version/state, assignments and cleaning
remain unchanged; no cancellation event is published. An empty frontend snapshot
is not authorization to cancel. Other cancellation subreasons are unchanged.

Eligibility uses the owner's current clock sampled after acquiring the transaction
gate, not the original command's `cancelledAt`. Queue delivery or retry may cross
a cleaning deadline without any concurrent database write. Historical cleaning rows
remain present, including rows already completed by the worker. `cancelledAt` keeps
its existing meaning for a successful cancellation, resource release and event;
it is not reused as the time of the availability decision.

## Transaction boundary and contention

Before taking eligibility locks, capacity cancellations acquire the same transaction-level
PostgreSQL advisory lock keyed by `hashtext('wash-execution'), hashtext('capacity-decision')`.
This orders competing cancellations across consumers and pods. Its wait is bounded by
`SET LOCAL lock_timeout = '500ms'`; commit/rollback releases it, including on table-lock
failure. It is acquired before eligibility/resource-row access so waiting cancellations
do not hold those locks. Other resource writers do not need the advisory lock: the table
gate below still protects against them. During a mixed-version rollout, old consumers
still bypass this ordering; table-lock safety remains, but the measured reduction in
retry collisions applies only after every replica uses the new revision.

This ordering is necessary: an isolated burst on the earlier NOWAIT-only implementation
sent 2 of 20 commands to DLQ after an external row lock lasting only one second had
already ended. Consumers collided again at the fixed 5s/30s broker retry boundaries.
An initial run observed 5 of 20; the corrected experiment reproduced 2 of 20.

Before resource-row/idempotency/aggregate access, this cancellation takes EXCLUSIVE
table locks on the complete local eligibility inputs, including readiness. Ordinary
SELECT remains possible. Resource writers, SELECT FOR UPDATE, inserts into an empty
resource universe, and projection rebuilds cannot change those inputs before commit.
Locks on just the currently eligible resources would not protect an empty result.

Table-lock acquisition uses NOWAIT: a partial acquisition rolls back on contention. A transaction-local
500ms lock timeout also bounds subsequent lock waits. SQLSTATE 55P03 is explicitly
classified as transient because Spring's fallback translator leaves it uncategorized.
No functional rejection or idempotency result is committed on a technical lock failure;
the existing broker retry path reruns the whole command and evaluates fresh inputs.

This is deliberately a coarse, short-lived gate for terminal capacity decisions, not
a new serialization gate on every normal allocation. Under sustained contention the
existing finite retry policy can still exhaust and route a command to DLQ; monitor
command retries/DLQ before broader rollout. Do not interpret that as successful cancellation.
There is no network call while holding the gate.

The guarantee concerns the owner's locally committed projections. It does not imply
that upstream events still queued have been consumed, or make multiple source events
one atomic configuration update. No BFF wire migration or feature-flag activation is
included in this change.

## Verification

`WashExecutionCommandIT` covers available-candidate rejection without side effects,
empty-context cancellation with physical-use cleaning, missing/unready context,
unchanged non-capacity subreasons, version/idempotency, and concurrent resource
insertion/reactivation, operational restoration via `RestoreOperationalResource`,
row-lock acquisition and capacity release.
The concurrency cases hold a real PostgreSQL writer open, check rollback and transient
classification, commit the resource, then retry the identical command and require rejection.
The temporal regression also forces a technical rollback, advances the owner clock
past a cleaning deadline and retries the unchanged payload. Both completed and
not-yet-completed historical cleaning rows must yield available-alternative rejection
without cancellation, assignment changes or new cleaning. The rejected result remains
idempotent on redelivery.


## Opt-in contention experiment

`CapacityDecisionContentionIT` runs only with `-Dwash.capacity.contention=true`.
It uses disposable Testcontainers PostgreSQL/RabbitMQ, never a shared endpoint.
Run from this service with Maven/JDK25 and working Docker:

```sh
OTEL_SDK_DISABLED=true mvn -Dwash.capacity.contention=true \
  -Dit.test=CapacityDecisionContentionIT \
  '-DargLine=-Xmx256m -XX:ActiveProcessorCount=2' verify
```

The application pool has two connections, with unchanged listeners (2 initial / 4
maximum consumers per queue, prefetch10) and broker retry TTLs (5s,30s;3 attempts).
Recovery and cleaning workers are disabled in the synthetic fixture. The real outbox
relay is invoked by the observer loop; Result latency includes that observation cadence,
not a measured BFF/client SLA. Projection latency is time until the observer sees an
APPLIED inbox row. The fixture starts executions in PENDING_REASSIGNMENT; it does not
certify the preceding public transition or WASH-SUP-02 E2E.

Acceptance criteria fixed before execution: mixed40 commands/40 projection events,
40/40 cancellations without compatible capacity (with one semantic event per execution),
and transient20/20 with a one-second writer must finish within45s, without DLQ or incorrect
business mutation. A separate deliberate35s+ lock must exhaust exactly one command
through retry1/retry2/DLQ without business mutation, then allow controlled redelivery
of the exact message after release. Ordinary reads are measured throughout.

Only this disposable broker is drained/redriven by the experiment. No historical or
shared DLQ is touched. Counts/latency evidence (no credentials, IDs or payloads) is
written to `target/capacity-contention-evidence.json`. Containers are reaped by
Testcontainers/Ryuk. This is a bounded owner experiment, not a capacity forecast or
approval of shared-cluster rollout; workload acceptance remains an operations decision.

PostgreSQL references: [transaction-level advisory locks](https://www.postgresql.org/docs/16/explicit-locking.html#ADVISORY-LOCKS)
and [lock_timeout](https://www.postgresql.org/docs/16/runtime-config-client.html#GUC-LOCK-TIMEOUT).


### Measured result — 2026-09-06

Sanitized before/after evidence: `capacity_contention_evidence.json`. Before serialization,
a corrected run sent 2/20 transient-writer commands to DLQ (initial run:5/20).
With transaction advisory ordering, the expanded run produced:

| Scenario | Commands / projection events | Command p95 / max | Unexpected DLQ |
| --- | --- | --- | --- |
| Alternative available | 40 / 40 | 563 / 5353 ms | 0 |
| One-second writer | 20 / 20 | 5122 / 5122 ms | 0 |
| No compatible capacity, actual cancellation | 40 / 40 | 5186 / 5218 ms | 0 |
| Forced exhaustion | 1 / 5 | 35047 / 35047 ms | 1 expected, safely redelivered |

The successful cancellation case verifies one state/version transition and one published
cancellation event per execution; rejected/exhausted cases preserve the pending state.
Projection observed maxima were552/90/356/35ms respectively; ordinary read maxima56/4/10/3ms.
These are small synthetic samples, not a production workload distribution or SLA.
The initial green run also passed the first, second and exhaustion scenarios; the
expanded run adds real cancellation outcomes. CI runs the focused queue/lifetime tests;
the longer contention experiment remains opt-in.

Full `clean verify` with the initial assay enabled:23 unit tests +64 integration,
zero failures/errors/skips, 18:57:56UTC. After expanding the assay, focused `verify`
recompiled tests and passed23 unit tests +1 operational IT at19:00:32UTC. Runtime
sources were identical between these green executions. New SHA still requires external
review/CI, and deployment/workload acceptance remains separate.

Final focused recheck at19:08:00UTC passed23 unit tests +1 operational IT after
strengthening redelivery correlation, rejection code, unchanged state/version and absence
of cancellation-event assertions. Its separate sanitized report is included in the evidence JSON.
