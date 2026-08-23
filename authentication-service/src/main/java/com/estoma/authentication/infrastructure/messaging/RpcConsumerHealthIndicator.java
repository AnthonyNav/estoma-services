package com.estoma.authentication.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness contributor for the ephemeral Security RPC consumer. */
@Component
class RpcConsumerHealthIndicator implements HealthIndicator {
  static final String LISTENER_ID = "rpcListener";

  private final RabbitListenerEndpointRegistry registry;

  RpcConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Health health() {
    var container = registry.getListenerContainer(LISTENER_ID);
    if (container == null) {
      return Health.down().withDetail("reason", "security RPC listener container not found").build();
    }
    if (!container.isRunning()) {
      return Health.down().withDetail("reason", "security RPC listener container is not running").build();
    }
    return Health.up().build();
  }
}
