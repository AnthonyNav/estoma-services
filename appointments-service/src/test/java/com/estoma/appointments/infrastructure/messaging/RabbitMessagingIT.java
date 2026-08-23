package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RabbitMessagingIT {
  @Container
  static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  @Test
  void brokerContainerIsAvailableForConfirmedMessagingScenarios() {
    assertThat(rabbit.isRunning()).isTrue();
    assertThat(rabbit.getHost()).isNotBlank();
    assertThat(rabbit.getAmqpPort()).isPositive();
  }
}
