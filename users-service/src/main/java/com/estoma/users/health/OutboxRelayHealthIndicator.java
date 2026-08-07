package com.estoma.users.health;

import com.estoma.users.persistence.JdbcOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes to the {@code readiness} health group: DOWN when the relay
 * looks genuinely stalled, not merely mid-poll (docs/system-contract.md
 * §8, §11).
 *
 * <p>Measures overdue-ness, not age since creation: a row created an hour
 * ago that just finished a legitimate 15-minute backoff must not look
 * identical to a row the relay has actually stopped processing. Two
 * independent signals, either one enough to go DOWN:
 *
 * <ul>
 *   <li>a PENDING row has been reclaimable ({@code available_at <= now})
 *       for longer than the stall threshold;
 *   <li>a PROCESSING row's lease expired more than the stall threshold
 *       ago (the sweeper runs every 30s, so a brief expiry is normal, not
 *       a stall).
 * </ul>
 *
 * <p>The momentary existence of one overdue row must not flap readiness
 * — both signals require the overdue/expired age itself to exceed the
 * threshold, not just existence.
 */
@Component
class OutboxRelayHealthIndicator implements HealthIndicator {

  private static final Duration STALL_THRESHOLD = Duration.ofMinutes(5);

  private final JdbcOutboxRepository outboxRepository;
  private final Clock clock;

  OutboxRelayHealthIndicator(JdbcOutboxRepository outboxRepository, Clock clock) {
    this.outboxRepository = outboxRepository;
    this.clock = clock;
  }

  @Override
  public Health health() {
    Instant now = clock.instant();

    Instant oldestOverduePending = outboxRepository.oldestOverduePendingAvailableAt();
    Duration overduePendingAge =
        oldestOverduePending == null ? Duration.ZERO : Duration.between(oldestOverduePending, now);

    Instant oldestExpiredLease = outboxRepository.oldestExpiredLeaseUntil();
    Duration expiredLeaseAge = oldestExpiredLease == null ? Duration.ZERO : Duration.between(oldestExpiredLease, now);

    boolean stalled =
        overduePendingAge.compareTo(STALL_THRESHOLD) > 0 || expiredLeaseAge.compareTo(STALL_THRESHOLD) > 0;

    Health.Builder builder = stalled ? Health.down() : Health.up();
    return builder
        .withDetail("overduePendingAgeSeconds", overduePendingAge.toSeconds())
        .withDetail("expiredLeaseAgeSeconds", expiredLeaseAge.toSeconds())
        .build();
  }
}
