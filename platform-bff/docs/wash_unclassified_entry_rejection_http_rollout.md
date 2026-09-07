# Unclassified Wash entry rejection HTTP rollout

`POST /api/v1/wash/executions/{washExecutionId}/entry-decision` keeps valid classified requests
available. An unclassified rejection uses `decision=REJECTED`, a nonblank `rejectionReason` of at
most 500 characters, and omits both `identityConfirmed` and `requirementsSatisfied` or sends both
as null. Omission and explicit null are one idempotent intent; the owner Command omits both keys.
The legacy `false/false` pair remains a different classified intent.

The producer capability is disabled by default with
`BFF_WASH_UNCLASSIFIED_ENTRY_REJECTION_ENABLED=false`. When disabled, only the unclassified form
returns `503 BFF.WASH_UNCLASSIFIED_ENTRY_REJECTION_UNAVAILABLE`; authentication and
`SUPERVISOR_LAVADO` authorization run first, and no operation, idempotency record, or Outbox message
is created. Invalid combinations and non-boolean check values return `400 BFF.INVALID_REQUEST`.

Deploy the V2 readers first, then the Wash Execution owner and BFF with both producer gates off.
Enable the owner gate before the BFF gate and enable the frontend last. For rollback, disable the
frontend/BFF producer first and retain owner and reader compatibility while messages drain. A
disabled owner returns a functional rejection; the polled HTTP operation preserves it as the
terminal status `REJECTED` (`FAILED` remains a technical failure, while `EXPIRED` may represent
either class) and the original idempotency key remains terminal. Retry only after
reconciling owner state and version, with a deliberate new key.
