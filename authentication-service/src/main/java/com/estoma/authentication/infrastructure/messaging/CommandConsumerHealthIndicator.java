package com.estoma.authentication.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes to the {@code readiness} health group: DOWN if the command
 * listener container isn't running. A pod that cannot consume commands
 * cannot fulfil its function and must be removed from traffic.
 *
 * <p>Bean name {@code commandConsumerHealthIndicator} is what makes
 * Spring register this contributor under the id {@code commandConsumer}
 * referenced in {@code application.yml}'s readiness group.
 */
@Component
class CommandConsumerHealthIndicator implements HealthIndicator {

  static final String LISTENER_ID = "commandListener";

  private final RabbitListenerEndpointRegistry registry;

  CommandConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Health health() {
    var container = registry.getListenerContainer(LISTENER_ID);
    if (container == null) {
      return Health.down().withDetail("reason", "command listener container not found").build();
    }
    if (!container.isRunning()) {
      return Health.down().withDetail("reason", "command listener container is not running").build();
    }
    return Health.up().build();
  }
}
