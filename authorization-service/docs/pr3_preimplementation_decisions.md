# PR3 Pre-implementation Decision Gate

## Status

`PENDING_CANONICAL_DECISIONS`

- Service baseline: `estoma-services/develop` at `314240c359bbbf3b6b4ea6a849abc0a9f98c9287`.
- Canonical documentation reviewed: `estomatologia-platform-docs/main` at `42281c5fd1050cbab40a4e0536cc54e694e1fa30`.
- Related canonical gate: `03-services/shared/authorization-service/pr3-contract-gate.md`.

## Purpose

PR3 may materialize only the already completed PR2 behavior through executable
wire contracts and RabbitMQ adapters. This record identifies the decisions that
would otherwise make the public V1 contract or terminal-delivery behavior
ambiguous. It does not change Domain or Application behavior and it does not
replace canonical platform documentation.

No PR3 implementation code that freezes a schema or terminal consumer outcome
may begin for an open decision below. Once resolved, the canonical owner must
update `estomatologia-platform-docs`; this record can then be marked resolved
with the canonical commit.

## Decisions Required

### D-01 - Strict V1 schema profile

**Status:** `OPEN`

The canonical gate requires `additionalProperties: false` for the common
envelope, `actor`, and every Authorization payload. It does not fully define
the executable requiredness, nullability, lexical formats, or bounds that JSON
Schemas must enforce.

The decision must define all of the following for V1:

| Area | Decision required |
| --- | --- |
| Common envelope | Exact `required` properties by `messageKind`, including whether `causationId`, `operationId`, `idempotencyKey`, `aggregateId`, and `aggregateVersion` are omitted or encoded as `null` when inapplicable. |
| Actor and identifiers | UUID and timestamp lexical formats, identifier nullability, and the valid `ACCOUNT`/`SYSTEM` discriminator shapes. |
| Result payload | Whether optional `data` and `error` are omitted or `null`; legal `status`/`data`/`error` combinations; the schema of `error.details`. |
| Command, Event, and Query payloads | Required fields, nullable fields, minimum versions, and closed enum values for every V1 message. |
| Snapshot pagination | Cursor representation, `limit` minimum/maximum, and `hasMore`/`nextCursor` consistency. |

**Evidence:**

- `02-architecture/messaging-architecture.md` section 8 describes fields as
  applicable but does not define all schema-level combinations.
- `03-services/shared/authorization-service/pr3-contract-gate.md` section 6
  requires strict schemas but leaves the exact profile open.
- `03-services/shared/authorization-service/events.md` section 2.1 includes
  optional envelope metadata in an example, while the executable null/omission
  policy is not stated.

**Recommended resolution:** Canonical documentation should define one V1 JSON
Schema profile before Authorization publishes artifacts. Optional properties
should have one consistent representation across all contracts, and every
schema-level bound must be explicit rather than inferred by a listener.

**Why this blocks coding:** Choosing these rules locally would create an
externally visible compatibility policy before the platform has selected one.
Contract tests cannot establish interoperability until this profile is fixed.

### D-02 - Direct BFF grant for PRACTICAS_PROFESIONALES

**Status:** `OPEN`

PR2 currently rejects a BFF `GrantSystemAccess(PRACTICAS_PROFESIONALES)` unless
the target has a present, trusted, eligible Academic projection with a supported
policy version. This was intentionally closed in local audit finding G-10.
The canonical `GrantSystemAccess` description currently lists normal account,
role, system, compatibility, scope, and uniqueness validation, but does not
explicitly state this extra predicate or its rejection code.

The decision must select one behavior:

| Option | Behavior |
| --- | --- |
| A | Canonicalize the existing PR2 behavior: BFF/SUPERADMIN direct Practices grants require an eligible, trusted, supported Academic eligibility projection. Define the documented rejection as `TARGET_SCOPE_NOT_ALLOWED`. |
| B | Remove that predicate from Authorization and update PR2 behavior, tests, and the audit record before PR3 begins. |

**Recommended resolution:** Select option A. It preserves the already merged
PR2 fail-closed behavior and prevents an administrative Command from bypassing
the Academic eligibility boundary. PR3 should map and test the chosen behavior;
it must not change it silently.

**Evidence:**

- `docs/implementation_gaps.md` G-10 records the requirement and its PR2
  regression coverage.
- `AuthorizationCommandDispatcher.validatePracticesGrantEligibility` enforces
  the predicate with `TARGET_SCOPE_NOT_ALLOWED`.
- `03-services/shared/authorization-service/commands.md` section 5.2 does not
  state the predicate explicitly.

**Why this blocks coding:** The predicate and rejection code are observable in
Command Result contracts. Freezing a schema and E2E behavior without a canonical
decision would let the service and the platform documentation drift.

### D-03 - Exhausted transient Command failure

**Status:** `OPEN`

The platform Result model says an exhausted retryable Command failure becomes a
terminal `FAILED` Result. Authorization-specific documentation instead permits
DLQ isolation when the failure cannot be durably persisted. PR2 deliberately
rethrows retryable failures so PR3 transport owns the final retry decision, but
it does not define the exhausted-retry path.

The decision must define the final disposition of a valid Command after its
third consumer delivery fails transiently:

| Option | Behavior |
| --- | --- |
| A | Require a durable `FAILED` Result for every valid exhausted Command. Define how the terminal Result obligation is persisted when the original failure is retryable. |
| B | Permit a valid Command whose terminal Result cannot be committed to enter the DLQ without a Result, with manual audited replay and requester reconciliation. |
| C | Define a hybrid: persist `FAILED` when a fresh terminal transaction commits; otherwise isolate the unpersistible delivery in the DLQ without fabricating a Result. |

**Recommended resolution:** Select option C and state it in the canonical
platform and Authorization contracts. A `FAILED` Result must only be emitted
after its Inbox/idempotency/audit/Outbox obligation commits. If that transaction
cannot commit, the adapter must not falsely report a terminal outcome; it must
isolate the delivery with enough traceability for controlled recovery.

**Evidence:**

- `02-architecture/messaging-architecture.md` section 3 describes exhausted
  retryable failures as `FAILED`.
- `03-services/shared/authorization-service/commands.md` section 9 sends an
  invalid or unpersistible exhausted failure to the DLQ.
- `docs/implementation_gaps.md` G-12 and
  `AuthorizationCommandProcessor` deliberately expose retryable failures to
  PR3 transport policy.

**Why this blocks coding:** The listener's final ACK, retry, DLQ, and Result
publication behavior depends on this decision. Choosing one path locally can
either lose the requester terminal result or fabricate a result without a
durable transaction.

## Decisions Already Delegated To The Adapter

The following are implementation choices, not additional platform decisions:

| Area | Fixed constraint |
| --- | --- |
| Retry representation | The adapter may use `x-death`, an explicit attempt header, or controlled republish, provided the initial delivery is attempt 1, `retry.1` is attempt 2, `retry.2` is attempt 3, and traceability is preserved. |
| Retry timing | TTL and backoff are operational configuration; they do not change the canonical retry queue names or maximum attempts. |
| Topology declaration | `estoma.messaging.topology-declare` may enable local/test declaration or defer to preprovisioned infrastructure, with identical durable names and bindings in both modes. |
| Outbox retry | Relay retry remains claim/lease/fencing plus internal backoff and does not inherit the consumer three-attempt limit. |

These constraints are defined by
`02-architecture/rabbitmq-topology-naming.md` sections 5 through 8 and
`03-services/shared/authorization-service/pr3-contract-gate.md` sections 2,
5, and 9.

## Resolution Checklist

Before implementation starts:

1. Resolve D-01 through D-03 in canonical platform documentation.
2. Record the canonical commit and selected outcomes in this file.
3. Recheck `estoma-services/develop` and canonical documents for drift.
4. Create the implementation branch using the selected contract as its fixed
   baseline.
