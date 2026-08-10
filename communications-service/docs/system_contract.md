# communications-service System Contract

## Status

Implementation contract for Phase 1 (PR1) of a three-phase plan (see `execution_plan.md`). This document constrains the scaffold and all future implementation work. Sections through "Prohibited Behavior" are derived from the platform documentation repository (see "Source Of Truth"). Sections from "Internal Architecture" onward are technical decisions this service has authority to make on its own; they are not derived from the platform documentation and do not require cross-team sign-off.

## Source Of Truth

This contract is derived from the platform documentation repository:

- `03-services/shared/communications-service/README.md`
- `03-services/shared/communications-service/commands.md`
- `03-services/shared/communications-service/data-model.md`
- `03-services/shared/communications-service/events.md`
- `03-services/shared/communications-service/interfaces.md`
- `02-architecture/messaging-architecture.md`
- `02-architecture/data-consistency-architecture.md`
- `01-domain/shared-platform-bounded-contexts.md` (§8, Contexto de Comunicaciones)
- `01-domain/shared-platform-business.md` (§8-§10, buzón y comunicaciones; §11, decisiones pendientes)

If this file conflicts with the platform documentation, the conflict must be resolved in the documentation source before implementing behavior.

## Ownership

`communications-service` owns:

- the `Communication` aggregate (origin, recipient, system, type, priority, requested channels, status);
- internal inbox messages and inbox visits;
- email deliveries and their delivery attempts;
- provider selection, fallback, and retry orchestration for email;
- the `recipient_projection` derived from consumed Person and Account events.

`communications-service` does not own:

- personal identity data;
- account or session data;
- business rules of the domains that request a communication (for example, why an appointment was cancelled);
- client-facing HTTP APIs;
- provider credentials management beyond consuming them through its own adapter configuration.

## Persistence Boundary

The service writes only to `communications-db`.

Initial conceptual tables:

- `communications`
- `inbox_messages`
- `inbox_visits`
- `email_deliveries`
- `email_delivery_attempts`
- `recipient_projection`
- `processed_messages`
- `outbox_messages`

There must be no foreign keys to databases owned by other services. References such as `recipientAccountId`, `personId`, and `initiatedByAccountId` are logical references.

## Operational HTTP Interface

The service may expose only internal operational endpoints:

- `/health/startup`
- `/health/readiness`
- `/health/liveness`
- `/metrics`

These paths are served by Actuator health groups (`management.endpoints.web.base-path: /`, so they are the real paths, not `/actuator/*`), not by a custom controller — matching the resolution already adopted by `authentication-service` and `users-service` for this exact question. `/health` and `/info` are also exposed: `/health` is the aggregate Actuator health endpoint (kept for local operational verification), and `/info` carries build/version metadata via the `spring-boot-maven-plugin` `build-info` goal. None of these endpoints are functional routes and none expose the service to a client (`interfaces.md` §9); `HttpSecurityConfig` denies everything else, including any Actuator endpoint not explicitly on the exposure allow-list.

## Functional Input Interfaces

Durable business commands use RabbitMQ:

```text
platform.commands -> commands.communications -> communications-service -> platform.results -> results.<requester>
```

Initial durable commands:

- `RequestCommunication`
- `RegisterInboxVisit`
- `RetryEmailDelivery`
- `ResolveIndeterminateEmailDelivery`
- `ExpireInboxMessages`

This service has no ephemeral Security RPC surface: that pattern (`platform.security.rpc` / Direct Reply-To) is scoped exclusively to `authentication-service` (`messaging-architecture.md` §3). Every operation `communications-service` receives is a durable Command.

`ResolveIndeterminateEmailDelivery` is administrative-only: `RECONCILED_ACCEPTED` requires a persisted real indeterminate attempt, while `RESEND_AUTHORIZED` requires explicit duplicate-risk acknowledgement. Both paths append an auditable administrative action in the same transaction as the lifecycle transition and Outbox events.

`ListInboxMessages`, `GetInboxSummary`, and `GetCommunicationStatus` are **not** operations of this service. They are served by BFF `read_models` fed from the events listed below; there is no direct interface from the BFF to `communications-service` for reads (`commands.md` §2, `interfaces.md` §3.1).

Queue topology is owned by the future platform messaging integration. This service does not currently declare queues, bindings, or per-projection consumers.

## Events Produced

Events are non-sensitive integration facts and must be recorded through Transactional Outbox in the same transaction as the local change. Routing key shape: `communications.<subject>.<event>` (topic exchange `platform.events`).

Initial produced events:

- `CommunicationRequested`
- `InboxMessageCreated`
- `InboxVisited`
- `InboxMessageExpired`
- `EmailDeliveryAccepted`
- `EmailDeliveryRetryScheduled`
- `EmailDeliveryFailed`
- `EmailDeliveryDelivered`
- `CommunicationCompleted`
- `CommunicationFailed`

`InboxMessageCreated` is a **restricted integration event**: it carries `title`/`body` so the BFF can build its `mailbox_messages_read_model`. Future platform access controls must prevent unauthorized consumption. `title`/`body` must never appear in logs, metrics, or traces (`events.md` §4).

`EmailDeliveryDelivered` is future/conditional: it requires real delivery confirmation (webhook or equivalent), and no webhook support exists in the initial scope. It is listed here because it is part of the documented contract, not because it is reachable in v1.

## Events Consumed

Initial consumed events:

- `PersonCreated`, `PersonUpdated`, `PersonActivated`, `PersonDeactivated` (from `users-service`) — feed `recipient_projection`.
- `AccountCreated`, `AccountActivated`, `AccountDeactivated` (from `authentication-service`) — feed `recipient_projection`.
- `AppointmentCancelled` (from `appointments-service`) — consumed by a documented adapter, **only** when `cancellationType = CLINIC` and `cancellationSubreason = CAPACITY_LOSS`, translated into an internal `RequestCommunication` for the student (`studentAccountId` from the event) with channel `EMAIL`, `sourceReferenceType = AppointmentCancelled`, `sourceReferenceId = appointmentId`. Other `cancellationType`/`cancellationSubreason` values must not trigger this adapter (`events.md` §5.1).

None of the Person/Account events trigger a communication by themselves — they only maintain `recipient_projection`. Durable event consumption must use Consumer Inbox or equivalent idempotence. ACK happens only after the local transaction commits.

## Mandatory Invariants

- A communication is not duplicated for the same `(sourceService, sourceReferenceType, sourceReferenceId, recipientAccountId, systemCode, communicationType)`.
- A communication generates at most one inbox message and at most one email.
- Each email has exactly one recipient.
- `ACCEPTED` does not mean physical delivery.
- `DELIVERED` requires real delivery confirmation.
- Local changes and Outbox messages share one transaction.
- Durable consumed messages are idempotent (Consumer Inbox).
- An email provider is never called while a database transaction is open.

## Prohibited Behavior

The service must not:

- decide business rules that belong to the domain requesting a communication (it only translates an already-confirmed fact or explicit request into a delivery);
- expose public client APIs or receive direct client/BFF HTTP or gRPC calls for functional behavior;
- access another service's database;
- store provider credentials, passwords, hashes, full tokens, or reusable recovery secrets;
- call an email provider from inside an open database transaction;
- write `title`, `body`, full email content, or full `templateData` to logs, metrics, or traces;
- revert the business operation that originated a communication because its delivery failed.

## Internal Architecture

`communications-service` follows a hexagonal (ports & adapters) architecture:

```text
com.estoma.communications
├── domain/              # no framework dependencies
│   ├── communication/     # Communication (aggregate root), SourceReference, value objects
│   ├── inbox/                # InboxMessage, InboxVisit
│   ├── email/                    # EmailDelivery, EmailDeliveryAttempt
│   └── event/                        # domain event types, prior to Outbox encoding
├── application/         # use cases; orchestrate domain + ports
│   ├── command/            # durable command handlers
│   ├── projection/            # Person/Account event handlers updating recipient_projection
│   ├── adapter/                   # AppointmentCancelled -> RequestCommunication translation (new vs. authentication-service)
│   └── port/                          # persistence and infrastructure ports
├── infrastructure/      # concrete adapters
│   ├── persistence/        # JdbcClient repositories implementing domain ports; SchemaVersionHealthIndicator
│   ├── messaging/              # RabbitMQ listeners/publishers, envelope (de)serialization
│   ├── email/                      # email provider port implementation(s), the async delivery worker (new vs. authentication-service)
│   ├── observability/                  # structured JSON log formatter, MDC field vocabulary
│   └── config/                             # Spring @Configuration classes (HttpSecurityConfig, etc.)
└── contracts/           # wire-name vocabulary
    ├── command/            # payload records for durable commands
    ├── event/                 # payload records for produced events
    ├── consumed/                  # conceptual payload shapes for consumed events
    └── result/                        # result payload shapes for durable commands
```

`application/adapter` and `infrastructure/email` do not exist in `authentication-service`. They are new because this service has two concepts `authentication-service` does not: a consumed event that is conditionally translated into a self-issued command (`application/adapter`), and an external provider call that must happen outside any open database transaction (`infrastructure/email`). There is no `contracts/rpc` package: this service has no ephemeral Security RPC surface (see "Functional Input Interfaces").

`domain` must have zero dependency on Spring, JDBC, or RabbitMQ. `application` depends on `domain` and on port interfaces declared by `domain` or `application`, never directly on `infrastructure`. `infrastructure` implements those ports and is the only layer allowed to depend on Spring JDBC, Spring AMQP, or an email provider client.

Port interfaces are not created until a Phase 2 tramo actually needs them: an interface with no real consumer yet is speculative.

**`MessageEnvelope` has no `targetService` field.** `messaging-architecture.md` §8 is explicit that the destination is determined entirely by RabbitMQ routing, not by a field in the envelope. This intentionally differs from `authentication-service`'s own `MessageEnvelope`, which still carries a `targetService` field predating this reading of the platform doc; that is not replicated here.

**Persistence: Spring JDBC (`JdbcClient`), not JPA/Hibernate** — same rationale already established by `authentication-service` and `users-service` for the same question. `communications-service` has directly applicable versions of the same three problems:

- `RequestCommunication` requires an idempotency key and uses one atomic insert protected by `(source_service, idempotency_key)`; a duplicate rereads and compares the canonical request fingerprint.
- `processed_messages` needs the same `INSERT ... ON CONFLICT DO NOTHING` idiom for Consumer Inbox.
- The Outbox relay's claim query and the email delivery worker's claim query both need `FOR UPDATE SKIP LOCKED`, outside JPA's portable surface.

Dependencies (production, added to `pom.xml` in this tramo): `spring-boot-starter-jdbc`, `spring-boot-starter-flyway`, `flyway-database-postgresql` (runtime), `postgresql` (runtime), `spring-boot-starter-security` (HTTP lockdown only — this service has no password hashing or token issuing). Test: `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql`, plus the `-test` variants of the starters above.

Still pending, added alongside the messaging tramo (PR3): `spring-boot-starter-amqp` and `testcontainers-rabbitmq`, together with `docker-compose.yml`'s RabbitMQ service.

## Migration Tooling

- Tool: **Flyway** (native Spring Boot autoconfiguration; plain SQL vs. Liquibase's XML/YAML changelogs; no extra plugin wiring needed on top of `./mvnw clean verify`).
- Location: `src/main/resources/db/migration/`, files named `V{n}__description.sql`.
- Migrations are additive only; an already-applied migration is never edited.
- Isolation level: READ COMMITTED (Postgres default) unless an operation states otherwise.
- Explicit row locking (`SELECT ... FOR UPDATE SKIP LOCKED`) is required for claiming Outbox rows and for claiming `email_deliveries` rows in the delivery worker.
- `processed_messages` retention: purge rows older than 30 days.
- `outbox_messages` retention: purge `PUBLISHED` rows older than 30 days.
- `email_delivery_attempts` retention is a pending decision (`pending_decisions.md`), not a Phase 2 blocker.

## Outbox Operational Policy

- Relay: single process per pod, polling every 1 second.
- Claim query pattern: `SELECT ... WHERE status = 'PENDING' AND available_at <= now() ORDER BY occurred_at LIMIT 50 FOR UPDATE SKIP LOCKED`.
- Publish with RabbitMQ publisher confirms; mark `PUBLISHED` on ack.
- On failure: increment `attempts`, exponential backoff via `available_at`, mark `FAILED` after 10 attempts.
- `communications.outbox.failed.total` is an alertable metric (see "Observability Parameters").

## Email Delivery Operational Policy

This section has no equivalent in `authentication-service`: it documents how the service satisfies `data-consistency-architecture.md`'s hard rule that a local database transaction must never include an external call.

- `RequestCommunicationHandler` writes `EmailDelivery` as `PENDING` inside the same transaction that creates the `Communication` (and, if applicable, the `InboxMessage`). No provider call happens in this transaction.
- A separate scheduled `EmailDeliveryWorker` claims due `PENDING`/`RETRY_SCHEDULED` rows with `SELECT ... FOR UPDATE SKIP LOCKED`, in its own transaction that only marks rows as claimed (e.g. `SENDING`) — no provider call happens in this transaction either.
- The provider call happens with **no** database transaction open.
- Provider calls run in a bounded executor with a configured timeout; timeout or ambiguous provider failure records `INDETERMINATE`.
- The resulting `EmailDeliveryAttempt`, the `EmailDelivery` status transition, and the derived canonical events (`EmailDeliveryAccepted` / `EmailDeliveryRetryScheduled` / `EmailDeliveryFailed` plus the corresponding `CommunicationCompleted` or `CommunicationFailed`) are recorded together in a second, separate transaction, appended to Outbox in the same commit.
- v1 operational values: worker polls every 5 seconds, claims a batch of 20; retry backoff schedule on provider failure is `1m, 5m, 30m, 2h, 24h` for six physical calls per retry cycle, then the delivery is marked `FAILED`. Lease duration must exceed provider timeout plus a safety margin.
- V1 uses one local fake provider. Real providers and provider fallback are outside this service's current scope.

## Observability Parameters

### Metrics

```text
communications.communication.requested.total
communications.communication.completed.total
communications.communication.failed.total
communications.inbox.message.created.total
communications.inbox.visited.total
communications.inbox.expired.total
communications.email.accepted.total
communications.email.retry.total
communications.email.failed.total{errorCode}
communications.email.provider.fallback.total
communications.outbox.pending            (gauge)
communications.outbox.failed.total
communications.messages.duplicate.total{consumer}
```

### Logging

Fixed field set: `service`, `environment`, `message_id`, `correlation_id`, `communication_id`, `operation`, `result`, `error_code`, `duration` — the same shape `authentication-service` uses, with `communication_id` in place of `account_id`/`session_id`. Redaction rule: never pass a full request/response DTO to the logger; `title`, `body`, `subject`, `textBody`, `htmlBody`, and `templateData` are never logged, even partially — construct log lines only from this allowlisted field set.

### Audit

v1 uses structured logs (the fields above) as the audit trail, shipped to the platform's log pipeline. There is no dedicated `audit_log` table in `communications-db` in v1. A durable, queryable audit store distinct from log retention is a future decision, not a Phase 2 blocker.

## PR1 Acceptance Criteria

PR1 (Phase 1) is complete when:

- the existing service scaffold remains buildable;
- the service has a local `docs/` directory;
- this system contract is present and has no open technical decision that blocks starting Phase 2;
- the three-phase execution plan is present;
- `pending_decisions.md` contains only items deferred to Phase 3 or explicitly out of this service's control;
- the hexagonal package skeleton (`domain`, `application`, `infrastructure`, `contracts`) exists, documented via `package-info.java`, with no premature interfaces or implementations;
- operational health endpoints are available for startup, readiness, and liveness checks;
- canonical enum/constant names exist for already-defined commands, events, communication/inbox/email statuses, exchanges, and queues;
- no command handlers, projection handlers, JDBC repositories, RabbitMQ consumers, email provider integration, or database migrations with real tables are implemented prematurely.
