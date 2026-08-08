package com.estoma.authentication.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessagingNames;
import com.estoma.authentication.contracts.consumed.ConsumedEventTypes;
import com.estoma.authentication.contracts.consumed.PersonStatusEventPayload;
import com.estoma.authentication.testsupport.AbstractRabbitMqIT;
import com.estoma.authentication.testsupport.Envelopes;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end proof that {@code EventListener} correctly wires {@code
 * ProjectionDispatcher} to real RabbitMQ, binding on the routing key
 * {@code users-service} actually publishes ({@code identity.person.*},
 * not the {@code users.person.*} example string in
 * messaging-architecture.md §4 -- see {@link AmqpNames}).
 */
class EventProjectionIT extends AbstractRabbitMqIT {

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;

  @Test
  void personCreatedEventUpdatesTheProjection() throws InterruptedException {
    UUID personId = UUID.randomUUID();
    MessageEnvelope envelope =
        Envelopes.event(ConsumedEventTypes.PERSON_CREATED, new PersonStatusEventPayload(personId, "ACTIVE", 1), personId, 1);

    rabbitTemplate.send(
        MessagingNames.EVENTS_EXCHANGE, AmqpNames.ROUTING_KEY_PERSON_CREATED, new Message(codec.encode(envelope)));

    awaitProjection(personId);
  }

  private void awaitProjection(UUID personId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      var projection = personStatusProjectionRepository.findByPersonId(personId);
      if (projection.isPresent()) {
        assertThat(projection.get().status()).isEqualTo("ACTIVE");
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("person status projection for " + personId + " was not applied within the deadline");
  }
}
