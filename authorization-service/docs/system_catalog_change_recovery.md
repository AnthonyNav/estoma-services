# System catalog change recovery gate

Scope: controlled changes to `authorization-service.systems.status` and the
`platform-bff` System catalog projection that consumes the snapshot contract.

`systems` is static V1 configuration loaded or changed through approved
migrations. It is not a runtime event-sourced aggregate. Migration V4 makes
`systems.version` a positive monotonic source version and increments it only
when `status` changes.

## Required deployment procedure

1. The approved migration or controlled operation lists every affected
   canonical `systemCode` in its release/change record.
2. Deploy the Authorization migration before enabling the BFF consumer change.
3. BFF binds its Results consumer and performs a bootstrap with
   `ListSystemCatalogSnapshotsPage` until `hasMore = false`. Each item is
   applied only when its `sourceVersion` is newer than the local projection.
4. After every later controlled status change, BFF issues
   `GetSystemCatalogSnapshot(systemCode)` once for each affected code. It
   waits for the correlated Result and persists it only when `sourceVersion`
   is newer than its local value.
5. Until that Result is persisted, BFF must fail closed for the affected
   System: it must not expose it from `/me` or authorize a system-scoped read.
   A failed, missing or stale Result keeps that fail-closed state; it is never
   replaced with a timestamp or a synchronous Authorization request.

This procedure is a deployment gate for the BFF consumer PR. No synthetic
Authorization Event is emitted from the migration: an Event requires a valid
causal inbound message, which a direct configuration migration does not have.

## Verification evidence for a rollout

- The Query Result has `producer = authorization`, routes to `bff`, and has
  `causationId = Query.messageId`.
- The Result item has the affected `systemCode` and a `sourceVersion` newer
  than the local BFF projection before the change.
- BFF records the new status before the affected System becomes effective.
- If the recovery cannot complete, operational evidence shows the affected
  System remains unavailable rather than preserving the prior active view.

The external canonical documentation is synchronized in a separate docs-only
PR. This service PR intentionally contains no `platform-bff` implementation.
