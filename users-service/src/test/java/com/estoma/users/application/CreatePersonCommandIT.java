package com.estoma.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.estoma.users.application.command.CreatePersonCommand;
import com.estoma.users.application.command.UpdatePersonCommand;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.result.ResultPayload;
import com.estoma.users.contract.result.SucceededPayload;
import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.outbound.OutboxRelay;
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
  @Autowired private OutboxRelay relay;

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
                relay.relay();
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
                relay.relay();
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

  @Test
  void replayedCreateResultKeepsItsOriginalAggregateVersionAfterALaterUpdate() {
    UUID personId = UUID.randomUUID();
    UUID originalCorrelationId = UUID.randomUUID();
    UUID originalMessageId = UUID.randomUUID();
    String idempotencyKey = UUID.randomUUID().toString();
    Queue resultsQueue = amqpAdmin.declareQueue();
    amqpAdmin.declareBinding(
        BindingBuilder.bind(resultsQueue).to(new org.springframework.amqp.core.DirectExchange(AmqpNames.EXCHANGE_RESULTS)).with("bff"));

    try {
      publishCreatePersonCommand(
          personId, originalCorrelationId, UUID.randomUUID(), originalMessageId, idempotencyKey);
      MessageEnvelope originalResult = codec.decode(awaitMessage(resultsQueue, originalCorrelationId).getBody());
      assertThat(originalResult.aggregateVersion()).isEqualTo(1);

      UUID updateCorrelationId = UUID.randomUUID();
      publishUpdatePersonCommand(personId, updateCorrelationId);
      MessageEnvelope updateResult = codec.decode(awaitMessage(resultsQueue, updateCorrelationId).getBody());
      assertThat(updateResult.aggregateVersion()).isEqualTo(2);

      UUID retryCorrelationId = UUID.randomUUID();
      publishCreatePersonCommand(
          personId, retryCorrelationId, UUID.randomUUID(), UUID.randomUUID(), idempotencyKey);
      MessageEnvelope replayedResult = codec.decode(awaitMessage(resultsQueue, retryCorrelationId).getBody());

      assertThat(replayedResult.messageType()).isEqualTo("CreatePersonSucceeded");
      assertThat(replayedResult.aggregateId()).isEqualTo(personId);
      assertThat(replayedResult.aggregateVersion()).isEqualTo(1);
      ResultPayload payload = codec.convertPayload(replayedResult, ResultPayload.class);
      SucceededPayload data = codec.readValue(codec.writeValueAsString(payload.data()), SucceededPayload.class);
      assertThat(data.personId()).isEqualTo(personId);
      assertThat(data.version()).isEqualTo(1);
    } finally {
      amqpAdmin.deleteQueue(resultsQueue.getName());
    }
  }

  private void publishCreatePersonCommand(UUID personId, UUID correlationId, UUID operationId, UUID messageId) {
    publishCreatePersonCommand(personId, correlationId, operationId, messageId, UUID.randomUUID().toString());
  }

  private void publishCreatePersonCommand(
      UUID personId, UUID correlationId, UUID operationId, UUID messageId, String idempotencyKey) {
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
            idempotencyKey,
            personId,
            null,
            new Actor.Account(UUID.randomUUID()),
            codec.toJsonNode(command));

    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    Message message = new Message(codec.encode(envelope), props);
    rabbitTemplate.send(AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, message);
  }

  private void publishUpdatePersonCommand(UUID personId, UUID correlationId) {
    UpdatePersonCommand command =
        new UpdatePersonCommand(personId, 1, "Augusta", "Lovelace", null, "ada." + personId + "@example.edu", null);
    MessageEnvelope envelope =
        new MessageEnvelope(
            UUID.randomUUID(),
            MessageTypes.UPDATE_PERSON,
            1,
            MessageKind.COMMAND,
            "bff",
            Instant.now(),
            correlationId,
            null,
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            personId,
            null,
            new Actor.Account(UUID.randomUUID()),
            codec.toJsonNode(command));
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    rabbitTemplate.send(
        AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, new Message(codec.encode(envelope), props));
  }

  private Message awaitMessage(Queue queue, UUID correlationId) {
    Message[] holder = new Message[1];
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              relay.relay();
              Message message = rabbitTemplate.receive(queue.getName());
              assertThat(message).isNotNull();
              assertThat(codec.decode(message.getBody()).correlationId()).isEqualTo(correlationId);
              holder[0] = message;
            });
    return holder[0];
  }
}
