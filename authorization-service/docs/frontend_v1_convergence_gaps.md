# Frontend V1 Backend Convergence Gaps — authorization-service

Status: `CLOSED`

Scope: authorization data needed to compose the closed Front V1 real session context and to apply the same effective-system access rule to system-scoped BFF reads such as the Wash mailbox.

This register records only the remaining owner-side gap. Role and SystemAccess recovery already exist and are not reopened here.

## Architectural boundary

`authorization-service` owns the authorization catalog, roles and system access grants. It must expose owner-authoritative state through asynchronous/recoverable contracts. The BFF owns HTTP composition and must evaluate its read-side effective-system view from local projections rather than by synchronously querying Authorization.

## Resolved baseline — Role and SystemAccess are recoverable

Existing Authorization recovery provides the BFF with sufficient Role and SystemAccess state, including status/version information, to rebuild those projections. No new owner contract is required for those two aggregates as part of this convergence work.

This distinction matters because the remaining gap is narrower: the BFF cannot reconstruct the **System catalog status** required to decide whether an otherwise granted system is currently available.

## GAP-AUTHZ-01 — System catalog status is not reconstructible by the BFF

- Severity: `HIGH`
- Status: `CLOSED`
- Owner: `authorization-service`
- Consumer: `platform-bff`
- Affected flows: `GET /api/v1/me`, Wash mailbox authorization, reusable system-scoped BFF reads

### Current state

Authorization owns the System catalog and its lifecycle/status. The domain already models that status and the current V1 catalog contains the canonical system codes, including:

```text
LAVADO_ULTRASONICO
PRACTICAS_PROFESIONALES
```

The BFF can recover Role and SystemAccess, but its recoverable Authorization projection surface does not currently provide the System catalog status needed to rebuild an effective-system view.

### Why SystemAccess alone is insufficient

A grant does not imply that the target System itself is operational/active. The BFF's effective client context must therefore not expose a system solely because an account has an active SystemAccess row.

The intended composition is conceptually:

```text
effective system
  = System.status == ACTIVE
    AND SystemAccess.status == ACTIVE
    AND academic eligibility satisfied when that system requires it
```

Academic eligibility remains Academic-owned. Authorization is responsible only for making its own System status reconstructible.

### Impact

Without System status, the BFF has two unsafe choices:

- expose a system from SystemAccess even if the catalog marks it unavailable; or
- synchronously query Authorization during HTTP reads.

Both violate the closed V1 convergence model. The same missing status would also force mailbox-specific authorization logic to diverge from `/me`.

### Resolution

Authorization now exposes durable, asynchronous and recoverable System catalog snapshots through:

```text
GetSystemCatalogSnapshot(systemCode)
ListSystemCatalogSnapshotsPage(cursor?, limit)
```

Both Queries are limited to `bff` and write their Result through the Authorization Transactional Outbox before the inbound Query is acknowledged. The reconstructible item includes:

```text
systemCode
status
sourceVersion
```

`systemId` is included as the stable owner identity, while `systemCode` remains the canonical consumer key. `systems.version` is the monotonic `sourceVersion`; a controlled configuration change of `systems.status` increments it atomically. A BFF point recovery after that change can therefore apply the new status only when its source version is newer, without a synchronous HTTP read or an unversioned timestamp comparison.

The contract supports:

1. bootstrap/rebuild of all relevant V1 systems;
2. applying incremental status changes through a versioned point snapshot;
3. idempotent duplicate handling;
4. monotonic source-version protection;
5. authoritative `NOT_FOUND`/recovery semantics consistent with the service's current recovery architecture.

### Dependencies

- `platform-bff` GAP-BFF-03 depends on this gap.
- Existing Role and SystemAccess recovery is already sufficient and must be reused.
- Existing Academic eligibility projection is a separate input to BFF composition and does not belong in this owner contract.

### Acceptance criteria

- A fresh BFF projection can rebuild the current status of all supported systems without a synchronous Authorization read.
- A System status change increments `sourceVersion`; applying its point snapshot produces the same final state as a rebuild.
- System projection processing is idempotent and source-version aware.
- Canonical system codes are preserved; no frontend aliases such as `wash-ultrasonico` are introduced into owner contracts.
- No eligibility rule is moved into Authorization merely to satisfy the BFF composition.
- Existing Role/SystemAccess recovery and authorization behavior remain backward compatible.

### Explicit non-solutions

Do not:

- treat active SystemAccess as proof that the System catalog is active;
- query Authorization synchronously from `/me` or mailbox reads;
- duplicate System status inside Academic or Communications;
- make Authorization calculate the BFF's final `availableSystemCodes` list;
- generalize Wash-specific behavior into the Practices authorization model.

## Consumer composition contract

Once this gap closes, the BFF should own one reusable effective-system abstraction. At a minimum it combines:

```text
Authorization System status
+ Authorization SystemAccess
+ Academic eligibility when applicable
```

That same abstraction must drive `/api/v1/me` and access checks for system-scoped reads. Authorization should not create endpoint-specific grants for either flow.

## Service-level Definition of Done

`authorization-service` is complete for these Front V1 convergence gaps when:

- System catalog status is asynchronously consumable and recoverable;
- bootstrap/rebuild and incremental projection reach equivalent state;
- consumers can distinguish an active grant to an inactive system from an effective system;
- existing Role/SystemAccess recovery remains intact;
- no synchronous BFF dependency or cross-domain eligibility ownership is introduced;
- Authorization contract and regression suites remain green.
