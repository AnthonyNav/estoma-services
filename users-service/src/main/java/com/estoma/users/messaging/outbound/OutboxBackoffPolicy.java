package com.estoma.users.messaging.outbound;

import java.time.Duration;

/**
 * How long the Outbox waits before a failed row becomes reclaimable
 * again, as a function of the attempt count already persisted by the
 * claim that just failed (docs/system-contract.md §8). Kept behind an
 * interface so a future jitter strategy can be swapped in without
 * touching {@code OutboxRelay} or {@code OutboxSweeper}.
 */
public interface OutboxBackoffPolicy {

  /**
   * @param attempts the {@code attempts} value already persisted on the
   *     row (i.e. after the claim that failed), always {@code >= 1}
   * @return how long to wait before the row is reclaimable again
   */
  Duration delayForAttempt(int attempts);
}
