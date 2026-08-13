# PR1 Canonical Contract Alignment

## Purpose

Record the contract reconciliation performed while closing PR1 of `wash-administration-service`.

The local `system_contract.md` was originally prepared against `estomatologia-platform-docs/main` at:

```text
af603c80ebb1259b155209e81a0fd3a0469a6fba
```

Before accepting PR1, the canonical documentation was rechecked at:

```text
ba9c03e2d7bccfc48211bdc4279f3c23bd03f6db
```

The canonical repository remains authoritative over this file.

## Relevant Drift For Wash Administration

The functional Wash Administration contract remained compatible with the PR1 domain/persistence model. The relevant new contract is:

```text
03-services/domains/lavado-ultrasonico/
  wash-administration-service/authorization-projection.md
```

It adds explicit recovery requirements for the local Authorization projections:

- normal synchronization still uses Authorization Events;
- point repair uses `GetAccountAuthorizationSnapshot`;
- full rebuild uses `ListAccountAuthorizationSnapshotsPage`;
- recovery Results are consumed from `results.wash-administration`;
- FULL rebuild is fail-closed while rebuilding;
- `role_source_version` and `access_source_version` remain independent;
- recovery progress, cursor, attempts and current Query correlation must be durable;
- a late `NOT_FOUND` must never erase a newer Event-applied projection;
- no authorization decision may synchronously Query Authorization and continue the same business request.

Normative recovery behavior lives in:

```text
03-services/shared/authorization-service/projection-recovery.md
```

## PR1 Resolution

PR1 continues to own foundation only. It therefore adds durable persistence for recovery jobs without implementing transport or recovery workers:

```text
projection_recovery_jobs
├── scope = FULL | ENTITY
├── status = PENDING | WAITING_RESULT | COMPLETED | FAILED
├── entity_key?
├── cursor?
├── query_attempt
├── current_query_type?
├── current_query_message_id?
├── current_operation_id?
├── current_correlation_id?
├── attempts
├── last_error_code?
├── claim_token?
└── lease_until?
```

Database constraints enforce:

- one active FULL recovery for Authorization;
- one active ENTITY recovery per entity key;
- FULL jobs cannot carry an entity key;
- ENTITY jobs require an entity key;
- `WAITING_RESULT` requires the current Query identifiers;
- terminal jobs clear current Query/claim state and have `completed_at`;
- claim token and lease are paired.

No `ProjectionRecoveryJobRepository`, worker, Query publisher, Result consumer or RabbitMQ topology is introduced in PR1. Those interfaces should be created only when PR3 implements the real recovery workflow, avoiding speculative application ports.

## Other PR1 Hardening From Review

PR1 also closes the following foundation issues before merge:

1. Cabin retirement and Tank registration serialize on the same parent Cabin row. A concurrent operation can no longer commit `Cabin = RETIRED` with a new non-retired Tank.
2. Repository updates require candidate version `expectedVersion + 1`, while PostgreSQL performs `version = version + 1` atomically.
3. Command idempotency records only transition from `IN_PROGRESS` to a terminal state; terminal outcomes cannot overwrite one another.
4. A dedicated GitHub Actions workflow runs `./mvnw -B clean verify` for Wash Administration without prematurely enabling deployment/infra wiring.

## PR2 Boundary

PR2 may implement domain/application behavior using the persistence foundation above, but remains broker-agnostic. It must not start Authorization recovery Queries or RabbitMQ listeners.

## PR3 Boundary

PR3 owns:

- Authorization Event listeners;
- `GetAccountAuthorizationSnapshot` and `ListAccountAuthorizationSnapshotsPage` transport;
- `results.wash-administration` consumption;
- durable recovery-job worker/state transitions;
- FULL/ENTITY recovery execution and fail-closed readiness behavior;
- RabbitMQ topology, Outbox relay, retry/DLQ and wire contracts.

Before PR3 starts, recheck `estomatologia-platform-docs/main` again and resolve any remaining canonical naming conflict before wiring queues/routing keys.
