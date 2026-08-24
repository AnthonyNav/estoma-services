# Phase 3 Messaging Shell Status

The durable RabbitMQ shell is implemented on this branch and intentionally stacked on the canonical upstream-contract convergence branch.

The only deferred consumed Event is `PasswordRecoveryRequested`; it is enabled by the next secure-recovery branch together with the ephemeral `GetRecoveryCodeForDelivery` RPC client so no secret-bearing workflow is partially enabled.

No `estoma-infra` resources are modified here.
