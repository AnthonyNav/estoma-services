# communications-service Execution Plan

## Goal

Deliver `communications-service` safely through small pull requests into `develop`. The plan has three phases. Phase 1 closes all technical decisions this service has authority over, so Phase 2 can proceed without intermediate approval gates. Unlike a placeholder Phase 3, this service's Phase 3 already has a concrete, requested scope: wiring the actual RabbitMQ communication layer using the platform's unified messaging rules.

## Branch And PR Policy

- All changes target `develop` through pull requests.
- No direct commits to `develop`.
- Each PR must keep secrets out of Git.
- Each PR must include verification notes.
- Phase 2 may be delivered as several small, independently reviewable commits or sub-PRs on the same branch. Splitting it does not require re-approval of already-resolved decisions, because Phase 1 removes the prerequisite gates.

## Phase 1 (PR1): Scaffold And Complete System Contract

Scope:

- Spring Boot service scaffold, including the runtime infrastructure needed for the service to boot against a real database with its operational HTTP surface locked down (datasource, Flyway wiring with no real migrations yet, structured logging, `HttpSecurityConfig`, Actuator health groups).
- `docs/system_contract.md`: service boundary, ownership, persistence boundary, messaging interfaces, mandatory invariants, prohibited behavior, and the full set of v1 technical decisions (internal architecture, migration tooling, Outbox operational policy, email delivery operational policy, observability parameter names).
- `docs/execution_plan.md` (this file).
- `docs/pending_decisions.md`: only what genuinely remains open (deferred to Phase 3, or owned by `estoma-infra`/platform team outside this service's control).
- Hexagonal package skeleton (`domain`, `application`, `infrastructure`, `contracts`), documented with `package-info.java`, no business logic yet.
- Canonical enum/constant names for defined commands, events, communication/inbox/email statuses, exchanges, and the command queue name.
- Operational health endpoints for startup, readiness, and liveness.

Out of scope:

- Command handlers, projection handlers, or the `AppointmentCancelled` adapter.
- JDBC repositories.
- RabbitMQ consumers or publishers.
- Database migrations with real tables.
- Email provider integration, even a stub.
- Outbox Relay or email delivery worker implementation.

Acceptance criteria:

- Service builds and tests continue passing.
- `system_contract.md` has no open technical decision that blocks starting Phase 2 (all v1 values are concrete, not "TBD").
- `pending_decisions.md` contains only items that are explicitly deferred to Phase 3 or explicitly out of this service's control.
- The hexagonal package skeleton exists and is documented; no premature interfaces or implementations.
- Code only exposes safe foundation constants and operational endpoints.

## Phase 2 (PR2): Build And Finish The Microservice

Single milestone covering all functional behavior against a real database, without a live RabbitMQ broker. No external approvals block sub-parts of this phase — every technical decision needed to implement it was resolved in Phase 1. Suggested internal order:

1. **Database migrations**: Flyway migrations under `src/main/resources/db/migration/` implementing the full schema documented in the platform's `data-model.md` (`communications`, `inbox_messages`, `inbox_visits`, `email_deliveries`, `email_delivery_attempts`, `recipient_projection`, `processed_messages`, `outbox_messages`), with the constraints and indexes already specified there, including the `uq_communication_origin_recipient_type` idempotency index.
2. **Runtime infrastructure completion**: `SchemaVersionHealthIndicator` joins the `startup` health group now that migrations exist; `TransactionManagerConfig` if a nested-transaction/savepoint fix is needed once real repositories exist.
3. **Domain aggregates**: `Communication`, `InboxMessage`, `InboxVisit`, `EmailDelivery`, `EmailDeliveryAttempt`, plus `CommunicationsRejectedException` and `RejectionCode` (the 10 rejection codes from `commands.md` §2.1). No framework dependency, unit-tested directly.
4. **JDBC repositories** implementing the ports declared in `application/port`, using `JdbcClient`.
5. **`RequestCommunication` handler**: applies the composite idempotency key (`commands.md` §6), creates `Communication` and at most one `InboxMessage`/`EmailDelivery`, appends `CommunicationRequested` (and `InboxMessageCreated` if applicable) to Outbox in the same transaction. `EmailDelivery` is written `PENDING` only — no provider call here.
6. **`RegisterInboxVisit`, `RetryEmailDelivery`, `ExpireInboxMessages` handlers**, dispatched by a single transactional `CommandDispatcher` (Consumer Inbox first, functional rejection caught inside the transaction, non-sensitive events appended to Outbox). No correlated result is actually published to `platform.results` until the messaging shell exists (Phase 3); the dispatcher's Outbox write is the durable, testable proof of correctness in the meantime.
7. **`recipient_projection` consumer**: consumes `PersonCreated/Updated/Activated/Deactivated` and `AccountCreated/Activated/Deactivated/LoginIdentifierChanged`, updates `recipient_projection` idempotently using `person_source_version`/`account_source_version` monotonicity, via the same `ProjectionDispatcher` pattern. Implemented against the conceptual payload already assumed by this service's own `events.md`/`data-model.md` — Phase 3 formalizes this against what `users-service`/`authentication-service` actually publish.
8. **`AppointmentCancelled` adapter**: filters on `cancellationType = CLINIC` and `cancellationSubreason = CAPACITY_LOSS`, and on match invokes the `RequestCommunication` handling logic **in-process, in the same transaction as its own Consumer Inbox write** — not as a RabbitMQ round trip (see `system_contract.md`'s "Internal Architecture"). Other values must not trigger it.
9. **Email provider port and stub implementation**: a neutral `send(...)` port with normalized success/error, no SDK/exception leakage, and a deterministic stub implementation so the delivery worker and its tests run without a real provider account. Real provider selection/fallback stays out of scope.
10. **Email delivery worker**: implements the "Email Delivery Operational Policy" from `system_contract.md` — claims due rows, calls the provider port with no open transaction, records the attempt and resulting state/event in a second transaction.
11. **`ExpireInboxMessages` scheduled trigger**: same in-process pattern as the email delivery worker, no RabbitMQ self-command needed.
12. `readiness` health group gains `emailWorker` as a checkable component.

Out of scope:

- RabbitMQ consumers/publishers, exchange/queue/binding declarations, `docker-compose.yml`'s RabbitMQ service.
- Real email provider integration (SMTP/API SDK, credentials, template rendering against a real templating engine).
- Enforcement of `InboxMessageCreated`'s restricted binding (that is a RabbitMQ permissions concern, Phase 3).
- Anything requiring a payload contract not yet ratified with `users-service`, `authentication-service`, or `appointments-service` beyond what this service's own docs already assume (see Phase 3).

Acceptance criteria:

- All 4 commands and their handlers are implemented and tested (unit tests for domain, Testcontainers integration tests for repositories and dispatchers).
- All mandatory invariants from `system_contract.md` hold under test, including the idempotency key and the max-one-inbox/max-one-email invariants.
- A test proves the email provider is never called while a database transaction is open.
- `title`, `body`, and email content never appear in any log line.
- Service is deployable and passes its own health checks against a real database (no RabbitMQ required yet).

## Phase 3 (PR3): RabbitMQ Communication Contracts And Live Wiring

Wires the real messaging layer over the business logic already proven in Phase 2, using `users-service`'s already-working `AmqpTopologyConfig`/`AmqpNames`/`OutboxRelay`/`CommandListener` shape as the direct structural reference (`authentication-service` has not built this layer yet).

Scope:

- Add `spring-boot-starter-amqp` and `testcontainers-rabbitmq`; add the `rabbitmq` service to `docker-compose.yml`.
- `AmqpTopologyConfig`: declares only what this service owns — its own command queue (`commands.communications`) plus DLQ and retry tiers, and its own event consumer queues (one per independently-deployable consumer: `recipient_projection`, the `AppointmentCancelled` adapter). Gated behind `estoma.messaging.topology-declare`, same as `users-service`.
- `InboxMessageCreated`'s restricted binding: bound only to `events.bff-projections` and explicitly authorized queues, enforced at the RabbitMQ permission layer.
- `CommandListener`/`EventListener`: decode the envelope, route to `CommandDispatcher`/`ProjectionDispatcher`/the adapter built in Phase 2, manual ACK after commit, technical-vs-functional failure classification for retry/DLQ routing.
- `OutboxRelay`: claim-and-publish with publisher confirms over `outbox_messages`.
- No `results.communications` queue: the `AppointmentCancelled` adapter's self-issued `RequestCommunication` stays in-process (Phase 2 decision), so this service never issues durable Commands to another service and never needs to consume a `results.<requester>` queue of its own.
- Finalize `contracts/command`, `contracts/event`, `contracts/consumed` payload records against `messaging-architecture.md` §8's exact envelope fields.
- RabbitMQ and Outbox observability metrics (queue depth, unacked, redeliveries, DLQ count; `communications.outbox.pending`, `communications.outbox.failed.total`).

Out of scope:

- Real email provider selection/fallback beyond the Phase 2 stub.
- `EmailDeliveryDelivered`/webhook support.
- DLQ replay tooling (v1 policy is manual, audited replay only).
- Changes to the already-shipped `-service`-suffixed queue naming of `authentication-service`/`users-service`.

Acceptance criteria:

- Service builds and boots against a live RabbitMQ (Testcontainers).
- A command published on `commands.communications` produces the same outcome Phase 2's dispatcher tests already proved via direct transactional calls.
- `InboxMessageCreated` cannot be consumed from a queue other than `events.bff-projections`/explicitly-authorized ones.
- Messages reach DLQ after exhausting the configured retry tiers; no auto-replay exists.
- `recipient_projection` and the `AppointmentCancelled` adapter process real `@RabbitListener`-delivered events idempotently (duplicate-delivery test).
- `readiness` health group includes `commandConsumer`/`outboxRelay`.

## Safe Delivery Rules

- Do not introduce secrets in durable messages.
- Prefer database constraints over in-memory checks for critical invariants.
- Never call an external provider while a database transaction is open (`data-consistency-architecture.md`).
- Keep Phase 2 and Phase 3 sub-parts independently reviewable even though they are not gated on external approval.
- Update this plan when a phase's scope changes.
