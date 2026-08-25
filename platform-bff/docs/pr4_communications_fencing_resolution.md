# PR4 — Communications Native Fencing Resolution

## Status

Implementation addendum for the PR4 correction discovered while reconciling `platform-bff` against the current `communications-service` wire behavior.

Canonical source: `estomatologia-platform-docs` PR #41, `03-services/edge/bff/pr4-communications-fencing-resolution.md`.

If this addendum conflicts with the merged canonical documentation, canonical documentation wins.

## Problem

The initial PR4 implementation treated the BFF mailbox read models as owner-versioned streams. That does not match Communications:

- `InboxMessageCreated` is emitted with Communication aggregate metadata; its `aggregateVersion` is not an InboxMessage version.
- `InboxMessageExpired` is emitted by a batch command without aggregate metadata.
- `InboxVisited` is emitted without aggregate metadata and its owner state is already monotonic by `visitedAt`.

The BFF must not manufacture a version or reuse the version of another aggregate solely to fit its schema.

## V1 Rule

Exact delivery dedupe remains `consumerName + messageId` through Consumer Inbox.

Native fencing is:

```text
InboxMessageCreated
  identity = inboxMessageId
  aggregateId, when present, must equal communicationId
  aggregateVersion is not a mailbox source version

InboxMessageExpired
  identity = inboxMessageId
  monotonic fence = expiredAt
  aggregate metadata is optional/not required

InboxVisited
  identity = accountId + systemCode
  monotonic fence = visitedAt
  aggregate metadata is optional/not required
```

## Persistence

`mailbox_messages_read_model` has no `source_version`. It stores `expired_at` separately from the optional configured `expires_at`.

An expiration may arrive before creation. In that case PR4 persists a partial tombstone containing `inbox_message_id`, `expired_at`, and `updated_at`. A later `InboxMessageCreated` fills missing content but never clears `expired_at`.

`mailbox_visits_read_model` has no `source_version`; an incoming visit only updates the row when `visitedAt` is strictly newer than the stored `last_visited_at`.

## Acceptance Cases

PR4 must prove:

1. `InboxVisited` with null `aggregateId/aggregateVersion` is accepted.
2. A stale visit cannot move `last_visited_at` backwards.
3. `InboxMessageExpired` with null aggregate metadata is accepted.
4. Expiration before creation creates a tombstone.
5. Late creation completes the tombstone without reviving the message.
6. An older expiration cannot move `expired_at` backwards.
7. `InboxMessageCreated.aggregateId` is validated against `communicationId`; mismatch rolls back Inbox/read-model mutation.
8. No mailbox persistence or projector path depends on a synthetic numeric `source_version`.
