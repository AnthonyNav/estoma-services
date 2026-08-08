package com.estoma.users.health;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes to the {@code readiness} health group: DOWN if the query
 * consumer container isn't running. Mirrors {@link
 * CommandConsumerHealthIndicator} — a pod that cannot consume {@code
 * ResolvePersonByInstitutionalEmail} cannot fulfil that part of its
 * function either (docs/system-contract.md §11).
 */
@Component
class QueryConsumerHealthIndicator implements HealthIndicator {

  static final String LISTENER_ID = "queryListener";

  private final RabbitListenerEndpointRegistry registry;

  QueryConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Health health() {
    var container = registry.getListenerContainer(LISTENER_ID);
    if (container == null) {
      return Health.down().withDetail("reason", "query listener container not found").build();
    }
    if (!container.isRunning()) {
      return Health.down().withDetail("reason", "query listener container is not running").build();
    }
    return Health.up().build();
  }
}
