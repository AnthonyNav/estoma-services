package com.estoma.communications.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class CommandConsumerHealthIndicator implements HealthIndicator {
  private final RabbitListenerEndpointRegistry registry;
  private final boolean listenersEnabled;

  CommandConsumerHealthIndicator(
      RabbitListenerEndpointRegistry registry,
      @Value("${estoma.messaging.listeners-enabled:true}") boolean listenersEnabled) {
    this.registry = registry;
    this.listenersEnabled = listenersEnabled;
  }

  @Override
  public Health health() {
    if (!listenersEnabled) return Health.up().withDetail("disabled", true).build();
    var commands = registry.getListenerContainer("commandConsumer");
    var events = registry.getListenerContainer("eventConsumer");
    boolean running = commands != null && commands.isRunning() && events != null && events.isRunning();
    return running ? Health.up().build() : Health.down().withDetail("consumersRunning", false).build();
  }
}
