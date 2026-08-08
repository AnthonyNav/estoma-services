package com.estoma.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.estoma.users.application.command.ActivatePersonCommand;
import com.estoma.users.application.command.CreatePersonCommand;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.result.ResultPayload;
import com.estoma.users.contract.result.ResultStatus;
import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.testsupport.AbstractMessagingIT;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A functional rejection must be durable, correlated, and MUST NOT reach
 * the DLQ or any retry tier (commands.md:285, docs/system-contract.md §7)
 * — the dispatcher catches {@code PersonRejectedException} inside its own
 * transaction and commits a {@code Rejected} result instead of letting it
 * unwind.
 */
class FunctionalRejectionIT extends AbstractMessagingIT {

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private AmqpAdmin amqpAdmin;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private JdbcClient jdbc;

  @Test
  void activatingAnAlreadyActivePersonIsRejectedWithoutReachingTheDlqOrAnyRetryTier() throws InterruptedException {
    UUID personId = UUID.randomUUID();
    Queue resultsQueue = amqpAdmin.declareQueue();
    amqpAdmin.declareBinding(
        BindingBuilder.bind(resultsQueue).to(new DirectExchange(AmqpNames.EXCHANGE_RESULTS)).with("bff"));

    try {
      createPerson(personId); // version 1, ACTIVE by construction
      // drain the CreatePersonSucceeded result before sending the command under test
      await().atMost(Duration.ofSeconds(10)).until(() -> rabbitTemplate.receive(resultsQueue.getName()) != null);

      UUID activateCorrelationId = UUID.randomUUID();
      activatePerson(personId, 1, activateCorrelationId); // already ACTIVE -> rejection expected

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                Message resultMessage = rabbitTemplate.receive(resultsQueue.getName());
                assertThat(resultMessage).isNotNull();
                MessageEnvelope result = codec.decode(resultMessage.getBody());
                assertThat(result.messageType()).isEqualTo("ActivatePersonRejected");
                assertThat(result.correlationId()).isEqualTo(activateCorrelationId);
                ResultPayload payload = codec.convertPayload(result, ResultPayload.class);
                assertThat(payload.status()).isEqualTo(ResultStatus.REJECTED);
                assertThat(payload.error().code()).isEqualTo("PERSON_ALREADY_ACTIVE");
              });

      // Give any wrongly-triggered retry/DLQ routing time to happen, then
      // assert none of it did.
      Thread.sleep(1_500);
      assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ)).isNull();
      assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_RETRY_5S)).isNull();
      assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_RETRY_30S)).isNull();
      assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_RETRY_300S)).isNull();
    } finally {
      amqpAdmin.deleteQueue(resultsQueue.getName());
    }
  }

  @Test
  void malformedCreateEmailIsPersistedAndReplayedWithoutRevalidatingTheDomain() {
    Queue resultsQueue = amqpAdmin.declareQueue();
    amqpAdmin.declareBinding(
        BindingBuilder.bind(resultsQueue).to(new DirectExchange(AmqpNames.EXCHANGE_RESULTS)).with("bff"));

    try {
      UUID personId = UUID.randomUUID();
      String idempotencyKey = UUID.randomUUID().toString();
      CreatePersonCommand malformed =
          new CreatePersonCommand(personId, "Ada", "Lovelace", null, "not-an-email", null);

      UUID firstCorrelationId = UUID.randomUUID();
      send(MessageTypes.CREATE_PERSON, idempotencyKey, firstCorrelationId, codec.toJsonNode(malformed));
      assertRejectedResult(resultsQueue, firstCorrelationId, "CreatePersonRejected", "INVALID_PERSON_DATA");

      assertThat(
              jdbc.sql(
                      "SELECT terminal_status FROM idempotency_records "
                          + "WHERE producer = :producer AND idempotency_key = :idempotencyKey")
                  .param("producer", "bff")
                  .param("idempotencyKey", idempotencyKey)
                  .query(String.class)
                  .single())
          .isEqualTo("REJECTED");
      assertThat(
              jdbc.sql(
                      "SELECT error_code FROM idempotency_records "
                          + "WHERE producer = :producer AND idempotency_key = :idempotencyKey")
                  .param("producer", "bff")
                  .param("idempotencyKey", idempotencyKey)
                  .query(String.class)
                  .single())
          .isEqualTo("INVALID_PERSON_DATA");

      UUID retryCorrelationId = UUID.randomUUID();
      send(MessageTypes.CREATE_PERSON, idempotencyKey, retryCorrelationId, codec.toJsonNode(malformed));
      assertRejectedResult(resultsQueue, retryCorrelationId, "CreatePersonRejected", "INVALID_PERSON_DATA");

      CreatePersonCommand corrected =
          new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada." + personId + "@example.edu", null);
      UUID correctedCorrelationId = UUID.randomUUID();
      send(MessageTypes.CREATE_PERSON, idempotencyKey, correctedCorrelationId, codec.toJsonNode(corrected));
      assertRejectedResult(resultsQueue, correctedCorrelationId, "CreatePersonRejected", "INVALID_MESSAGE");
    } finally {
      amqpAdmin.deleteQueue(resultsQueue.getName());
    }
  }

  private void createPerson(UUID personId) {
    CreatePersonCommand command =
        new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada." + personId + "@example.edu", null);
    send(MessageTypes.CREATE_PERSON, UUID.randomUUID().toString(), UUID.randomUUID(), codec.toJsonNode(command));
  }

  private void activatePerson(UUID personId, int expectedVersion, UUID correlationId) {
    ActivatePersonCommand command = new ActivatePersonCommand(personId, expectedVersion);
    send(MessageTypes.ACTIVATE_PERSON, UUID.randomUUID().toString(), correlationId, codec.toJsonNode(command));
  }

  private void send(
      String messageType, String idempotencyKey, UUID correlationId, tools.jackson.databind.JsonNode payload) {
    UUID aggregateId = UUID.fromString(payload.path("personId").asString());
    MessageEnvelope envelope =
        new MessageEnvelope(
            UUID.randomUUID(),
            messageType,
            1,
            MessageKind.COMMAND,
            "bff",
            Instant.now(),
            correlationId,
            null,
            UUID.randomUUID(),
            idempotencyKey,
            aggregateId,
            null,
            new Actor.Account(UUID.randomUUID()),
            payload);
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    rabbitTemplate.send(
        AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, new Message(codec.encode(envelope), props));
  }

  private void assertRejectedResult(Queue queue, UUID correlationId, String messageType, String errorCode) {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              Message resultMessage = rabbitTemplate.receive(queue.getName());
              assertThat(resultMessage).isNotNull();
              MessageEnvelope result = codec.decode(resultMessage.getBody());
              assertThat(result.messageType()).isEqualTo(messageType);
              assertThat(result.correlationId()).isEqualTo(correlationId);
              ResultPayload payload = codec.convertPayload(result, ResultPayload.class);
              assertThat(payload.status()).isEqualTo(ResultStatus.REJECTED);
              assertThat(payload.error().code()).isEqualTo(errorCode);
            });
  }
}
