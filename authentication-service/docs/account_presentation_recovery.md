# Account Presentation recovery

`authentication-service` owns the Account presentation fields required by BFF:

```text
accountId
personId
loginType
loginIdentifier
status
sourceVersion
```

The `Account` aggregate version is the monotonic `sourceVersion`. No Event or
snapshot includes a credential hash, password, refresh token, access token or
recovery code.

## Incremental path

`AccountPresentationUpdated` is emitted with the current presentation snapshot
after every Account creation, activation, deactivation and login-identifier
change. It uses `authentication.account.presentation-updated`; its envelope
`aggregateVersion` is the monotonic source version. This leaves the existing
Account lifecycle Events byte-compatible for their current consumers.

Consumers apply a presentation Event only when its `aggregateVersion` is newer
than their local source version.

## Recovery path

Only `SYSTEM/bff` can issue:

```text
GetAccountPresentationSnapshot(accountId)
ListAccountPresentationSnapshotsPage(cursor?, limit=200)
```

Results are persisted through Authentication's Transactional Outbox before the
Query is acknowledged and route to `bff`. `FOUND` and page items carry the
full presentation fields plus `updatedAt`; `NOT_FOUND` is authoritative for a
point recovery. BFF binds incremental Account Events before a rebuild and
applies each snapshot only when `sourceVersion` is newer.

The legacy Account-status Query family remains restricted to `authorization`
and intentionally omits `loginIdentifier`.
