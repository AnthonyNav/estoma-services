package com.estoma.users.messaging.outbound;

import com.estoma.users.config.OutboxProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Indexes {@code estoma.outbox.retry-delays} by attempt number, reusing
 * the last configured delay for every attempt beyond the list's length.
 * No jitter (docs/system-contract.md §8, this round's explicit decision).
 */
@Component
public class ConfiguredOutboxBackoffPolicy implements OutboxBackoffPolicy {

  private final List<Duration> retryDelays;

  public ConfiguredOutboxBackoffPolicy(OutboxProperties properties) {
    this.retryDelays = properties.retryDelays();
  }

  @Override
  public Duration delayForAttempt(int attempts) {
    if (attempts <= 0) {
      throw new IllegalArgumentException("attempts must be positive, was " + attempts);
    }
    int index = Math.min(attempts - 1, retryDelays.size() - 1);
    return retryDelays.get(index);
  }
}
