# Wash exit/completion projection rollout

`V16__wash_completion_projection.sql` adds an independently fenced final-materials
projection. `WashExecutionCompleted` supplies those official values and releases the
remaining active assignment locally at the same owner execution version. A delayed
assignment event cannot make a terminal execution look active again.

This first rollout stage only writes the final-material projection and converges assignment
release. Student Home keeps its existing response contract, query and consistency checks.
The stored final materials and released assignment history are not exposed by this stage.

The second stage exposes canonical `executionVersion`, submitted/final materials, completion
time and active/last assignment in Student Home. It keeps `version` as a compatibility alias
with the same value and fails closed when a terminal execution lacks its required owner
context. This read stage must not be published or deployed until the activation gate below is
closed with current rollout evidence.

## Historical recovery behavior

The incremental Wash Execution feed has no owner-supported historical snapshot. Existing
`COMPLETED` rows predate the final-material columns. Redelivery of the original
`WashExecutionCompleted` message can backfill them even when its message ID is already in the
projection Inbox, because the final-material fence is independent. There is currently no
owner-supported source from which the BFF can retrieve acknowledged historical messages.

## Read activation gate

The second rollout stage may add the new Home fields and consistency checks only after the
data audit verifies that no incomplete `COMPLETED` rows are in the served date window, or an
owner-supported replay/recovery has supplied the original completion materials and assignment
history. Every older BFF replica consuming `events.bff-projections` must be updated to this
first stage or removed before the data audit starts; otherwise an old replica can acknowledge
a completion without writing the new columns. HTTP clients migrate separately in the second
stage, which retains `version` as a compatibility alias while clients adopt canonical
`executionVersion`. Do not enable the second-stage reads, projection genesis or direct BFF row
edits to manufacture missing owner state.

QR lookup remains gated until the shared protected QR projection exists. This change does
not generate or decode QR representations.
