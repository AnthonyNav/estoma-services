package com.estoma.authentication.infrastructure.messaging;

import com.estoma.authentication.infrastructure.persistence.JdbcOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes to the {@code readiness} health group: DOWN when the relay
 * looks genuinely stalled, not merely mid-poll or mid-backoff.
 *
 * <p>Measures overdue-ness, not age since creation: a row created an hour
 * ago that just finished a legitimate 5-minute backoff must not look
 * identical to a row the relay has actually stopped processing. Only DOWN
 * once the oldest overdue-PENDING row has been overdue for longer than
 * the stall threshold -- the momentary existence of one overdue row must
 * not flap readiness.
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
    Instant oldestOverdue = outboxRepository.oldestOverduePendingAvailableAt();
    Duration overdueAge = oldestOverdue == null ? Duration.ZERO : Duration.between(oldestOverdue, Instant.now());

    Health.Builder builder = overdueAge.compareTo(STALL_THRESHOLD) > 0 ? Health.down() : Health.up();
    return builder.withDetail("overdueAgeSeconds", overdueAge.toSeconds()).build();
  }
}
