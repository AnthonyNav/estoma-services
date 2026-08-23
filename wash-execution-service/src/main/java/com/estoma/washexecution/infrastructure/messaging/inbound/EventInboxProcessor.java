package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.application.port.ProcessedMessageRepository;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.MessagingProperties;
import com.estoma.washexecution.infrastructure.messaging.recovery.RecoveryTrigger;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class EventInboxProcessor {
  public static final String CONSUMER = "wash-execution.events";
  private final ProcessedMessageRepository inbox;
  private final ProjectionEventProcessor projections;
  private final ProjectionTrustCoordinator trust;
  private final RecoveryTrigger recovery;
  private final EnvelopeJsonCodec codec;
  private final MessagingProperties properties;
  private final TransactionTemplate transactions;

  public EventInboxProcessor(
      ProcessedMessageRepository inbox,
      ProjectionEventProcessor projections,
      ProjectionTrustCoordinator trust,
      RecoveryTrigger recovery,
      EnvelopeJsonCodec codec,
      MessagingProperties properties,
      PlatformTransactionManager manager) {
    this.inbox = inbox;
    this.projections = projections;
    this.trust = trust;
    this.recovery = recovery;
    this.codec = codec;
    this.properties = properties;
    this.transactions = new TransactionTemplate(manager);
  }

  public void process(MessageEnvelope envelope) {
    transactions.executeWithoutResult(status -> processNew(envelope));
  }

  private void processNew(MessageEnvelope e) {
    var existing = inbox.find(CONSUMER, e.messageId());
    if (existing.isPresent()) return;
    Instant now = Instant.now();
    inbox.insertIfAbsent(toDeferred(e, now, "EVENT_PROCESSING"));
    UUID claim = UUID.randomUUID();
    if (!inbox.claimDeferred(CONSUMER, e.messageId(), now, claim, now.plus(properties.inboxLease()))) {
      throw new IllegalStateException("EVENT_INBOX_CLAIM_FAILED");
    }
    var outcome = projections.process(e);
    if (outcome.applied()) {
      if (!inbox.markApplied(CONSUMER, e.messageId(), claim, Instant.now())) {
        throw new IllegalStateException("EVENT_INBOX_FENCE_LOST");
      }
    } else {
      if (!inbox.keepDeferred(CONSUMER, e.messageId(), claim, outcome.deferredReason(), Instant.now())) {
        throw new IllegalStateException("EVENT_INBOX_FENCE_LOST");
      }
      trust.deferred(e.producer(), outcome.deferredReason());
      recovery.onDeferred(e);
    }
  }

  ProcessedMessageRepository.ProcessedMessage toDeferred(MessageEnvelope e, Instant receivedAt, String reason) {
    return new ProcessedMessageRepository.ProcessedMessage(
        CONSUMER, e.messageId(), ProcessedMessageRepository.Status.DEFERRED, e.messageType(), e.messageVersion(),
        e.messageKind().name(), e.producer(), e.correlationId(), e.causationId(), e.operationId(), e.idempotencyKey(),
        e.aggregateId(), e.aggregateVersion() == null ? null : e.aggregateVersion().longValue(), codec.write(e.actor()),
        codec.write(e.payload()), receivedAt, e.occurredAt(), null, reason, receivedAt);
  }
}
