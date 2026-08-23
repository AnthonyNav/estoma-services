package com.estoma.communications.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("commandConsumer")
public class CommandConsumerHealthIndicator implements HealthIndicator {

  private final RabbitListenerEndpointRegistry registry;

  CommandConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Health health() {
    var commands = registry.getListenerContainer("commandConsumer");
    var events = registry.getListenerContainer("eventConsumer");
    boolean running = commands != null && commands.isRunning() && events != null && events.isRunning();
    return running ? Health.up().build() : Health.down().withDetail("consumersRunning", false).build();
  }
}
