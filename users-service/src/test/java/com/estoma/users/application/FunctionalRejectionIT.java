package com.estoma.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.estoma.users.application.command.ActivatePersonCommand;
import com.estoma.users.application.command.CreatePersonCommand;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.result.RejectedPayload;
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
                RejectedPayload payload = codec.convertPayload(result, RejectedPayload.class);
                assertThat(payload.errorCode()).isEqualTo("PERSON_ALREADY_ACTIVE");
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

  private void createPerson(UUID personId) {
    CreatePersonCommand command =
        new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada." + personId + "@example.edu", null);
    send(MessageTypes.CREATE_PERSON, "users-service", UUID.randomUUID(), codec.toJsonNode(command));
  }

  private void activatePerson(UUID personId, int expectedVersion, UUID correlationId) {
    ActivatePersonCommand command = new ActivatePersonCommand(personId, expectedVersion);
    send(MessageTypes.ACTIVATE_PERSON, "users-service", correlationId, codec.toJsonNode(command));
  }

  private void send(String messageType, String targetService, UUID correlationId, tools.jackson.databind.JsonNode payload) {
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
            targetService,
            null,
            null,
            new Actor(UUID.randomUUID()),
            payload);
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    rabbitTemplate.send(
        AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, new Message(codec.encode(envelope), props));
  }
}
