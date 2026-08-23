package com.estoma.communications.infrastructure.messaging;

import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.contracts.MessagingNames;
import com.estoma.communications.infrastructure.persistence.JdbcOutboxRelayRepository;
import java.time.Instant;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes durable Outbox rows only after broker confirmation. */
@Component
public class OutboxRelay {

  private static final int BATCH_SIZE = 50;

  private final JdbcOutboxRelayRepository repository;
  private final EnvelopeJsonCodec codec;
  private final ConfirmedRabbitPublisher publisher;
  private final CommunicationsEventRoutingKeyResolver eventRoutingKeys;

  OutboxRelay(
      JdbcOutboxRelayRepository repository,
      EnvelopeJsonCodec codec,
      ConfirmedRabbitPublisher publisher,
      CommunicationsEventRoutingKeyResolver eventRoutingKeys) {
    this.repository = repository;
    this.codec = codec;
    this.publisher = publisher;
    this.eventRoutingKeys = eventRoutingKeys;
  }

  @Scheduled(fixedDelayString = "${estoma.messaging.outbox-relay-delay:500ms}")
  public void relay() {
    Instant now = Instant.now();
    for (JdbcOutboxRelayRepository.OutboxRow row : repository.claim(BATCH_SIZE, now)) {
      try {
        publish(row);
        repository.markPublished(row.messageId(), Instant.now());
      } catch (RuntimeException publicationFailure) {
        repository.markPublicationFailure(row.messageId(), row.attempts(), Instant.now());
      }
    }
  }

  private void publish(JdbcOutboxRelayRepository.OutboxRow row) {
    MessageKind kind;
    try {
      kind = MessageKind.valueOf(row.messageKind());
    } catch (IllegalArgumentException invalidKind) {
      throw new IllegalStateException("unsupported Outbox message kind " + row.messageKind(), invalidKind);
    }

    MessageEnvelope envelope = new MessageEnvelope(
        row.messageId(),
        row.messageType(),
        row.messageVersion(),
        kind,
        "communications",
        row.occurredAt(),
        row.correlationId(),
        row.causationId(),
        row.operationId(),
        row.idempotencyKey(),
        row.aggregateId(),
        row.aggregateVersion(),
        row.actor(),
        row.payload());

    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(row.messageId().toString());
    properties.setCorrelationId(row.correlationId().toString());
    Message message = new Message(codec.encode(envelope), properties);

    if (kind == MessageKind.RESULT) {
      if (row.destination() == null || row.destination().isBlank()) {
        throw new IllegalStateException("RESULT Outbox row requires destination");
      }
      publisher.publishRequiredRoute(MessagingNames.RESULTS_EXCHANGE, row.destination(), message);
      return;
    }
    if (kind == MessageKind.EVENT) {
      publisher.publishEvent(MessagingNames.EVENTS_EXCHANGE, eventRoutingKeys.resolve(row.messageType()), message);
      return;
    }
    throw new IllegalStateException("Outbox Relay may publish only RESULT or EVENT rows, got " + kind);
  }
}
