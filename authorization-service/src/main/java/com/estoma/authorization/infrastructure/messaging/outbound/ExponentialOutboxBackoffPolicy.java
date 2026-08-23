package com.estoma.authorization.infrastructure.messaging.outbound;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
final class ExponentialOutboxBackoffPolicy implements OutboxBackoffPolicy {

  @Override
  public Duration delayForAttempt(int attempt) {
    int bounded = Math.max(1, Math.min(attempt, 6));
    return Duration.ofSeconds(1L << (bounded - 1));
  }
}
