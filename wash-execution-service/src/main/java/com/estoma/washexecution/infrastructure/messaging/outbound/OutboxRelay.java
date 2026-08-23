package com.estoma.washexecution.infrastructure.messaging.outbound;

import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessageKind;
import com.estoma.washexecution.contracts.MessagingNames;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.MessagingProperties;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="estoma.messaging",name="outbox-relay-enabled",havingValue="true",matchIfMissing=true)
public final class OutboxRelay {
  private final OutboxRelayStore store;
  private final ConfirmedRabbitPublisher publisher;
  private final EventRoutingKeyResolver eventRoutingKeys;
  private final EnvelopeJsonCodec codec;
  private final MessagingProperties properties;

  public OutboxRelay(
      OutboxRelayStore store,
      ConfirmedRabbitPublisher publisher,
      EventRoutingKeyResolver eventRoutingKeys,
      EnvelopeJsonCodec codec,
      MessagingProperties properties) {
    this.store = store;
    this.publisher = publisher;
    this.eventRoutingKeys = eventRoutingKeys;
    this.codec = codec;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString="${estoma.messaging.outbox-poll-interval:1000}",
      initialDelayString="${estoma.messaging.outbox-initial-delay:1000}")
  public void relay() {
    Instant now = Instant.now();
    UUID claim = UUID.randomUUID();
    var batch = store.claimBatch(properties.outboxClaimBatchSize(), now, claim, now.plus(properties.outboxLease()));
    for (var record : batch) {
      try {
        publish(record);
        if (!store.markPublished(record.messageId(), claim, Instant.now())) {
          throw new IllegalStateException("OUTBOX_FENCE_LOST");
        }
      } catch (IllegalArgumentException permanent) {
        store.markFailed(record.messageId(), claim, permanent.getMessage());
      } catch (RuntimeException transientFailure) {
        long seconds = Math.min(300L, 1L << Math.min(8, Math.max(0, record.attemptCount() - 1)));
        store.reschedule(record.messageId(), claim, Instant.now().plusSeconds(seconds), transientFailure.getMessage());
      }
    }
  }

  private void publish(OutboxRelayStore.RelayMessage record) {
    MessageKind kind;
    try { kind = MessageKind.valueOf(record.messageKind()); }
    catch (Exception ex) { throw new IllegalArgumentException("UNSUPPORTED_OUTBOX_KIND", ex); }
    if (kind == MessageKind.COMMAND) throw new IllegalArgumentException("UNSUPPORTED_OUTBOX_KIND");

    MessageEnvelope envelope = new MessageEnvelope(
        record.messageId(), record.messageType(), record.messageVersion(), kind, record.producer(),
        record.occurredAt(), record.correlationId(), record.causationId(), record.operationId(),
        record.idempotencyKey(), record.aggregateId(), record.aggregateVersion(), codec.readActor(record.actorJson()),
        codec.readNode(record.payloadJson()));
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    props.setMessageId(record.messageId().toString());
    Message message = new Message(codec.encode(envelope), props);

    if (kind == MessageKind.EVENT) {
      publisher.publishEvent(
          MessagingNames.EVENTS_EXCHANGE,
          eventRoutingKeys.resolve(record.messageType()),
          message);
    } else if (kind == MessageKind.RESULT) {
      publisher.publishRequiredRoute(MessagingNames.RESULTS_EXCHANGE, record.routingKey(), message);
    } else if (kind == MessageKind.QUERY) {
      publisher.publishRequiredRoute(MessagingNames.QUERIES_EXCHANGE, record.routingKey(), message);
    }
  }
}
