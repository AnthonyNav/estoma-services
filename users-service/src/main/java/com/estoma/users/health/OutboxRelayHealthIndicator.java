package com.estoma.users.health;

import com.estoma.users.persistence.JdbcOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes to the {@code readiness} health group: DOWN if the oldest
 * PENDING outbox row has been waiting longer than a relay could
 * plausibly explain — a signal the relay has stalled, not merely that
 * it's mid-poll (docs/system-contract.md §8, §11).
 */
@Component
class OutboxRelayHealthIndicator implements HealthIndicator {

  private static final Duration STALL_THRESHOLD = Duration.ofMinutes(5);

  private final JdbcOutboxRepository outboxRepository;

  OutboxRelayHealthIndicator(JdbcOutboxRepository outboxRepository) {
    this.outboxRepository = outboxRepository;
  }

  @Override
  public Health health() {
    Instant oldestPending = outboxRepository.oldestPendingOccurredAt();
    if (oldestPending == null) {
      return Health.up().build();
    }
    Duration age = Duration.between(oldestPending, Instant.now());
    Health.Builder builder =
        age.compareTo(STALL_THRESHOLD) > 0 ? Health.down() : Health.up();
    return builder.withDetail("oldestPendingAgeSeconds", age.toSeconds()).build();
  }
}
