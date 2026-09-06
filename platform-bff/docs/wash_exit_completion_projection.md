# Wash exit/completion projection rollout

`V16__wash_completion_projection.sql` adds an independently fenced final-materials
projection. `WashExecutionCompleted` supplies those official values and releases the
remaining active assignment locally at the same owner execution version. A delayed
assignment event cannot make a terminal execution look active again.

Student Home now publishes canonical `executionVersion`, submitted/final materials,
completion time, and active/last assignment. The previous `version` property remains
during the client migration and has the same value. Remove it only after deployed clients
have switched to `executionVersion`.

## Deployment gate

The incremental Wash Execution feed has no owner-supported historical snapshot. Existing
`COMPLETED` rows predate the final-material columns. Redelivery of the original
`WashExecutionCompleted` message can backfill them even when its message ID is already in the
projection Inbox, because the final-material fence is independent. There is currently no
owner-supported source from which the BFF can retrieve acknowledged historical messages. The
BFF therefore returns controlled `503` for a completed Home row without official final
materials or a last assignment; it never substitutes submitted values.

Before deploying this read path in an environment with historical completed executions,
provide and consume an owner-supported replay/recovery that includes the original completion
materials and assignment history, or verify that no such rows are in the served date window.
Do not enable projection genesis or edit BFF rows to manufacture the missing owner state.

QR lookup remains gated until the shared protected QR projection exists. This change does
not generate or decode QR representations.
