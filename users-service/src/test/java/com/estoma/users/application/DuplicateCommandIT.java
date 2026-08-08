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
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Redelivering the exact same {@code messageId} must never duplicate
 * anything: not the person row, not the Inbox row, not the derived event
 * (docs/system-contract.md §7). The second delivery is still a completely
 * ordinary ACK from the broker's point of view — {@code CommandListener}
 * has no special "this is a duplicate" branch, the Inbox insert simply
 * affects zero rows and the dispatcher returns early.
 */
class DuplicateCommandIT extends AbstractMessagingIT {

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private JdbcClient jdbc;

  @Test
  void redeliveringTheSameMessageIdProducesExactlyOnePersonAndOneEvent() {
    UUID personId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    byte[] body = buildCommandBody(personId, messageId, correlationId);

    publish(body);
    // Wait for the first delivery to be fully processed (person row exists).
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> countPersons(personId) == 1);

    publish(body); // exact same envelope bytes, same messageId
    // Give the second delivery time to be consumed and (correctly) do
    // nothing; there is no positive event to await here, so a bounded
    // sleep is the honest way to assert "and nothing else happened".
    awaitRedeliveryToSettle();

    assertThat(countPersons(personId)).isEqualTo(1);
    assertThat(countInboxRows(messageId)).isEqualTo(1);
    assertThat(countOutboxRowsByCorrelation(correlationId, "EVENT")).isEqualTo(1);
    assertThat(countOutboxRowsByCorrelation(correlationId, "RESULT")).isEqualTo(1);
  }

  private void publish(byte[] body) {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    rabbitTemplate.send(AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, new Message(body, props));
  }

  private byte[] buildCommandBody(UUID personId, UUID messageId, UUID correlationId) {
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
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            personId,
            null,
            new Actor.Account(UUID.randomUUID()),
            codec.toJsonNode(command));
    return codec.encode(envelope);
  }

  private long countPersons(UUID personId) {
    return jdbc.sql("SELECT count(*) FROM persons WHERE person_id = :id").param("id", personId).query(Long.class).single();
  }

  private long countInboxRows(UUID messageId) {
    return jdbc.sql("SELECT count(*) FROM processed_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(Long.class)
        .single();
  }

  private long countOutboxRowsByCorrelation(UUID correlationId, String kind) {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE correlation_id = :id AND message_kind = :kind")
        .param("id", correlationId)
        .param("kind", kind)
        .query(Long.class)
        .single();
  }

  private static void awaitRedeliveryToSettle() {
    try {
      Thread.sleep(1_500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
