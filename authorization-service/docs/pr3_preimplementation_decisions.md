# PR3 Pre-implementation Decision Gate

## Status

`RESOLVED_FOR_IMPLEMENTATION`

- Service baseline: `estoma-services/develop` at `314240c359bbbf3b6b4ea6a849abc0a9f98c9287`.
- Canonical documentation reviewed: `estomatologia-platform-docs/main` at `1578db7976433c099ab77d032e70544ad4aa2ec3`.
- Related canonical gate: `03-services/shared/authorization-service/pr3-contract-gate.md`.
- Final canonical resolution: `03-services/shared/authorization-service/pr3-final-contract-resolution.md`.

## Purpose

PR3 materializes only the already completed PR2 behavior through executable
wire contracts and RabbitMQ adapters. This record preserves the decisions that
were required before implementation and their canonical resolution. It does not
change Domain or Application behavior and it does not replace canonical
platform documentation.

The canonical owner resolved D-01 through D-03 in `1578db7`. PR3 may freeze
schemas and terminal consumer behavior only as specified by that resolution.

## Resolved Decisions

### D-01 - Strict V1 schema profile

**Status:** `RESOLVED`

`pr3-final-contract-resolution.md` section 1 defines JSON Schema Draft
2020-12, `messageVersion = 1`, closed envelope/actor/payload schemas, required
properties by `messageKind`, UUID and RFC 3339 timestamp formats, and
version/pagination bounds.

V1 represents absence by omitting a property, not by serializing `null`.
`error.details` is the sole explicitly extensible object. The implementation
must reject an unsupported version, unknown/prohibited field, invalid format,
unknown enum, or failed conditional shape before invoking Application.

### D-02 - Direct BFF grant for PRACTICAS_PROFESIONALES

**Status:** `RESOLVED`

The canonical resolution section 2 preserves the behavior closed by PR2 G-10.
A BFF/SUPERADMIN direct `GrantSystemAccess(PRACTICAS_PROFESIONALES)` requires a
present, trusted, eligible Academic projection with a supported policy version.
The failure is terminal `REJECTED` with `TARGET_SCOPE_NOT_ALLOWED`.

PR3 maps and tests this behavior; it does not change the eligibility boundary
or the derived provisioning flow.

### D-03 - Exhausted transient Command failure

**Status:** `RESOLVED`

The canonical resolution section 3 fixes the hybrid terminal path. A valid
Command receives at most three consumer deliveries. On exhaustion, the adapter
persists a `FAILED` Result only when a new transaction atomically commits the
Inbox/idempotency/audit/Result Outbox obligation; it then ACKs.

When that terminal obligation cannot commit, the adapter must not fabricate a
Result. It isolates the delivery in `commands.authorization.dlq`, retaining
message, correlation, attempt, and audit traceability for controlled replay.
Outbox Relay publication failures remain independent and retry with their own
lease/fencing/backoff policy.

## Adapter-Owned Choices

The following remain implementation choices within the fixed contract:

| Area | Fixed constraint |
| --- | --- |
| Retry representation | The adapter may use `x-death`, an explicit attempt header, or controlled republish, provided the initial delivery is attempt 1, `retry.1` is attempt 2, `retry.2` is attempt 3, and traceability is preserved. |
| Retry timing | TTL and backoff are operational configuration; they do not change the canonical retry queue names or maximum attempts. |
| Topology declaration | `estoma.messaging.topology-declare` may enable local/test declaration or defer to preprovisioned infrastructure, with identical durable names and bindings in both modes. |
| Outbox retry | Relay retry remains claim/lease/fencing plus internal backoff and does not inherit the consumer three-attempt limit. |

These constraints are defined by
`02-architecture/rabbitmq-topology-naming.md`,
`03-services/shared/authorization-service/pr3-contract-gate.md`, and
`03-services/shared/authorization-service/pr3-final-contract-resolution.md`.

## Implementation Baseline

PR3 must use this record with:

1. `02-architecture/messaging-architecture.md`.
2. `02-architecture/rabbitmq-topology-naming.md`.
3. `03-services/shared/authorization-service/pr3-contract-gate.md`.
4. `03-services/shared/authorization-service/pr3-final-contract-resolution.md`.

The implementation branch is based on `estoma-services/develop` at
`314240c359bbbf3b6b4ea6a849abc0a9f98c9287` and must stop if a later canonical
contract conflicts with the wire behavior under construction.
