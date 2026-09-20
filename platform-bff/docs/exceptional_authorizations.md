# Supervisor exceptional authorizations

`GET /api/v1/wash/exceptional-authorizations/students?query=...` searches active academic profiles by literal enrollment/name substring (2–100 characters, at most 30 results). Returns `accountId`, `enrollment`, `fullName`; UUIDs remain internal selection values.

`GET /api/v1/wash/exceptional-authorizations?studentAccountId=...&serviceDate=YYYY-MM-DD` returns the selected student's date-scoped authorizations: `authorizationId`, `status`, `reason`, `canCancel`. Both reads require wash-supervisor access and relevant projection readiness. Existing POST grant/cancel commands remain the authoritative decision, with client operation tracking and idempotency keys.

Display states are AVAILABLE, CONSUMED, SUPERSEDED, CANCELLED, EXPIRED and UNKNOWN. Any appointment consumes the authorization except a CLINIC cancellation, matching the owner policy. A later grant permanently supersedes its consumed predecessors, even if a predecessor's appointment is subsequently cancelled by the clinic. Current grants are date-scoped and intentionally have no custom expiry. Historical expiry is respected when the source event contains it.

## Event evolution and rollout

Deploy BFF migration V19 and the extended event reader **before** the appointments producer: the previous BFF registry rejects unknown payload fields. `ExceptionalAppointmentAuthorizationGranted` V1 now optionally includes `policyContextKnown`, `expiresAt`, and `supersededAuthorizationIds`. Required fields and routing are unchanged. New grants always publish complete policy context, with an array of predecessor IDs. The BFF retains supersession independently of predecessor arrival order.

Old events remain accepted but cannot reconstruct expiry/supersession facts that were never published. Their rows display UNKNOWN and do not advertise cancellation availability; re-delivering old bytes does not invent missing owner facts. No database backfill or synthetic authorization is performed. An owner snapshot/recovery contract would be required to enrich those historical rows. New grants are still decided by the owner's complete history and cannot bypass an existing available authorization.

The frontend stores one pending receipt per supervisor in browser storage before sending. Lost acknowledgements reuse the exact key/payload; known operations are polled. FAILED/EXPIRED/transport uncertainty remain locked for reconciliation. Only confirmed success/rejection may be acknowledged. No auth tokens are stored with receipts.
