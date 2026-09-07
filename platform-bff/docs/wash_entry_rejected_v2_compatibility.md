# WashEntryRejected V2 consumer compatibility

This stage prepares the BFF projection consumer before the Wash Execution owner starts emitting
`WashEntryRejected` V2. It does not change the public HTTP request or OpenAPI contract.

The trust registry accepts V2 only for an `EVENT` envelope whose producer is `wash-execution` and
whose type is `WashEntryRejected`. All other V2 producer/type combinations remain untrusted. The
payload uses the existing Wash Execution event allowlist and requires the canonical fields other
than the omitted checks: `washExecutionId`, `appointmentId`, `entryValidationId`, `rejectionReason`,
`decidedByAccountId`, `decidedAt`, `executionStatus` and `executionVersion`. The reason must be a
nonblank string, state must be `ENTRY_REJECTED`, identifiers and timestamp must parse, and execution
identity/version must match the envelope. Explicit null fields are rejected before adaptation.

V2 represents an unclassified rejection by omitting both `identityConfirmed` and
`requirementsSatisfied`. A legacy classified pair remains compatible when both keys are present as
booleans. A partial pair, explicit nulls, non-boolean values, or fields outside the allowlist are
rejected before Inbox or projection writes. V1 behavior is unchanged.

The existing projector records the owner-supplied rejection reason and derives
`ENTRY_REJECTED`; it does not persist the two checks. Deploy this reader stage to every BFF replica
before enabling V2 emission in Wash Execution. The later owner and HTTP producer stages remain
separate changes.
