# PR3 Contract Decisions

## Purpose

PR1 and PR2 are merged. PR3 connects the already-tested application behavior to the platform messaging contracts. This document records the public-contract decisions that must be resolved in `estomatologia-platform-docs` before that implementation starts.

It does not authorize a local interpretation, aliases, or compatibility topology. Once each decision has a canonical resolution, update this document with the source reference and unblock the affected PR3 work.

## Status

**Blocked.** No RabbitMQ topology, wire DTO, listener, relay, or recovery worker may be implemented while any decision below remains unresolved.

## Decisions Required

### 1. Logical Component ID And Ingress Queues

**Question:** Which names are canonical for the service command and query queues?

The platform architecture defines the logical component ID `wash-administration`, requiring `commands.wash-administration` and `queries.wash-administration`. The service-specific interface and query documents instead name queues with the `-service` suffix.

Required canonical decision:

```text
logicalComponentId
command queue
query queue
whether any alias is permitted
```

Implementation constraint: there must be one approved name for each queue and no permanent alias.

Sources:

- `02-architecture/messaging-architecture.md`, `logicalComponentId` and routing conventions
- `03-services/domains/lavado-ultrasonico/wash-administration-service/interfaces.md`, Messaging
- `03-services/domains/lavado-ultrasonico/wash-administration-service/queries.md`, GetWashAdministrationConfiguration flow

### 2. Query Requester IDs And Rejected Query Routing

**Question:** Which logical IDs may issue `GetWashAdministrationConfiguration`?

The implementation plan uses `appointments` and `wash-execution`, while the service query contract uses `appointments-service` and `wash-execution-service`. The producer is also the Result routing key, so this selects different Result queues.

Required canonical decision:

```text
allowed producer IDs
Result routing key for each requester
Result behavior for a syntactically valid but non-allowlisted requester
handling of malformed or unknown producer IDs
```

Sources:

- `02-architecture/messaging-architecture.md`, Result routing and logical IDs
- `03-services/domains/lavado-ultrasonico/wash-administration-service/queries.md`, authorized producers
- `wash-administration-service/docs/execution_plan.md`, PR3 Query Wire

### 3. Authorization Recovery Result Ingress

**Question:** Is `results.wash-administration` part of the required PR3 topology and what messages may it consume?

Authorization recovery requires `GetAccountAuthorizationSnapshot` and `ListAccountAuthorizationSnapshotsPage` Results. The PR1 alignment assigns their consumption and recovery jobs to PR3, but the PR3 topology and listener scope do not fully specify this ingress.

Required canonical decision:

```text
existence and binding of results.wash-administration
allowlisted Authorization Result message types
messageId deduplication rule
causationId to recovery-job matching rule
atomic snapshot and recovery-job update rule
```

Sources:

- `wash-administration-service/docs/pr1_contract_alignment.md`, PR3 Boundary
- `03-services/shared/authorization-service/queries.md`, authorization snapshot Results
- `03-services/domains/lavado-ultrasonico/wash-administration-service/authorization-projection.md`, recovery flow

### 4. Authorization Snapshot Result Schema

**Question:** What exact envelope and payload schema does each Authorization recovery Result use?

Wash Administration currently models operation Results with `SUCCEEDED`, `REJECTED`, `FAILED`, and `EXPIRED`. Authorization snapshot queries describe `FOUND` and `NOT_FOUND`, and do not conclusively place all snapshot fields in one Result payload shape. A strict recovery consumer cannot safely infer the schema.

Required canonical decision for each snapshot Result:

```text
messageType and messageVersion
allowed payload.status values
payload.data schema for success/found
payload.error schema for rejection/failure/not-found
aggregate and causation metadata requirements
```

Sources:

- `03-services/shared/authorization-service/queries.md`, snapshot query contracts
- `02-architecture/messaging-architecture.md`, Result envelope conventions
- `wash-administration-service/docs/system_contract.md`, Result vocabulary

### 5. Actor And Provenance By Message Class

**Question:** Which actor and causal metadata must be used for each externally visible message class?

BFF Commands define an `ACCOUNT` actor, but the following remain unspecified or incomplete: command Results, derived configuration Events, service-to-service Queries, Query Results, Authorization recovery Results, and bootstrap `ResourcePriorityConfigured`.

Required canonical decision:

```text
actor for each message class
correlationId propagation or creation rule
causationId rule
operationId and idempotencyKey propagation rule
bootstrap event provenance rule
```

Sources:

- `02-architecture/messaging-architecture.md`, ActorContext and envelope provenance
- `03-services/domains/lavado-ultrasonico/wash-administration-service/commands.md`, command actor rules
- `03-services/shared/authorization-service/interfaces.md`, service-to-service actor convention

### 6. Produced Configuration Event Contracts

**Question:** What is the exact wire contract for every produced configuration Event?

PR3 lists 19 administrative Events plus `ResourcePriorityConfigured`, but leaves the exact `subject.event` routing names for implementation time. Several payloads are narrative rather than a required field schema, and `occurredAt` appears both in payload descriptions and the mandatory envelope.

Required canonical decision for every Event:

```text
messageType and messageVersion
platform.events routing key
required and optional payload fields
whether aggregateId, aggregateVersion, and occurredAt are envelope-only or duplicated in payload
actor and provenance metadata
```

Sources:

- `02-architecture/messaging-architecture.md`, Event routing convention
- `03-services/domains/lavado-ultrasonico/wash-administration-service/events.md`, Event catalog
- `wash-administration-service/docs/execution_plan.md`, Produced Events

### 7. Terminal Failure, Retry, And DLQ Classification

**Question:** How is each inbound message class classified after a functional rejection, persistible technical failure, transient failure, invalid message, or exhausted retry?

The platform architecture requires a persistible terminal Command or Query failure to emit a `FAILED` Result, while PR3 also requires technical retry and DLQ behavior. The service contract does not define this outcome matrix for Commands, configuration Queries, Authorization Events, or Authorization recovery Results.

Required canonical decision:

```text
functional rejection: Result and ACK behavior
persistible terminal Command/Query failure: FAILED Result and ACK behavior
transient failure: retry count and backoff
invalid or non-persistible failure: DLQ behavior
exhausted retry behavior
Authorization Event and recovery-Result behavior, which do not have a requester Result
```

Sources:

- `02-architecture/messaging-architecture.md`, retry, DLQ, and terminal-failure policy
- `03-services/domains/lavado-ultrasonico/wash-administration-service/interfaces.md`, error handling
- `wash-administration-service/docs/execution_plan.md`, listener and test requirements

### 8. Command Result Message Type Convention

**Question:** Does each Command publish one Result message type or separate success and rejection message types?

The local service contract specifies `<OperationName>Result` with outcome in `payload.status`. Some command documentation names `...Succeeded` and `...Rejected` as Results, which can be interpreted as wire message types.

Required canonical decision:

```text
one messageType per operation, or separate outcome message types
the authoritative Result envelope and payload field names
```

Recommendation for consistency with the platform Result envelope: one `<OperationName>Result` message type, with the terminal outcome only in `payload.status`.

Sources:

- `wash-administration-service/docs/system_contract.md`, Result model
- `03-services/domains/lavado-ultrasonico/wash-administration-service/commands.md`, Result wording
- `03-services/domains/lavado-ultrasonico/wash-administration-service/interfaces.md`, Result convention

## Resolution Checklist

- [ ] Decision 1 resolved in canonical documentation.
- [ ] Decision 2 resolved in canonical documentation.
- [ ] Decision 3 resolved in canonical documentation.
- [ ] Decision 4 resolved in canonical documentation.
- [ ] Decision 5 resolved in canonical documentation.
- [ ] Decision 6 resolved in canonical documentation.
- [ ] Decision 7 resolved in canonical documentation.
- [ ] Decision 8 resolved in canonical documentation.
- [ ] Each resolution is linked here before starting PR3 implementation.
