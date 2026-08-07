package com.estoma.users.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 */
@Testcontainers
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
}
