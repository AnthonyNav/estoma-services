# communications-service Pending Decisions

## Purpose

Tracks only decisions that still remain genuinely open after the canonical Phase 3 integration and secure password-recovery delivery work.

The following are now resolved and are intentionally no longer pending:

- canonical Users/Authentication projection payload handling;
- the durable RabbitMQ command/event shell;
- the secure mechanism for password-recovery secrets: `PasswordRecoveryRequested` is non-secret and durable, while `GetRecoveryCodeForDelivery` is an ephemeral Authentication Security RPC immediately before each provider call; the returned code exists only in memory and is never persisted or logged.

## Still Pending

- Definitive real email provider selection/activation beyond the current provider-neutral port/stub.
- Definitive normalized provider-error-code catalog and provider capability matrix.
- Future webhook support and the criterion for when `EmailDeliveryDelivered`/`DELIVERED` becomes reachable.
- Final platform-wide RabbitMQ tunables such as exact prefetch, retention and message-size limits.
- Retention policy for `email_delivery_attempts`.
- Retention policy for the restricted `InboxMessageCreated` event/projection data.
- Which additional business scenarios count as critical communications and the corresponding inbox expiration policy, where still owned by the cross-cutting business specification.

## Out Of This Service's Control

- Secret-management strategy beyond raw Kubernetes Secrets.
- Resource requests/limits and HPA metrics.
- Database user/database creation workflow.
- Physical RabbitMQ topology ownership per environment.

The last items belong to `estoma-infra` / the platform team and remain outside this code PR.
