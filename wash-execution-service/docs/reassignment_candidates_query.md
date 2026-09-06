# Reassignment candidate snapshot query (V1)

Wash Execution owns candidate eligibility, capacity and ordering. The BFF may refresh a local
snapshot in a bounded background job; an HTTP GET must never trigger this Query synchronously.

## Wire contract

- Exchange/routing: `platform.queries` -> `queries.wash-execution`.
- Query: `GetReassignmentCandidates` V1, producer `bff`, actor `SYSTEM/bff` and payload
  `{ "washExecutionId": "<uuid>" }`. `operationId`, `idempotencyKey`, `aggregateId` and
  `aggregateVersion` are null because this background refresh creates no client operation.
- Result: `GetReassignmentCandidatesResult` V1, producer `wash-execution`, target `bff`, actor
  `SYSTEM/wash-execution`, with the normal correlation and causation identifiers; `operationId`
  remains null.
- Replies are written to the transactional outbox and delivered by the normal Result relay.

Successful payload:

```json
{
  "status": "SUCCEEDED",
  "data": {
    "washExecutionId": "<uuid>",
    "executionVersion": 2,
    "snapshotGeneratedAt": "<instant>",
    "freshUntil": "<snapshotGeneratedAt + 10 seconds>",
    "candidates": []
  }
}
```

Each candidate contains `cabinId`, `cabinCode`, `cabinName`, `tankId`, `tankCode`, `tankName`
and `availableCapacity`. Candidates use the same owner policy and order as automatic allocation;
the recommendation is exactly the first candidate. Following the platform's null-free envelope,
`recommendedCandidate` is omitted from an empty async Result; the BFF public response normalizes it
to explicit JSON `null`. The snapshot takes no resource lock, creates no
reservation and performs no business write. The Query handler's repeatable-read transaction makes
the owner reads coherent. The later reassignment Command still locks and revalidates everything.

## Rejections

Rejected payloads are `{ "status": "REJECTED", "error": { "code": "..." } }`.

| Code | Meaning |
| --- | --- |
| `INVALID_WASH_EXECUTION_ID` | Missing or malformed identifier. |
| `INVALID_QUERY_PAYLOAD` | Payload has fields other than the required `washExecutionId`. |
| `WASH_EXECUTION_NOT_FOUND` | The owner has no such execution. |
| `REASSIGNMENT_NOT_PENDING` | The execution is not currently pending reassignment. |
| `APPOINTMENT_CONTEXT_UNAVAILABLE` | Appointments are unready or appointment/slot context is missing, inactive or unsuitable. |
| `RESOURCE_CONFIGURATION_UNAVAILABLE` | Wash Administration projections are not ready. |
| `QUERY_PRODUCER_NOT_ALLOWED` | Only `bff` may request this Query. Existing recovery Queries remain appointments-only. |
| `UNSUPPORTED_QUERY` | The Query type is not supported. |

A successful empty list means the owner evaluated a trustworthy context and found no eligible
candidate. Missing or unready owner context must never be disguised as an empty list.

## Rollout

Deploy this owner Result producer before enabling the BFF refresher. Keep the BFF background
scheduler disabled by default until the owner deployment is confirmed. The compatibility bridge in
the owner system contract governs `ResolvePendingReassignment`; clinic-cancellation policy remains
unchanged. Automatic
allocation now retains the authoritative configured priority on its post-lock reread; the previous
single-candidate reread hardcoded priority `1`. The reread is intersected with the initially locked
candidate set, so a concurrently appearing resource can never be selected without its lock.
