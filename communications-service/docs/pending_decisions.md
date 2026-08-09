# communications-service Pending Decisions

## Purpose

This file tracks what is genuinely still open after Phase 1 (PR1). Everything this service had authority to decide on its own — internal architecture, migration tooling, Outbox operational policy, email delivery operational policy (worker cadence, retry backoff), observability parameter names — was resolved in `docs/system_contract.md` during Phase 1 and is no longer listed here.

## Deferred To Phase 3

These require agreement with other services' teams, or vendor/operational decisions outside this service's own technical authority, before they can be finalized. Phase 2 proceeds using the conceptual payloads and the stub provider already documented in this service's own `events.md`/`data-model.md`/`commands.md`; Phase 3 formalizes and reconciles them.

- Exact payload contracts for `PersonCreated`/`PersonUpdated`/`PersonActivated`/`PersonDeactivated` (from `users-service`) and `AccountCreated`/`AccountActivated`/`AccountDeactivated` (from `authentication-service`), once those services build their own messaging shells.
- Definitive real email provider selection, without introducing provider fallback in this scope.
- Definitive normalized provider-error-code catalog.
- Secure mechanism for temporary links or secrets carried in email content (`README.md` §9, "pendiente de definición").
- Future webhook support and the criterion for when `EmailDeliveryDelivered`/`DELIVERED` becomes reachable.
- Final RabbitMQ topology details beyond the platform-wide defaults Phase 3 applies (exact prefetch, exact retention, exact message size limits — `messaging-architecture.md` §20 lists these as globally pending, not specific to this service).
- Retention policy for `email_delivery_attempts`.
- The canonical normalized provider-error-code catalog remains deferred; timeout, ambiguous outcomes, and legacy `SENDING` rows use durable indeterminate codes.

## Out Of This Service's Control

- Which communications count as "critical" and the inbox message expiration policy — sourced to `01-domain/shared-platform-business.md` §11 as a cross-cutting platform decision, not this service's to resolve on its own.
- Secret management strategy beyond raw Kubernetes Secrets.
- Resource requests/limits and HPA metrics.
- Database user and database creation workflow.
- Physical RabbitMQ topology ownership per environment.

Owned by `estoma-infra` / the platform team (last three items). Not a blocker for Phase 2 or Phase 3.
