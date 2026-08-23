package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.MessagingNames;
import com.estoma.appointments.contracts.WireActor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims in a short transaction, publishes with confirm, then fences the claim. */
@Component
public final class OutboxRelay {
  private final OutboxRepository outbox;
  private final ConfirmedRabbitPublisher publisher;
  private final EnvelopeJsonCodec codec;
  private final MessagingProperties properties;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public OutboxRelay(OutboxRepository outbox, ConfirmedRabbitPublisher publisher, EnvelopeJsonCodec codec,
      MessagingProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
    this.outbox = outbox; this.publisher = publisher; this.codec = codec; this.properties = properties;
    this.transactions = new TransactionTemplate(transactionManager); this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${estoma.messaging.outbox-poll-interval:1s}")
  public void scheduledRelay() { if (properties.outboxRelayEnabled()) relay(); }

  public void relay() {
    String worker = UUID.randomUUID().toString();
    Instant now = clock.instant();
    List<OutboxRepository.OutboxMessage> messages = transactions.execute(status -> outbox.claimBatch(
        properties.outboxClaimBatchSize(), worker, now, now.plus(properties.outboxLease())));
    if (messages == null) return;
    for (var message : messages) {
      try {
        publish(message);
        transactions.execute(status -> outbox.markPublished(message.messageId(), worker, clock.instant()));
      } catch (RabbitPublishException exception) {
        int attempts = message.attempts() + 1;
        transactions.execute(status -> outbox.markFailed(message.messageId(), worker, exception.getMessage(),
            clock.instant().plusSeconds(Math.min(300L, 1L << Math.min(attempts, 8)))));
      } catch (RuntimeException permanent) {
        transactions.execute(status -> outbox.quarantine(message.messageId(), worker, "INVALID_OUTBOX_CONTRACT: " + permanent.getMessage(), clock.instant()));
      }
    }
  }

  private void publish(OutboxRepository.OutboxMessage message) {
    MessageKind kind = MessageKind.valueOf(message.messageKind());
    String exchange = switch (kind) {
      case EVENT -> MessagingNames.EVENTS_EXCHANGE;
      case RESULT -> MessagingNames.RESULTS_EXCHANGE;
      case QUERY -> MessagingNames.QUERIES_EXCHANGE;
      case COMMAND -> throw new IllegalArgumentException("appointments does not emit Commands");
    };
    String route = routingKey(kind, message.messageType(), message.payloadJson());
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(message.messageId().toString());
    MessageEnvelope envelope = new MessageEnvelope(message.messageId(), message.messageType(), message.messageVersion(),
        kind, MessagingNames.LOGICAL_COMPONENT_ID, message.occurredAt(), message.correlationId(), message.causationId(),
        null, null, message.aggregateId(), message.aggregateVersion() == null ? null : Math.toIntExact(message.aggregateVersion()), new WireActor("SERVICE", new UUID(0, 0), "appointments"),
        codec.readTree(message.payloadJson()));
    Message wire = new Message(codec.encode(envelope), properties);
    if (kind == MessageKind.EVENT) publisher.publishEvent(exchange, route, wire);
    else publisher.publishRequiredRoute(exchange, route, wire);
  }

  private static String routingKey(MessageKind kind, String messageType, String payload) {
    if (kind == MessageKind.RESULT) return MessagingNames.LOGICAL_COMPONENT_ID;
    if (kind == MessageKind.QUERY) {
      if (messageType.startsWith("GetWashAdministration")) return "wash-administration";
      if (messageType.startsWith("GetAccountAuthorization")) return "authorization";
      if (messageType.startsWith("GetStudentAcademic")) return "academic";
      return "wash-execution";
    }
    String word = messageType.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    return "appointments." + word;
  }
}
