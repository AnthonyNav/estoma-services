# Wash Execution event binding migration

Appointments previously declared `wash-execution.#` on `platform.events` for the durable
`events.appointments` queue. RabbitMQ declarations are additive: deploying the exact bindings does
not remove that existing wildcard. Leaving it installed continues routing owner-only events such as
`WashResourceAssigned` to Appointments, where they are rejected and dead-lettered.

For every existing environment, use this rollout order:

1. Deploy the Appointments version that declares the ten exact Wash Execution bindings.
2. Verify all ten exact bindings exist from `platform.events` to `events.appointments`.
3. Delete only the binding whose source is `platform.events`, destination is the queue
   `events.appointments`, and routing key is exactly `wash-execution.#`.
4. Verify the three unrelated subscription families remain unchanged:
   `academic.#`, `authorization.#`, and `wash-administration.#`.
5. Publish or observe a supported execution event and confirm normal consumption. Confirm an
   owner-only `wash-execution.resource.assigned` message is not routed to `events.appointments`.

Use the environment's approved RabbitMQ management client and credentials. Resolve the virtual host
explicitly before listing or deleting bindings. Do not delete/recreate the durable queue, purge it,
remove another service's binding, or use a wildcard when selecting the binding to delete.

The exact retained routing keys are defined by
`MessagingNames.WASH_EXECUTION_EVENT_ROUTES`. Any future consumed execution event must update the
listener contract, this routing map, and the broker-level topology regression in the same change.
