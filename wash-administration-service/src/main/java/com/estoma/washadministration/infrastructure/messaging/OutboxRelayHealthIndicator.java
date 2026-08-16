package com.estoma.washadministration.infrastructure.messaging;

import com.estoma.washadministration.application.port.OutboxRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("outboxRelay")
public class OutboxRelayHealthIndicator implements HealthIndicator {

  private final OutboxRepository outbox;

  public OutboxRelayHealthIndicator(OutboxRepository outbox) {
    this.outbox = outbox;
  }

  @Override
  public Health health() {
    try {
      if (outbox.hasFailedMessages()) {
        return Health.down().withDetail("reason", "outbox contains failed messages").build();
      }
      if (outbox.hasExpiredProcessing()) {
        return Health.down().withDetail("reason", "outbox contains expired claims").build();
      }
      Instant oldest = outbox.oldestOverduePendingAvailableAt();
      if (oldest != null && Duration.between(oldest, Instant.now()).toMinutes() >= 5) {
        return Health.down().withDetail("oldestOverduePending", oldest).build();
      }
      return Health.up().build();
    } catch (RuntimeException exception) {
      return Health.down(exception).build();
    }
  }
}
