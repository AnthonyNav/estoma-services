package com.estoma.authorization.infrastructure.messaging;

import com.estoma.authorization.application.port.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("outboxRelay")
final class OutboxRelayHealthIndicator implements HealthIndicator {

  private final OutboxRepository outbox;
  private final Clock clock;

  OutboxRelayHealthIndicator(OutboxRepository outbox, Clock authorizationClock) {
    this.outbox = outbox;
    this.clock = authorizationClock;
  }

  @Override
  public Health health() {
    try {
      Instant now = clock.instant();
      if (outbox.hasFailedMessages()) {
        return Health.down().withDetail("reason", "outbox contains failed messages").build();
      }
      if (outbox.hasExpiredProcessing(now)) {
        return Health.down().withDetail("reason", "outbox contains expired claims").build();
      }
      Instant oldest = outbox.oldestPendingAvailableAt();
      if (oldest != null && Duration.between(oldest, now).toMinutes() >= 5) {
        return Health.down().withDetail("oldestOverduePending", oldest).build();
      }
      return Health.up().build();
    } catch (RuntimeException exception) {
      return Health.down(exception).build();
    }
  }
}
