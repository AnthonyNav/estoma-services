package com.estoma.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.estoma.users.application.command.CreatePersonCommand;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.testsupport.AbstractMessagingIT;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The full happy path, end to end: a real {@code CreatePerson} envelope
 * published to {@code platform.commands}, consumed by {@code
 * CommandListener}, dispatched, persisted, and relayed back out as a
 * {@code CreatePersonSucceeded} result and a {@code PersonCreated} event
 * — proving the envelope contract along the way (docs/system-contract.md
 * §2, §7).
 */
class CreatePersonCommandIT extends AbstractMessagingIT {

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private AmqpAdmin amqpAdmin;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private TopicExchange eventsExchange;

  @Test
  void happyPathProducesACorrelatedResultAndTheDerivedEvent() {
    UUID personId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID commandMessageId = UUID.randomUUID();

    Queue resultsQueue = amqpAdmin.declareQueue();
    amqpAdmin.declareBinding(BindingBuilder.bind(resultsQueue).to(new org.springframework.amqp.core.DirectExchange(AmqpNames.EXCHANGE_RESULTS)).with("bff"));
    Queue eventsQueue = amqpAdmin.declareQueue();
    Binding eventsBinding = BindingBuilder.bind(eventsQueue).to(eventsExchange).with("identity.person.*");
    amqpAdmin.declareBinding(eventsBinding);

    try {
      publishCreatePersonCommand(personId, correlationId, operationId, commandMessageId);

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                Message resultMessage = rabbitTemplate.receive(resultsQueue.getName());
                assertThat(resultMessage).isNotNull();

                MessageEnvelope result = codec.decode(resultMessage.getBody());
                assertThat(result.messageType()).isEqualTo("CreatePersonSucceeded");
                assertThat(result.messageKind()).isEqualTo(MessageKind.RESULT);
                assertThat(result.producer()).isEqualTo("users-service");
                assertThat(result.correlationId()).isEqualTo(correlationId);
                assertThat(result.causationId()).isEqualTo(commandMessageId);
                assertThat(result.operationId()).isEqualTo(operationId);
                assertThat(result.aggregateVersion()).isEqualTo(1);
              });

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                Message eventMessage = rabbitTemplate.receive(eventsQueue.getName());
                assertThat(eventMessage).isNotNull();

                MessageEnvelope event = codec.decode(eventMessage.getBody());
                assertThat(event.messageType()).isEqualTo("PersonCreated");
                assertThat(event.messageKind()).isEqualTo(MessageKind.EVENT);
                assertThat(event.correlationId()).isEqualTo(correlationId);
                assertThat(event.causationId()).isEqualTo(commandMessageId);
                // No operationId on events (docs/system-contract.md §10 D6).
                assertThat(event.operationId()).isNull();
                assertThat(event.aggregateVersion()).isEqualTo(1);
                assertThat(event.aggregateId()).isEqualTo(personId);
              });
    } finally {
      amqpAdmin.deleteQueue(resultsQueue.getName());
      amqpAdmin.deleteQueue(eventsQueue.getName());
    }
  }

  private void publishCreatePersonCommand(UUID personId, UUID correlationId, UUID operationId, UUID messageId) {
    CreatePersonCommand command =
        new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada." + personId + "@example.edu", null);
    MessageEnvelope envelope =
        new MessageEnvelope(
            messageId,
            MessageTypes.CREATE_PERSON,
            1,
            MessageKind.COMMAND,
            "bff",
            Instant.now(),
            correlationId,
            null,
            operationId,
            UUID.randomUUID().toString(),
            personId,
            null,
            new Actor.Account(UUID.randomUUID()),
            codec.toJsonNode(command));

    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    Message message = new Message(codec.encode(envelope), props);
    rabbitTemplate.send(AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, message);
  }
}
