package com.estoma.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.estoma.users.application.command.ActivatePersonCommand;
import com.estoma.users.application.command.CreatePersonCommand;
import com.estoma.users.application.command.DeactivatePersonCommand;
import com.estoma.users.application.command.UpdatePersonCommand;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.outbound.OutboxRelay;
import com.estoma.users.testsupport.AbstractMessagingIT;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The producer-side obligation behind the consumer version ladder
 * (events.md:193-205, docs/system-contract.md §5): {@code
 * aggregateVersion} must be correct and gapless per {@code personId}.
 * {@code users-service} consumes zero events itself, so it cannot be an
 * out-of-order *consumer* — this instead proves the contract it emits is
 * *sufficient* for a downstream consumer's out-of-order handling, by
 * feeding the four events back through that exact ladder, deliberately
 * shuffled.
 */
class AggregateVersionMonotonicityIT extends AbstractMessagingIT {

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private AmqpAdmin amqpAdmin;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private TopicExchange eventsExchange;
  @Autowired private OutboxRelay relay;

  @Test
  void fourSequentialCommandsProduceFourEventsWithGaplessAggregateVersions() {
    UUID personId = UUID.randomUUID();
    Queue resultsQueue = declareQueue("results");
    amqpAdmin.declareBinding(
        BindingBuilder.bind(resultsQueue)
            .to(new DirectExchange(AmqpNames.EXCHANGE_RESULTS))
            .with("bff"));
    Queue eventsQueue = declareQueue("events");
    amqpAdmin.declareBinding(BindingBuilder.bind(eventsQueue).to(eventsExchange).with("users.person.*"));

    try {
      send(MessageTypes.CREATE_PERSON, codec.toJsonNode(
          new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada." + personId + "@example.edu", null)));
      MessageEnvelope created = awaitNextEvent(eventsQueue, "PersonCreated", personId);

      send(MessageTypes.UPDATE_PERSON, codec.toJsonNode(
          new UpdatePersonCommand(personId, 1, "Augusta", "Lovelace", null, "ada." + personId + "@example.edu", null)));
      MessageEnvelope updated = awaitNextEvent(eventsQueue, "PersonUpdated", personId);

      send(
          MessageTypes.DEACTIVATE_PERSON,
          codec.toJsonNode(new DeactivatePersonCommand(personId, 2, "ADMINISTRATIVE_REQUEST", null)));
      MessageEnvelope deactivated = awaitNextEvent(eventsQueue, "PersonDeactivated", personId);

      send(MessageTypes.ACTIVATE_PERSON, codec.toJsonNode(new ActivatePersonCommand(personId, 3)));
      MessageEnvelope activated = awaitNextEvent(eventsQueue, "PersonActivated", personId);

      assertThat(created.aggregateVersion()).isEqualTo(1);
      assertThat(updated.aggregateVersion()).isEqualTo(2);
      assertThat(deactivated.aggregateVersion()).isEqualTo(3);
      assertThat(activated.aggregateVersion()).isEqualTo(4);

      // Feed the four events back through the documented version ladder,
      // deliberately shuffled, and confirm a downstream projection would
      // still converge on version 4 -- the actual point of this contract.
      List<MessageEnvelope> shuffled = new ArrayList<>(List.of(activated, created, deactivated, updated));
      Collections.shuffle(shuffled);
      int converged = applyVersionLadder(shuffled);
      assertThat(converged).isEqualTo(4);
    } finally {
      amqpAdmin.deleteQueue(resultsQueue.getName());
      amqpAdmin.deleteQueue(eventsQueue.getName());
    }
  }

  private Queue declareQueue(String purpose) {
    Queue queue = new Queue("it.aggregate-version." + purpose + "." + UUID.randomUUID(), false, false, false);
    amqpAdmin.declareQueue(queue);
    return queue;
  }

  /**
   * events.md:193-205's consumer version ladder. A real consumer buffers
   * an out-of-order event (a "gap") and re-checks its buffer whenever it
   * successfully advances, rather than giving up on that event forever —
   * so this keeps sweeping the pending set until a full pass makes no
   * progress, correctly simulating eventual convergence once all four
   * events are available (as they are here, just shuffled).
   */
  private static int applyVersionLadder(List<MessageEnvelope> events) {
    List<MessageEnvelope> pending = new ArrayList<>(events);
    int localVersion = 0;
    boolean progressMadeThisPass = true;
    while (progressMadeThisPass && !pending.isEmpty()) {
      progressMadeThisPass = false;
      var iterator = pending.iterator();
      while (iterator.hasNext()) {
        int received = iterator.next().aggregateVersion();
        if (received <= localVersion) {
          iterator.remove(); // stale or duplicate: ignore
          progressMadeThisPass = true;
        } else if (received == localVersion + 1) {
          localVersion = received; // apply
          iterator.remove();
          progressMadeThisPass = true;
        }
        // else: still a gap relative to the current localVersion --
        // leave it in pending and re-check on the next pass.
      }
    }
    return localVersion;
  }

  /**
   * Drains {@code queue} until a message matching both {@code
   * expectedType} and {@code personId} arrives, discarding anything else.
   * The wildcard binding ({@code users.person.*}) only isolates this
   * test from unrelated aggregate types by routing key shape, not from
   * background activity in the shared broker touching a DIFFERENT
   * {@code personId} of the same event type (e.g. another test's Outbox
   * relay retry landing here in the same short window) -- filtering by
   * aggregateId as well as messageType is what actually pins this loop
   * to the four events this test itself produced.
   */
  private MessageEnvelope awaitNextEvent(Queue queue, String expectedType, UUID personId) {
    MessageEnvelope[] holder = new MessageEnvelope[1];
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              relay.relay();
              Message message = rabbitTemplate.receive(queue.getName());
              assertThat(message).isNotNull();
              MessageEnvelope envelope = codec.decode(message.getBody());
              assertThat(envelope.messageType()).isEqualTo(expectedType);
              assertThat(envelope.aggregateId()).isEqualTo(personId);
              holder[0] = envelope;
            });
    return holder[0];
  }

  private void send(String messageType, JsonNode payload) {
    MessageEnvelope envelope =
        new MessageEnvelope(
            UUID.randomUUID(),
            messageType,
            1,
            MessageKind.COMMAND,
            "bff",
            Instant.now(),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            UUID.fromString(payload.path("personId").asString()),
            null,
            new Actor.Account(UUID.randomUUID()),
            payload);
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    rabbitTemplate.send(
        AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, new Message(codec.encode(envelope), props));
  }
}
