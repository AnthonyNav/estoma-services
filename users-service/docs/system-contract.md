# System Contract — `users-service`

**Status:** v1.0 — first implementation
**Bounded context:** Identidad
**Database:** `users-db`
**Owns:** `Person` — names, institutional email, optional phone, global
person identifier, global person status, the person↔multiple-accounts
relationship.
**Does not own:** accounts, credentials, sessions, roles, permissions,
student number/semester/academic status.

---

## 1. Scope and subordination

This document is the executable contract for `users-service`: it fixes
every decision that
[`estomatologia-platform-docs`](../../../../../repo-estoma/estomatologia-platform-docs)
left open (routing keys, retry mechanism, health paths, the
`DUPLICATE_PERSON` rule, rejection payload shape) so the service can be
built without inventing them ad hoc during implementation.

**Precedence:** where this document conflicts with
`estomatologia-platform-docs/03-services/shared/users-service/*.md` or
`02-architecture/*.md` on a *domain* rule (what a Person is, what a command
does, what an event means), the platform docs win and this document has a
bug. Where this document *fills a gap* the platform docs left pending, this
document is authoritative for `users-service` until superseded by an ADR in
the platform docs repo (see §10 and §14).

Companion machine-readable artifacts in this same directory:

- `asyncapi.yaml` — the wire contract for commands, results and events.
- `openapi.yaml` — the operational HTTP surface (health + metrics only).
- `data-model.sql` — reference DDL; Flyway migrations
  (`src/main/resources/db/migration/`) are authoritative, this file is kept
  honest by `DataModelReferenceIT`.

This service has **no synchronous business API**. Every business operation
arrives as a durable command over RabbitMQ; every confirmed fact leaves as
a durable result or event over RabbitMQ. The only HTTP surface is
operational.
(`estomatologia-platform-docs/03-services/shared/users-service/interfaces.md:19-21,248`)

---

## 2. Message envelope

Every message — command, result, or event — carries the platform envelope
(`messaging-architecture.md:322-358`), reproduced here as JSON Schema in
`asyncapi.yaml#/components/schemas/BaseEnvelope`:

```json
{
  "messageId": "uuid",
  "messageType": "CreatePerson",
  "messageVersion": 1,
  "messageKind": "COMMAND",
  "producer": "bff",
  "occurredAt": "2026-08-03T12:00:00Z",
  "correlationId": "uuid",
  "causationId": "uuid",
  "operationId": "uuid",
  "targetService": "users-service",
  "aggregateId": "uuid",
  "aggregateVersion": 1,
  "actor": { "accountId": "uuid" },
  "payload": {}
}
```

Rules:

- `aggregateVersion` is always the version **after** the change
  (`events.md:183`).
- `operationId` is present on COMMAND and RESULT, **absent on EVENT**. It
  tracks a client-visible operation; event consumers have no use for it and
  the documented event example omits it (`events.md:164-181`). This is an
  interim decision — see §10 (R5).
- Field casing is camelCase on the wire. Logs and audit records use
  snake_case (`security-observability.md` vs `messaging-architecture.md`);
  this split is intentional and documented, not a bug.
- Codec must tolerate unknown fields on deserialization (forward
  compatibility, `messaging-architecture.md:499`).

---

## 3. Commands (received on `commands.users-service`)

All four commands originate from `bff` or an authorized administrative
import process (`commands.md:251-260`) and are processed exactly once per
`(consumerName, messageId)` pair (§7).

### `CreatePerson`

| | |
|---|---|
| Payload | `personId` (caller-generated UUID), `firstName`, `paternalSurname`, `maternalSurname?`, `institutionalEmail`, `phone?` |
| Succeeded | `CreatePersonSucceeded { personId, version: 1 }` |
| Event | `PersonCreated` |
| Rejections | see precedence below |

Rejection precedence (first match wins — not specified in the platform
docs, fixed here):

1. `PERSON_ALREADY_EXISTS` — a person with this `personId` already exists.
2. `INVALID_PERSON_DATA` — blank required field, malformed email.
3. `DUPLICATE_PERSON` — institutional email already claimed by a different
   `personId` (§6).

### `UpdatePerson`

Same payload plus `expectedVersion`. Never modifies accounts, roles, or
academic information (`commands.md:117`).

Rejections, in precedence order: `PERSON_NOT_FOUND` >
`PERSON_VERSION_CONFLICT` > `INVALID_PERSON_DATA` > `DUPLICATE_PERSON`
(self-exclusion applies — see §6).

### `ActivatePerson` / `DeactivatePerson`

Payload: `personId`, `expectedVersion`. Both perform a single guarded
conditional update plus a `person_status_history` insert, in one
transaction (§5, §8).

Rejection precedence — **fixed here, not in the platform docs**:

```
PERSON_NOT_FOUND  >  PERSON_VERSION_CONFLICT  >  PERSON_ALREADY_ACTIVE / PERSON_ALREADY_INACTIVE
```

Version wins over state: if the version is stale the caller must re-read
regardless of current state, so reporting "already active" would send them
down the wrong recovery path.

Full JSON Schemas: `asyncapi.yaml`.

---

## 4. Results (published to `platform.results`)

Every command yields exactly one correlated result:
`<Command>Succeeded` or `<Command>Rejected` — 8 message types total. No
exceptions to the naming convention exist in this service (the one
documented platform-wide exception, `StartStudentImportAccepted/Rejected`,
belongs to `academic-service`).

`Succeeded` payload: `{ personId, version }` — the version is the
aggregate version *after* the change, matching the derived event's
`aggregateVersion`.

`Rejected` payload shape (interim, cross-service concern — see §10 R6):

```json
{ "errorCode": "PERSON_NOT_FOUND", "message": "string", "details": [{"field": "string", "code": "string"}] }
```

Routing key = the `producer` field of the command being answered (§9).

---

## 5. Events (published to `platform.events`)

`PersonCreated`, `PersonUpdated`, `PersonActivated`, `PersonDeactivated` —
exactly the four in `events.md:16-19`. `users-service` is the only
publisher of Person events (`events.md:263`).

Consumer version ladder, restated here because the producer's obligations
depend on it (`events.md:193-205`):

```
received version <  local version       → ignore (stale)
received version == local version       → duplicate
received version == local version + 1   → apply
received version >  local version + 1   → gap; reconcile
```

Producer obligation: `aggregateVersion` must be correct and gapless per
`personId` (verified by `AggregateVersionMonotonicityIT`). No global
ordering is assumed across different `personId`s
(`messaging-architecture.md:487`).

Non-cascading rules a consumer must not assume away
(`events.md:60,115`; `commands.md:314`):

- `PersonCreated` does **not** create an account, role, or academic
  profile.
- `PersonActivated` does **not** reactivate any account or access.
- `PersonDeactivated` **does** require consumers to make the person's
  accounts/access ineffective, idempotently, even though `users-service`
  never touches those services' data directly.

`users-service` consumes **zero** events
(`events.md:246-257`) — it is a pure source in the dependency graph.

---

## 6. `DUPLICATE_PERSON` — interim rule

The platform docs point at `institutional_email_normalized` and say only
that the duplicate-detection *restriction* is undecided
(`data-model.md:247-251`), yet `DUPLICATE_PERSON` is already a contractual
rejection code for `CreatePerson`/`UpdatePerson`.

**Interim rule shipped in this PR:**

```sql
CREATE UNIQUE INDEX ux_persons_email_normalized ON persons (institutional_email_normalized);
```

- Total index, **not partial** — inactive persons are included. A
  deactivated person still owns their institutional identity; excluding
  them would let a re-registration create a second `Person` for the same
  human, which is exactly the invariant this rule protects.
- Does not violate the one hard constraint the docs place on the rule:
  "no debe impedir varias cuentas o roles permitidos para la misma
  Persona" (`README.md:221`) — accounts and roles live in other services
  keyed by `personId`; this only prevents two *Person* rows claiming the
  same mailbox.
- `CreatePerson` against an email owned by an `INACTIVE` person →
  `DUPLICATE_PERSON`. The caller should issue `ActivatePerson` instead of
  retrying `CreatePerson`.
- `UpdatePerson` excludes itself: `... AND person_id <> :personId`.
- Application-side `DuplicatePersonPolicy` returns a sealed `DuplicateVerdict`
  (`None` | `Duplicate(matchedPersonId, matchedOn)`), not a boolean, so a
  future fuzzy name+birthdate rule can carry richer information without
  changing any caller. Selectable via `estoma.users.duplicate-policy`
  (default `NORMALIZED_EMAIL`).

**Revisit trigger:** when the mass-import matching strategy is decided
(`data-model.md:386`).

---

## 7. Delivery semantics, idempotency, transaction boundary

- Semantics: `AT_LEAST_ONCE`. Publisher confirms on every publish. Manual
  ACK only after the local transaction commits
  (`messaging-architecture.md:434-445`).
- Consumer Inbox key: **`(consumer_name, message_id)`**, not `message_id`
  alone (`processed_messages`). First statement inside the transaction.
- A duplicate delivery is a no-op: no business change, no new outbox rows,
  transaction commits anyway (empty), message is ACKed.
- **A functional rejection is caught inside the transaction, not thrown
  past it.** If it unwound the transaction, the Inbox row would roll back
  too, the message would be redelivered forever, and it would eventually
  reach the DLQ — violating `commands.md:285` ("los rechazos funcionales
  no se consideran errores técnicos" / must not go to DLQ). Catching
  inside the transactional method and writing the rejection as a RESULT
  outbox row makes rejections simultaneously durable, idempotent, and
  never-DLQ.
- One local transaction contains exactly: Inbox insert + business change
  (if any) + RESULT outbox row + EVENT outbox row (if any). No HTTP/AMQP
  call, no email send, no other database, ever inside an open transaction
  (`data-consistency-architecture.md:108-116`).
- Optimistic concurrency via client-supplied `expectedVersion`:
  `UPDATE ... WHERE person_id = ? AND version = ?` with a rows-affected
  check. **Not** JPA `@Version` — see §13.

End-to-end pseudocode (representative of all four commands; `CreatePerson`
shown):

```
═══ AMQP delivery thread — NO transaction open ═══
onMessage:
  decode + validate structure         → invalid ⇒ straight to DLQ, ACK
  outcome = dispatcher.handle(envelope)   // the ONE @Transactional call
    catch transient failure  → retry tier, or DLQ if attempts exhausted, ACK
    catch anything else      → DLQ, ACK
  metrics.record(outcome)
  channel.basicAck(deliveryTag)       // ◀ after commit; nothing else here
  // No AMQP publish happens on this thread — the OutboxRelay owns that.

═══ dispatcher.handle — @Transactional ═══
BEGIN
  inbox.tryInsert(consumerName, messageId, correlationId)
    not inserted → return Duplicate                      // COMMIT no-op
  authorizationPolicy.check(envelope.actor, ...)          // from envelope, never payload
  try:
    validate payload; check personId not taken; check duplicate policy
    apply change (insert / guarded update)
    outbox.append(RESULT: <Command>Succeeded)
    outbox.append(EVENT: Person<X>)                       // when applicable
    return Succeeded(version)
  catch PersonRejectedException r:
    outbox.append(RESULT: <Command>Rejected {errorCode: r.code})
    return Rejected(r.code)          // NO rethrow → COMMIT → ACK → never DLQ
COMMIT
```

---

## 8. `users-db` schema

See `data-model.sql` for the full reference DDL and inline deltas. Summary:

| Table | Role |
|---|---|
| `persons` | aggregate root |
| `person_status_history` | append-only activation/deactivation log |
| `processed_messages` | Consumer Inbox — not a domain entity |
| `outbox_messages` | Transactional Outbox — not a domain entity |

Declared deltas from `data-model.md` (both additive/backwards-compatible,
proposed upstream — §10):

1. `outbox_messages.metadata jsonb NULL` — carries `destination`,
   `operationId`, `actor`, none of which are derivable from `payload` alone
   at relay time.
2. `ix_outbox_pending` is a **partial** index
   (`WHERE status IN ('PENDING','PROCESSING')`) instead of a plain
   `(status, available_at)` index — same purpose, smaller and cheaper as
   `PUBLISHED` rows accumulate.

`available_at` is deliberately overloaded: it means "do not touch this row
before this instant," serving as PENDING backoff *and* as the PROCESSING
lease deadline. This keeps the table shape identical to `data-model.md`
§4 — important because four more shared services will copy this table.

**Outbox relay claim** uses `FOR UPDATE SKIP LOCKED` across three separate
transactions (claim → publish outside any transaction → mark), because a
transaction must never stay open across an external call
(`data-consistency-architecture.md:108-116`):

```sql
WITH claimed AS (
  SELECT message_id FROM outbox_messages
  WHERE status = 'PENDING' AND available_at <= now()
  ORDER BY available_at, occurred_at
  FOR UPDATE SKIP LOCKED LIMIT :batchSize)
UPDATE outbox_messages o
   SET status = 'PROCESSING', attempts = o.attempts + 1, available_at = now() + :lease
  FROM claimed c WHERE o.message_id = c.message_id
RETURNING o.*;
-- COMMIT; publish each with publisher confirm (no transaction open); then
-- a second transaction marks PUBLISHED or releases back to PENDING.
```

`attempts` increments at *claim* time, not publish time, so a hard crash
still counts the attempt and a poison row cannot loop forever.
`OutboxSweeper` returns expired `PROCESSING` leases to `PENDING` — this is
the "messages stuck in PROCESSING" metric the platform docs mandate
(`security-observability.md:547-554`).

The outbox primary key **is** the wire `messageId`, generated inside the
originating transaction. Republishing after a lost mark reuses the same
`messageId`, which is what makes downstream `(messageId, consumerName)`
dedup work.

---

## 9. RabbitMQ topology

Exchanges are fixed by the platform docs
(`messaging-architecture.md:171-177`); routing keys and retry topology are
fixed here (companion ADR pending in the docs repo — §10, §14).

```
platform.commands (direct)  --("users-service")--> commands.users-service
platform.results  (direct)  --("bff", ...)-------> results.<component>   [owned by the consumer, not us]
platform.events   (topic)   identity.person.{created,updated,activated,deactivated}
platform.dlx      (topic)   dlq.commands.users-service --> commands.users-service.dlq
                             retry.commands.users-service.{5s,30s,300s} --> retry queues
```

Rules:

- **Commands**: routing key = `targetService` from the envelope. One queue
  per service (`messaging-architecture.md:654` rule 12); adding a new
  command never requires a topology change.
- **Results**: routing key = the `producer` field of the command being
  answered, not a hardcoded `"bff"` — this also serves the "authorized
  administrative import process" caller the docs already anticipate
  (`commands.md:254`) without any change here.
- **Events**: fixed 3-segment key `<context>.<aggregate>.<eventPastTense>`,
  lowercase, no version suffix (`messaging-architecture.md:360`). Context,
  not service name, so a future service split does not invalidate other
  services' bindings.
- **DLQ routing key is explicit**, never inherited from the original
  message: a comand dead-lettered from `commands.users-service` would
  otherwise arrive with key `users-service`, colliding with the commands
  namespace.
- **Retry via broker-side TTL tiers, not Spring AMQP `RetryTemplate`.**
  In-process retry blocks the consumer thread for the whole backoff, loses
  attempt count on pod restart, and fights the mandatory graceful shutdown
  sequence (`deployment-architecture.md:445`). TTL tiers keep threads free,
  survive restarts, and make each tier's depth observable in RabbitMQ.
  Attempt count travels in an explicit `x-estoma-attempt` header, not
  parsed from `x-death`.
- Queue config: `acknowledge-mode: manual`, `prefetch: 10`,
  `concurrency: 2`, `max-concurrency: 4`,
  `default-requeue-rejected: false`, in-process retry disabled.
- Topology declaration is gated by `estoma.messaging.topology.declare`
  (`true` in dev/test where the app self-provisions; `false` in
  staging/prod where `estoma-infra` provisions it and the runtime AMQP
  user has no `configure` permission).

**What `users-service` must NOT declare:** `results.bff`,
`events.bff-projections`, or any other consumer's queue/binding — those
belong to their owners. See §14 (R1, R9) for the operational risk this
creates until those consumers exist.

---

## 10. Interim decisions register

Each row fills a gap the platform docs leave explicitly pending, cites the
line that says so, and states when to revisit.

| # | Decision | Fills doc gap | Revisit trigger |
|---|---|---|---|
| D1 | Routing key scheme (§9) | `messaging-architecture.md:210,685`, `README.md:93` list routing keys as pending in 6 places | When the companion platform ADR (§14) is ratified or a second service needs a conflicting scheme |
| D2 | `DUPLICATE_PERSON` = unique index on normalized email (§6) | `data-model.md:247-251` | When mass-import matching strategy is decided (`data-model.md:386`) |
| D3 | Health paths served by Actuator groups, not custom controllers (§11) | doc specifies `/health/*` paths; code skeleton used bare Actuator paths | N/A — this is a genuine conflict resolution, not a placeholder |
| D4 | Rejected payload shape `{errorCode, message, details[]}` (§4) | `commands.md:321` lists "formato común para resultados de rechazo" as pending | When ratified platform-wide in the docs repo |
| D5 | Domain authorization: interim policy only checks `actor.accountId` present + `producer` allow-listed (§12) | `authorization-service` doesn't exist yet; none of the 4 commands has an authorization-failure rejection code | When `authorization-service` ships or a `FORBIDDEN`-equivalent code is added to the platform docs |
| D6 | `operationId` on RESULT only, absent on EVENT (§2) | envelope spec includes it; the event example in `events.md:164-181` omits it | When the platform docs state a rule either way |
| D7 | Outbox `metadata jsonb` column (§8) | not derivable from documented columns at relay time | Propose upstream for the other 4 shared services |
| D8 | Inbox retention: 30 days, configurable, purge job included in this PR | not addressed in platform docs | If purge cadence conflicts with an observed redelivery window |
| D9 | Retry via broker-side TTL tiers, not `spring.rabbitmq.listener.simple.retry` (§9) | `messaging-architecture.md:479` explicitly leaves timing/policy undecided | If operational experience shows TTL tiers under-serve a specific consumer |

---

## 11. Observability

Health groups (Actuator, `management.endpoint.health.group.*`):

- `startup` → `db`, `schemaVersion` (Flyway at expected version).
- `readiness` → `db`, `commandConsumer`, `outboxRelay`.
- `liveness` → `livenessState` **only** — no DB, no RabbitMQ. Liveness must
  never run an expensive query (`security-observability.md:711`).

Structured logs — mandatory snake_case fields
(`security-observability.md:446-469`), written by a custom
`EstomaJsonLogFormatter` rather than remapping a built-in format's keys
blind:

```
timestamp, level, service, environment, message,
correlation_id, message_id, account_id, error_code
(+ conditionally: causation_id, operation_id, aggregate_id, consumer, attempt, duration)
```

Never logged: passwords, hashes, tokens, secrets, full sensitive payloads.

Mandatory metrics (`security-observability.md:509-554`,
`messaging-architecture.md:596-604`):

- **Outbox**: pending count, age of oldest pending, successful publishes,
  failed publishes, retry count, messages stuck in `PROCESSING`. Backed by
  an `AtomicLong` refreshed every 10s by a scheduled query — **a
  Prometheus scrape must never trigger a query** against
  `outbox_messages`.
- **Consumer**: received, processed, duplicates detected, retries,
  technical failures, functional rejections, processing latency.

Correlation: every log line and outbound message propagates
`correlation_id`; `causation_id` links a message to whatever produced it.

---

## 12. Security

- **No mTLS / service tokens / API keys for this deliverable** — the
  platform docs leave the service-to-service auth mechanism explicitly
  open (`security-observability.md:895`). Interim posture: per-component
  RabbitMQ credentials with least privilege, TLS on the AMQP connection.
- `users-service`'s RabbitMQ user needs: consume from
  `commands.users-service`; publish to `platform.results`,
  `platform.events`, `platform.dlx`. No `configure` permission in
  staging/prod (topology is GitOps-managed by `estoma-infra`).
- HTTP surface: everything except `/health`, `/health/**`, `/metrics`,
  `/info` is `denyAll()`. No sessions (`STATELESS`), CSRF/httpBasic/
  formLogin disabled, `X-Frame-Options: DENY`, `X-Content-Type-Options:
  nosniff`. `UserDetailsServiceAutoConfiguration` is excluded — there are
  no authenticated HTTP users here, and leaving it enabled logs a random
  password on every boot.
- **Domain authorization (interim, D5):** the receiving service must not
  trust the client-provided payload for authorization
  (`security-observability.md:156`), but no rejection code exists yet for
  an authorization failure and `authorization-service` isn't built. Interim
  `PersonAuthorizationPolicy` only asserts `actor.accountId` is present and
  `producer` is in an allow-list; the actor is recorded in
  `person_status_history.changed_by_account_id` and in audit logs. This is
  a deliberate seam, not a finished authorization model.
- Secrets (DB credentials, RabbitMQ credentials) live in Kubernetes
  Secrets, never in code, images, ConfigMaps, or Git.
- **Migration DB user is separate from the runtime DB user**
  (`security-observability.md:235`) — `spring.flyway.user/password` vs
  `spring.datasource.username/password`; `V6__runtime_privileges.sql`
  grants the runtime role only what it needs.
- No Ingress reaches this service — ClusterIP only. `EXPOSE 8080` in the
  Dockerfile is for the Service, not a public route
  (`security-observability.md:270-284`). Enforced in `estoma-infra`, not
  in this repo; recorded here so the constraint isn't lost.

---

## 13. Persistence approach

**Spring JDBC (`JdbcClient`), no JPA/Hibernate** — for `persons`,
`person_status_history`, `processed_messages`, and `outbox_messages`
alike.

- `expectedVersion` is client-supplied from a read that may be minutes
  old. Hibernate's `@Version` compares against the version loaded in the
  *current* session, which after a fresh read is always current — it
  cannot detect a caller's stale expectation. The only correct primitive is
  `UPDATE ... WHERE person_id = ? AND version = ?` with a rows-affected
  check, which is what `data-model.md:295-306` describes directly.
- `@Version` would also fight the schema: Hibernate seeds a new entity's
  version at `0`; the schema declares `version integer NOT NULL DEFAULT 1`
  with `CHECK (version > 0)`.
- The Inbox's entire duplicate-detection mechanism is
  `INSERT ... ON CONFLICT (consumer_name, message_id) DO NOTHING` with a
  rows-affected check. A JPA `existsById()` + `save()` is a TOCTOU race
  under concurrent redelivery to two replicas — the exact failure the
  Inbox exists to prevent.
- The outbox relay's claim query needs `FOR UPDATE SKIP LOCKED` and
  `RETURNING`, both outside JPA's portable surface.
- No table here has an object graph worth an ORM: 11 flat columns on
  `persons`, an append-only `person_status_history` that is never loaded
  as a collection.

Accepted trade-off: hand-written SQL must stay in sync with Flyway
migrations, verified by the repository integration tests.

---

## 14. Follow-up work (outside this PR)

- **Companion ADR in `estomatologia-platform-docs`**:
  `02-architecture/decisions/0002-routing-keys-concretas.md`, registering
  §9's routing-key scheme as a platform-wide decision — not a
  `users-service`-owned fact — since the BFF and four more shared services
  will need to adopt it. Also a good place to propose D7 (outbox
  `metadata` column) and D4 (rejected payload shape) platform-wide.
- **CI workflow and `estoma-infra` manifests**, deferred to a follow-up PR:
  `.github/workflows/users-service.yaml` calling the reusable
  `service-ci.yaml`, plus in `estoma-infra`: `users-db` (logical database +
  two roles — migration and runtime), a RabbitMQ vhost/user with the
  permissions in §12, Kubernetes Secrets, and the
  `apps/users-service/overlays/develop/kustomization.yaml` overlay. The
  `update-develop-infra` CI job fails hard if that overlay doesn't already
  contain the image entry, so infra must land first.

### Open risks to track

- **R1/R9 — `results.bff` and `events.bff-projections` don't exist yet.**
  They're declared by their owners, not `users-service`. Until the BFF
  ships, a published RESULT comes back unroutable, and a published EVENT
  on a topic exchange with no matching binding is **silently dropped** —
  no error, no DLQ. Recommend `estoma-infra` pre-provisions both with
  their bindings ahead of any `develop` deploy.
- **R10 — Ingress isolation** must be enforced in `estoma-infra`; nothing
  in this repo prevents an accidental route.

---

## 15. Operations runbook

**Outbox stuck in `PROCESSING`:** check the `estoma_outbox_stuck_processing`
gauge (`OutboxMetrics`, refreshed every 10s — see §11). `OutboxSweeper`
should clear these automatically once the lease (`estoma.outbox.lease`,
default 30s) expires. If it doesn't clear, check whether the relay pod is
crash-looping before the mark step ever runs, and check
`estoma_outbox_pending`/`estoma_outbox_oldest_pending_age_seconds` for
whether the relay is keeping up at all.

**DLQ replay:** inspect `commands.users-service.dlq` in the RabbitMQ
management UI; each dead-lettered message carries `x-estoma-attempt` and
the original envelope. Fix the root cause before requeueing — dead-letters
here are either permanently invalid messages or exhausted transient
failures, never functional rejections (those never reach the DLQ, by
design — §7).

**Reading the logs for one operation:** every structured log line carries
`correlation_id` (the whole flow), `message_id` (this specific message),
and `causation_id` (what produced it) — filter by `correlation_id` to see
a command's full path through validation, dispatch, and the derived
result/event. A rejection additionally carries `error_code`.

**Local development:** see `README.md` — `docker compose up -d` starts a
local PostgreSQL and RabbitMQ that match the service's own defaults (no
env vars required), then `./mvnw spring-boot:run` self-provisions the
full topology in §9 against them (`estoma.messaging.topology-declare`
defaults to `true`).
