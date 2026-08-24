package com.estoma.communications.infrastructure.messaging;

import com.estoma.communications.infrastructure.persistence.JdbcOutboxRelayRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelayHealthIndicator implements HealthIndicator {

  private final JdbcOutboxRelayRepository repository;

  OutboxRelayHealthIndicator(JdbcOutboxRelayRepository repository) {
    this.repository = repository;
  }

  @Override
  public Health health() {
    long failed = repository.failedCount();
    long pending = repository.pendingCount();
    if (failed > 0) {
      return Health.down().withDetail("failed", failed).withDetail("pending", pending).build();
    }
    return Health.up().withDetail("pending", pending).build();
  }
}
