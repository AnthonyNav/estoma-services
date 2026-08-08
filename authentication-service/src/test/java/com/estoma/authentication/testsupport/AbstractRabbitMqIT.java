package com.estoma.authentication.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Base class for integration tests that need both a real PostgreSQL
 * database (inherited from {@link AbstractPostgresIT}) and a real
 * RabbitMQ broker -- command/event/RPC round-trip tests, the Outbox
 * Relay.
 *
 * <p>The broker container is started once in a static initializer and
 * shared by every subclass in the same JVM, same pattern as {@code
 * POSTGRES} in the parent class. Topology is self-declared against this
 * plain broker: {@code application-test.yml} (via the parent's {@code
 * @ActiveProfiles("test")}) turns {@code topology-declare} off by
 * default for a context with no broker, and this class turns it back on
 * now that a real one is actually present.
 */
public abstract class AbstractRabbitMqIT extends AbstractPostgresIT {

  protected static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  static {
    RABBITMQ.start();
  }

  @DynamicPropertySource
  static void registerRabbitMqProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    registry.add("estoma.messaging.topology-declare", () -> "true");
  }
}
