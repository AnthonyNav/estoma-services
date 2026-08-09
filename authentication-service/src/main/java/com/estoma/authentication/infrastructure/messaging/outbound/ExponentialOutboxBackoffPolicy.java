package com.estoma.authentication.infrastructure.messaging.outbound;

import java.time.Duration;
import org.springframework.stereotype.Component;

/** {@code 2s * 2^(attempts-1)}, capped at 5 minutes -- the same order of magnitude as the command retry tiers. No jitter in v1. */
@Component
public class ExponentialOutboxBackoffPolicy implements OutboxBackoffPolicy {

  private static final Duration BASE_DELAY = Duration.ofSeconds(2);
  private static final Duration MAX_DELAY = Duration.ofMinutes(5);

  @Override
  public Duration delayForAttempt(int attempts) {
    if (attempts <= 0) {
      throw new IllegalArgumentException("attempts must be positive, was " + attempts);
    }
    long factor = 1L << Math.min(attempts - 1, 10);
    Duration delay = BASE_DELAY.multipliedBy(factor);
    return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
  }
}
