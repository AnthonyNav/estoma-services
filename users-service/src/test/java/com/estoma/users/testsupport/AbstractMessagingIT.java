package com.estoma.users.testsupport;

import com.estoma.users.messaging.AmqpNames;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Base class for integration tests that need both PostgreSQL and a real
 * RabbitMQ broker. Extends {@link AbstractPostgresIT} rather than
 * duplicating its container, so a test needing both only pays for two
 * containers, not three.
 *
 * <p>The RabbitMQ container is static, like the Postgres one in the
 * parent class — shared across every {@code *IT} extending this base in
 * the same JVM.
 *
 * <p>The inline properties deliberately override the PostgreSQL test
 * profile, so messaging contexts opt into RabbitMQ without relying on
 * profile precedence.
 */
@Testcontainers
@TestPropertySource(
    properties = {
      "estoma.messaging.topology-declare=true",
      "estoma.messaging.listeners-enabled=true",
      "management.health.rabbit.enabled=true",
      "spring.rabbitmq.dynamic=true",
      "spring.rabbitmq.publisher-confirm-type=correlated",
      "spring.rabbitmq.publisher-returns=true"
    })
public abstract class AbstractMessagingIT extends AbstractPostgresIT {

  protected static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  static {
    RABBITMQ.start();
  }

  @DynamicPropertySource
  static void registerRabbitMqProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    registry.add("spring.rabbitmq.username", () -> "guest");
    registry.add("spring.rabbitmq.password", () -> "guest");
  }

  @BeforeEach
  void clearBrokerQueues() {
    List.of(
            AmqpNames.QUEUE_COMMANDS,
            AmqpNames.QUEUE_COMMANDS_DLQ,
            AmqpNames.QUEUE_RETRY_5S,
            AmqpNames.QUEUE_RETRY_30S,
            AmqpNames.QUEUE_RETRY_300S,
            AmqpNames.QUEUE_QUERIES,
            AmqpNames.QUEUE_QUERIES_DLQ,
            AmqpNames.QUEUE_QUERIES_RETRY_5S,
            AmqpNames.QUEUE_QUERIES_RETRY_30S,
            AmqpNames.QUEUE_QUERIES_RETRY_300S)
        .forEach(amqpAdmin::purgeQueue);
  }

  @Autowired private AmqpAdmin amqpAdmin;
}
