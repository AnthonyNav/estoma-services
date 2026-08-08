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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A structurally/contractually invalid message must go straight to the
 * DLQ and never visit a retry tier (docs/system-contract.md §7,
 * messaging-architecture.md §16: invalid messages are isolated, not
 * retried indefinitely).
 */
class InvalidMessageIT extends AbstractMessagingIT {

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private EnvelopeJsonCodec codec;

  @Test
  void malformedJsonGoesStraightToTheDlq() {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    Message message = new Message("{ this is not valid json".getBytes(StandardCharsets.UTF_8), props);
    rabbitTemplate.send(AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, message);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ)).isNotNull());

    assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_RETRY_5S)).isNull();
  }

  @Test
  void missingIdempotencyKeyGoesStraightToTheDlq() {
    byte[] body = envelopeWithIdempotencyKey(null);
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    rabbitTemplate.send(AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, new Message(body, props));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ)).isNotNull());

    assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_RETRY_5S)).isNull();
    assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_RETRY_30S)).isNull();
    assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_RETRY_300S)).isNull();
  }

  @Test
  void missingAggregateIdGoesStraightToTheDlq() {
    byte[] body = envelopeWithAggregateId(null);
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    rabbitTemplate.send(AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, new Message(body, props));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ)).isNotNull());
  }

  @Test
  void unsupportedMessageVersionGoesStraightToTheDlq() {
    UUID personId = UUID.randomUUID();
    CreatePersonCommand command =
        new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada." + personId + "@example.edu", null);
    MessageEnvelope envelope =
        new MessageEnvelope(
            UUID.randomUUID(),
            MessageTypes.CREATE_PERSON,
            99, // unsupported
            MessageKind.COMMAND,
            "bff",
            Instant.now(),
            UUID.randomUUID(),
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

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ)).isNotNull());
  }

  private byte[] envelopeWithIdempotencyKey(String idempotencyKey) {
    UUID personId = UUID.randomUUID();
    CreatePersonCommand command =
        new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada." + personId + "@example.edu", null);
    MessageEnvelope envelope =
        new MessageEnvelope(
            UUID.randomUUID(),
            MessageTypes.CREATE_PERSON,
            1,
            MessageKind.COMMAND,
            "bff",
            Instant.now(),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            idempotencyKey,
            personId,
            null,
            new Actor.Account(UUID.randomUUID()),
            codec.toJsonNode(command));
    return codec.encode(envelope);
  }

  private byte[] envelopeWithAggregateId(UUID aggregateId) {
    UUID personId = UUID.randomUUID();
    CreatePersonCommand command =
        new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada." + personId + "@example.edu", null);
    MessageEnvelope envelope =
        new MessageEnvelope(
            UUID.randomUUID(),
            MessageTypes.CREATE_PERSON,
            1,
            MessageKind.COMMAND,
            "bff",
            Instant.now(),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            aggregateId,
            null,
            new Actor.Account(UUID.randomUUID()),
            codec.toJsonNode(command));
    return codec.encode(envelope);
  }
}
