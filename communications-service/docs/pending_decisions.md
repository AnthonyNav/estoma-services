# communications-service Pending Decisions

## Purpose

Tracks only decisions that still remain genuinely open after the canonical Phase 3 integration, secure password-recovery delivery work, and the selection of AWS SES as the first real email provider.

The following are now resolved and are intentionally no longer pending:

- canonical Users/Authentication projection payload handling;
- the durable RabbitMQ command/event shell;
- the secure mechanism for password-recovery secrets: `PasswordRecoveryRequested` is non-secret and durable, while `GetRecoveryCodeForDelivery` is an ephemeral Authentication Security RPC immediately before each provider call; the returned code exists only in memory and is never persisted or logged;
- primary real email provider selection: AWS SES API v2 is the approved PRIMARY adapter;
- provider boundary: SES is infrastructure-only behind the neutral `EmailProviderPort` and may not leak SDK types/exceptions into domain/application/contracts;
- minimum SES normalization semantics and ambiguous-outcome rule required for implementation, documented in `docs/aws-ses-adapter-plan.md`.

## Still Pending

- Whether/when to integrate a second real email provider and enable production fallback. `StubEmailProvider` is local/test only and does not count as fallback.
- Future SES feedback ingestion (`DELIVERY`, `BOUNCE`, `COMPLAINT`) and the criterion for when `EmailDeliveryDelivered`/`DELIVERED` becomes reachable.
- Final platform-wide RabbitMQ tunables such as exact prefetch, retention and message-size limits.
- Retention policy for `email_delivery_attempts`.
- Retention policy for the restricted `InboxMessageCreated` event/projection data.
- Which additional business scenarios count as critical communications and the corresponding inbox expiration policy, where still owned by the cross-cutting business specification.

## Implementation Tracking

The concrete repository changes required to activate AWS SES are documented in:

```text
docs/aws-ses-adapter-plan.md
```

That plan is intentionally implementation-facing. It requires the AWS SDK and `SesEmailProvider` only under infrastructure, fixes explicit provider selection in `EmailProviderRouter`, defines configuration/error translation/tests, and keeps the provider-neutral port unchanged.

## Out Of This Service's Control

- AWS runtime identity/secret-management strategy and credential rotation.
- SES identity/domain verification, DKIM/DNS and production-access/quota operations.
- Resource requests/limits and HPA metrics.
- Database user/database creation workflow.
- Physical RabbitMQ topology ownership per environment.

These items belong to `estoma-infra` / the platform team and remain outside this code PR.
