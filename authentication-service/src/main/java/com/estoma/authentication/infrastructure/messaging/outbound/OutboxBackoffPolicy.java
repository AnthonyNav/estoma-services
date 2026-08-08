package com.estoma.authentication.infrastructure.messaging.outbound;

import java.time.Duration;

/**
 * How long a failed Outbox row waits before it becomes reclaimable
 * again, as a function of the attempt count already persisted by the
 * claim that just failed (system_contract.md "Outbox Operational
 * Policy": "exponential backoff via available_at").
 */
public interface OutboxBackoffPolicy {

  /**
   * @param attempts the {@code attempts} value already persisted on the row (i.e. after the
   *     failed attempt), always {@code >= 1}
   */
  Duration delayForAttempt(int attempts);
}
