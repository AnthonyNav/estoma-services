package com.estoma.authentication.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness contributor for the canonical queries.authentication consumer. */
@Component
class QueryConsumerHealthIndicator implements HealthIndicator {

  static final String LISTENER_ID = "queryConsumer";

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
