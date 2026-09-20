# Supervisor operational resources

`GET /api/v1/wash/operational-resources` requires a wash supervisor and READY
`wash-administration` / `wash-execution` projections. It returns `resources`, each
with `resourceId`, `resourceType` (`CABIN` or `TANK`), `code`, `name`,
`administrativeStatus`, and `unavailabilities`. Each active unavailability contains
`resourceUnavailabilityId`, `reason`, and `expectedVersion` for the existing restore
command. Multiple unavailabilities remain separate and must be restored separately.

The read uses existing typed projections, without migrations or synthetic resources.
Incomplete labels or invalid source versions fail closed. Administrative activation,
occupancy, cleaning, and operational unavailability are independent concepts: an
empty unavailability list does not promise that a resource is available for assignment.
Owner-side validation remains authoritative for command races and restore versions.

The inventory and active unavailabilities are joined in one PostgreSQL statement,
giving one MVCC snapshot and avoiding per-resource queries. The read permits at most
10,000 joined rows; exceeding this limit fails closed instead of returning a partial
inventory. A larger installation must introduce pagination before raising this bound.

The supervisor frontend preserves an account-scoped browser receipt before sending
a command. Transport uncertainty retries the original body and idempotency key;
accepted commands are polled by operation ID. Successful commands remain locked
until the resource projection reflects the matching disable ID or the selected
restore. Projection failures do not release the receipt. This is browser-local
recovery; deleting browser storage or switching devices does not transfer receipts.
