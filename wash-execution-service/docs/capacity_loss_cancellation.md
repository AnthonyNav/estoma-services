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

## Transaction boundary and contention

Before resource-row/idempotency/aggregate access, this cancellation takes EXCLUSIVE
table locks on the complete local eligibility inputs, including readiness. Ordinary
SELECT remains possible. Resource writers, SELECT FOR UPDATE, inserts into an empty
resource universe, and projection rebuilds cannot change those inputs before commit.
Locks on just the currently eligible resources would not protect an empty result.

Acquisition uses NOWAIT: a partial acquisition rolls back on contention. A transaction-local
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
