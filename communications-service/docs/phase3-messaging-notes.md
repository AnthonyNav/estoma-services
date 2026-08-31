# Communications Phase 3 Messaging Notes

This branch wires the durable RabbitMQ shell on top of the canonical contract-convergence branch.

## Included

- `commands.communications` and one durable `events.communications` queue.
- Manual ACK after local transaction commit.
- Bounded retry tiers and terminal DLQs.
- Retry return for consumed Events stays on `platform.dlx`; it never fabricates an integration Event on `platform.events`.
- Outbox publication uses publisher confirms. Results require a routed destination; Events require broker confirmation but consumers own their bindings.
- Outbox claims are recoverable after a bounded lease and use `FOR UPDATE SKIP LOCKED` for replica-safe partitioning.
- `PasswordRecoveryRequested` is intentionally not bound yet. Its secure adapter and ephemeral Security RPC are delivered in the next branch so a valid recovery Event cannot be consumed and DLQed before the secret-safe path exists.

## Account binding migration

`events.communications` binds only the three Account lifecycle Events consumed by
the recipient projection: `created`, `activated` and `deactivated`. It must not
bind `authentication.account.*`, because that wildcard also routes unrelated
Account Events to a strict consumer and creates terminal DLQ traffic.

The environment owner must remove the pre-existing binding before enabling an
Authentication producer of `authentication.account.presentation-updated`:

```text
source exchange: platform.events
destination queue: events.communications
routing key to delete: authentication.account.*
```

Afterwards, verify that the three exact lifecycle bindings exist and that the
wildcard is absent. Application declaration creates missing bindings but does
not delete a broker binding created by an older release.

## Not infrastructure

This branch declares topology for local/test execution. Physical environment ownership, RabbitMQ users/permissions and GitOps remain in `estoma-infra` and are intentionally unchanged here.
