package com.estoma.authorization.testsupport;

import com.estoma.authorization.contracts.MessagingNames;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** PostgreSQL plus a real RabbitMQ broker for AMQP integration tests. */
@Testcontainers
@TestPropertySource(
    properties = {
      "estoma.messaging.topology-declare=true",
      "estoma.messaging.listeners-enabled=true",
      "estoma.messaging.outbox-relay-enabled=false",
      "estoma.messaging.recovery-worker-enabled=false",
      "management.health.rabbit.enabled=true",
      "spring.rabbitmq.listener.simple.auto-startup=true",
      "spring.rabbitmq.publisher-confirm-type=correlated",
      "spring.rabbitmq.publisher-returns=true"
    })
public abstract class AbstractMessagingIT extends AbstractPostgresIT {

  protected static final RabbitMQContainer RABBITMQ =
      new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  @Autowired protected AmqpAdmin amqpAdmin;

  static {
    RABBITMQ.start();
  }

  @DynamicPropertySource
  static void registerRabbitProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    registry.add("spring.rabbitmq.username", () -> "guest");
    registry.add("spring.rabbitmq.password", () -> "guest");
  }

  @BeforeEach
  void clearBrokerState() {
    List.of(
            MessagingNames.COMMAND_QUEUE,
            MessagingNames.COMMAND_RETRY_1,
            MessagingNames.COMMAND_RETRY_2,
            MessagingNames.COMMAND_DLQ,
            MessagingNames.QUERY_QUEUE,
            MessagingNames.QUERY_RETRY_1,
            MessagingNames.QUERY_RETRY_2,
            MessagingNames.QUERY_DLQ,
            MessagingNames.EVENT_QUEUE,
            MessagingNames.EVENT_RETRY_1,
            MessagingNames.EVENT_RETRY_2,
            MessagingNames.EVENT_DLQ,
            MessagingNames.RESULT_QUEUE,
            MessagingNames.RESULT_RETRY_1,
            MessagingNames.RESULT_RETRY_2,
            MessagingNames.RESULT_DLQ)
        .forEach(amqpAdmin::purgeQueue);
  }
}
