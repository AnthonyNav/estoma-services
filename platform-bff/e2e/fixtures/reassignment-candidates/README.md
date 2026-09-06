# Reassignment-candidates isolated fixture

This fixture owns one synthetic `PENDING_REASSIGNMENT` execution, its appointment and active slot
projections, and one cabin/tank pair in Wash Execution. It inserts only the matching execution and
temporary supervisor authorization in the BFF. The BFF scheduler and the real owner Query/Result
path must create all V15 refresh, inbox and snapshot rows.

Run it only through `e2e/runner/run-reassignment-candidates.mjs` against a localhost tunnel to the
fixed `wash-e2e` environment. The runner reuses `isolated-identities/fixture-db.sh`; it never stores
tokens or response bodies. It proves a non-empty owner snapshot, makes the manifest-owned tank
inactive, then proves a newly generated empty owner snapshot is exposed as HTTP 200 with a null
recommendation.

Cleanup first removes the BFF execution projection to stop new scheduling. It then discovers every
refresh Query belonging to that execution, waits for each owner Result to be published and consumed,
locks the V15 tables to close an in-flight scheduler transaction, and deletes only manifest IDs.
Unknown, incomplete or changed state fails closed and preserves the manifest for inspection/retry.
