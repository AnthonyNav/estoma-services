package com.estoma.users.messaging.outbound;

/**
 * Technical, sanitized failure codes the Outbox pipeline can persist in
 * {@code outbox_messages.last_error_code} and log as {@code error_code}
 * (docs/system-contract.md §8, §11). Never a payload, a stack trace, or
 * {@code exception.getMessage()} — just one of these constants.
 *
 * <p>{@code LEASE_EXPIRED} is not a publication failure but belongs here
 * because it, too, is persistable: the sweeper writes it when it reclaims
 * a stale {@code PROCESSING} row.
 *
 * <p>{@code CLAIM_LOST_BEFORE_MARK} deliberately does NOT belong to this
 * enum. Once a worker's claim is gone, it has no right to write the row
 * anymore — that case is a log line plus the {@code
 * estoma.outbox.claim_lost} counter, never a persisted column value.
 */
public enum OutboxFailureCode {
  PUBLISH_NACK,
  PUBLISH_TIMEOUT,
  PUBLISH_RETURNED,
  PUBLISH_CONNECTION_FAILURE,
  PUBLISH_INTERRUPTED,
  PUBLISH_UNEXPECTED_ERROR,
  LEASE_EXPIRED
}
