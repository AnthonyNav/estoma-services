package com.estoma.platformbff.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.platformbff.domain.messaging.OutboxMessage;
import com.estoma.platformbff.domain.messaging.OutboxStatus;
import com.estoma.platformbff.contracts.messaging.EventEnvelope;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.infrastructure.config.MessagingTopology;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "platform.outbox.enabled=false",
    "platform.messaging.listeners-enabled=false",
    "platform.messaging.topology-declare=true"
})
class BffRabbitMessagingIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("bff_db")
      .withUsername("bff")
      .withPassword("bff");

  @Container
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.rabbitmq.addresses", RABBIT::getAmqpUrl);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
  }

  @Autowired AmqpAdmin admin;
  @Autowired RabbitTemplate rabbit;
  @Autowired MessageEnvelopeCodec codec;
  @Autowired DefaultConfirmedCommandPublisher publisher;

  @Test
  void declaresOnlyBffOwnedResultsQueue() {
    assertThat(admin.getQueueInfo(MessagingTopology.RESULTS_QUEUE)).isNotNull();
    assertThat(admin.getQueueInfo(MessagingTopology.PROJECTION_EVENTS_QUEUE)).isNotNull();
    assertThat(admin.getQueueInfo("commands.users")).isNull();
    assertThat(admin.getQueueInfo("commands.authentication")).isNull();
  }

  @Test
  void routesDifferentOwnerEventsToTheSingleProjectionQueue() {
    UUID executionId = UUID.randomUUID();
    var event = new EventEnvelope(
        UUID.randomUUID(), "WashExecutionStarted", 1, MessageKind.EVENT, "wash-execution",
        Instant.now(), UUID.randomUUID(), UUID.randomUUID(), null, null, executionId, 7,
        com.estoma.platformbff.contracts.messaging.ActorContext.anonymous(),
        codec.readTree("{\"washExecutionId\":\"" + executionId + "\",\"executionStatus\":\"STARTED\"}"));
    var properties = new org.springframework.amqp.core.MessageProperties();
    properties.setContentType(org.springframework.amqp.core.MessageProperties.CONTENT_TYPE_JSON);
    properties.setMessageId(event.messageId().toString());
    rabbit.send(MessagingTopology.EVENTS_EXCHANGE, "wash-execution.execution.started",
        new Message(codec.write(event).getBytes(java.nio.charset.StandardCharsets.UTF_8), properties));

    Message received = rabbit.receive(MessagingTopology.PROJECTION_EVENTS_QUEUE, 5_000);
    assertThat(received).isNotNull();
    assertThat(codec.readEvent(received.getBody()).messageType()).isEqualTo("WashExecutionStarted");
  }

  @Test
  void publisherConfirmsAndPreservesAggregateIdentityAndActorWireShape() {
    String queueName = "test.commands.users." + UUID.randomUUID();
    admin.declareQueue(new Queue(queueName, false, true, true));
    admin.declareBinding(new Binding(
        queueName, Binding.DestinationType.QUEUE,
        MessagingTopology.COMMANDS_EXCHANGE, "users", null));

    UUID accountId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    Instant now = Instant.now();
    String actor = codec.write(com.estoma.platformbff.contracts.messaging.ActorContext.account(
        accountId, "GLOBAL", null));
    var outboxMessage = new OutboxMessage(
        messageId, "COMMAND", "CreatePerson", 1,
        MessagingTopology.COMMANDS_EXCHANGE, "users", UUID.randomUUID(), UUID.randomUUID(), null,
        aggregateId, 4, "key", actor, "{\"firstName\":\"Ada\"}", OutboxStatus.PENDING,
        null, null, 0, null, null, null, now, now);

    publisher.publish(outboxMessage);

    Message published = rabbit.receive(queueName, 5_000);
    assertThat(published).isNotNull();
    var envelope = codec.readCommand(published.getBody());
    assertThat(envelope.messageId()).isEqualTo(messageId);
    assertThat(envelope.aggregateId()).isEqualTo(aggregateId);
    assertThat(envelope.aggregateVersion()).isEqualTo(4);
    assertThat(envelope.actor().accountId()).isEqualTo(accountId);
  }

  @Test
  void unroutableCommandFailsAfterPublisherReturn() {
    Instant now = Instant.now();
    var outboxMessage = new OutboxMessage(
        UUID.randomUUID(), "COMMAND", "CreatePerson", 1,
        MessagingTopology.COMMANDS_EXCHANGE, "missing-route-" + UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), null, null, null, "key", "{}", "{}",
        OutboxStatus.PENDING, null, null, 0, null, null, null, now, now);

    assertThatThrownBy(() -> publisher.publish(outboxMessage))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unroutable");
  }
}
