package com.estoma.washexecution.testsupport;

import com.estoma.washexecution.contracts.MessagingNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractMessagingIT {
  protected static final String BFF_RESULT_QUEUE = "test.wash-execution.results.platform-bff";
  protected static final String CANDIDATE_BFF_RESULT_QUEUE = "test.wash-execution.results.bff";
  protected static final String APPOINTMENTS_RESULT_QUEUE = "test.wash-execution.results.appointments";
  protected static final String UNAUTHORIZED_RESULT_QUEUE = "test.wash-execution.results.unauthorized";
  protected static final String EVENT_TAP_QUEUE = "test.wash-execution.events.tap";
  protected static final String APPOINTMENTS_QUERY_QUEUE = "test.wash-execution.queries.appointments";
  protected static final String ADMIN_QUERY_QUEUE = "test.wash-execution.queries.wash-administration";
  protected static final String AUTH_QUERY_QUEUE = "test.wash-execution.queries.authorization";

  @Container
  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("wash_execution_db")
          .withUsername("wash_execution")
          .withPassword("wash_execution");

  @Container
  protected static final RabbitMQContainer RABBITMQ =
      new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected RabbitTemplate rabbit;
  @Autowired protected AmqpAdmin amqp;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
    registry.add("wash-execution.cleaning-worker-enabled", () -> "false");
    registry.add("estoma.messaging.outbox-relay-enabled", () -> "true");
    registry.add("estoma.messaging.outbox-poll-interval", () -> "600000");
    registry.add("estoma.messaging.outbox-initial-delay", () -> "600000");
    registry.add("estoma.messaging.deferred-replay-enabled", () -> "true");
    registry.add("estoma.messaging.deferred-replay-poll-interval", () -> "600000");
    registry.add("estoma.messaging.deferred-replay-initial-delay", () -> "600000");
    registry.add("estoma.messaging.recovery-worker-enabled", () -> "false");
    registry.add("estoma.messaging.recovery-initial-delay", () -> "600000");
  }

  @BeforeEach
  void resetInfrastructure() {
    jdbc.execute("""
        TRUNCATE TABLE
          resource_unavailability_affected_executions,
          cleaning_periods,
          resource_unavailabilities,
          resource_assignments,
          entry_validations,
          wash_executions,
          appointment_projection,
          appointment_time_slot_projection,
          administrative_closure_projection,
          resource_priority_projection,
          tank_projection,
          cabin_projection,
          authorization_access_projection,
          authorization_role_projection,
          projection_recovery_state,
          projection_recovery_jobs,
          command_idempotency_records,
          processed_messages,
          outbox_messages
        CASCADE
        """);

    declareTap(BFF_RESULT_QUEUE, MessagingNames.RESULTS_EXCHANGE, "platform-bff");
    declareTap(CANDIDATE_BFF_RESULT_QUEUE, MessagingNames.RESULTS_EXCHANGE, "bff");
    declareTap(APPOINTMENTS_RESULT_QUEUE, MessagingNames.RESULTS_EXCHANGE, "appointments");
    declareTap(UNAUTHORIZED_RESULT_QUEUE, MessagingNames.RESULTS_EXCHANGE, "unknown-requester");
    declareTap(EVENT_TAP_QUEUE, MessagingNames.EVENTS_EXCHANGE, "wash-execution.#");
    declareTap(APPOINTMENTS_QUERY_QUEUE, MessagingNames.QUERIES_EXCHANGE, "appointments");
    declareTap(ADMIN_QUERY_QUEUE, MessagingNames.QUERIES_EXCHANGE, "wash-administration");
    declareTap(AUTH_QUERY_QUEUE, MessagingNames.QUERIES_EXCHANGE, "authorization");

    purge(BFF_RESULT_QUEUE);
    purge(CANDIDATE_BFF_RESULT_QUEUE);
    purge(APPOINTMENTS_RESULT_QUEUE);
    purge(UNAUTHORIZED_RESULT_QUEUE);
    purge(EVENT_TAP_QUEUE);
    purge(APPOINTMENTS_QUERY_QUEUE);
    purge(ADMIN_QUERY_QUEUE);
    purge(AUTH_QUERY_QUEUE);
    purge(MessagingNames.COMMAND_QUEUE);
    purge(MessagingNames.COMMAND_RETRY_1);
    purge(MessagingNames.COMMAND_RETRY_2);
    purge(MessagingNames.COMMAND_DLQ);
    purge(MessagingNames.EVENT_QUEUE);
    purge(MessagingNames.EVENT_RETRY_1);
    purge(MessagingNames.EVENT_RETRY_2);
    purge(MessagingNames.EVENT_DLQ);
    purge(MessagingNames.RESULT_QUEUE);
    purge(MessagingNames.RESULT_RETRY_1);
    purge(MessagingNames.RESULT_RETRY_2);
    purge(MessagingNames.RESULT_DLQ);
    purge(MessagingNames.QUERY_QUEUE);
    purge(MessagingNames.QUERY_RETRY_1);
    purge(MessagingNames.QUERY_RETRY_2);
    purge(MessagingNames.QUERY_DLQ);
  }

  protected void declareTap(String queueName, String exchange, String routingKey) {
    amqp.declareQueue(new Queue(queueName, false, false, false));
    amqp.declareBinding(new Binding(
        queueName, DestinationType.QUEUE, exchange, routingKey, null));
  }

  protected void purge(String queueName) {
    try {
      amqp.purgeQueue(queueName, false);
    } catch (RuntimeException ignored) {
      // Queue may not have been declared by a disabled topology in a focused test.
    }
  }
}
