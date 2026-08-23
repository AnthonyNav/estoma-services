package com.estoma.appointments.infrastructure.messaging;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("rabbitMessaging")
public final class RabbitMessagingHealthIndicator implements HealthIndicator {
  private final ConnectionFactory connectionFactory;
  public RabbitMessagingHealthIndicator(ConnectionFactory connectionFactory) { this.connectionFactory = connectionFactory; }
  @Override public Health health() {
    try (var connection = connectionFactory.createConnection(); var channel = connection.createChannel(false)) {
      return Health.up().withDetail("topology", "connection available").build();
    } catch (Exception exception) {
      return Health.down(exception).withDetail("topology", "unavailable").build();
    }
  }
}
