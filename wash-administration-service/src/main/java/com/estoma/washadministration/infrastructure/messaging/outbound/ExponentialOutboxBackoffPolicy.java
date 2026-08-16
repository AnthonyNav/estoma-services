package com.estoma.washadministration.infrastructure.messaging.outbound;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ExponentialOutboxBackoffPolicy implements OutboxBackoffPolicy {

  @Override
  public Duration delayForAttempt(int attempt) {
    long seconds = Math.min(300L, 1L << Math.min(8, Math.max(0, attempt - 1)));
    return Duration.ofSeconds(seconds);
  }
}
