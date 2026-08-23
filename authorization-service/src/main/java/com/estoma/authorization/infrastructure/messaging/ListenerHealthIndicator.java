package com.estoma.authorization.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

abstract class ListenerHealthIndicator implements HealthIndicator {

  private final RabbitListenerEndpointRegistry registry;
  private final String listenerId;

  ListenerHealthIndicator(RabbitListenerEndpointRegistry registry, String listenerId) {
    this.registry = registry;
    this.listenerId = listenerId;
  }

  @Override
  public Health health() {
    var container = registry.getListenerContainer(listenerId);
    if (container == null) {
      return Health.down().withDetail("reason", "listener container not found").build();
    }
    return container.isRunning()
        ? Health.up().build()
        : Health.down().withDetail("reason", "listener container is not running").build();
  }
}
