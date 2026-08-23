package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.application.port.ProcessedMessageRepository;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessageKind;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.MessagingProperties;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(prefix="estoma.messaging",name="deferred-replay-enabled",havingValue="true",matchIfMissing=true)
public final class DeferredEventReplayWorker {
  private final ProcessedMessageRepository inbox;
  private final ProjectionEventProcessor projections;
  private final ProjectionTrustCoordinator trust;
  private final EnvelopeJsonCodec codec;
  private final MessagingProperties properties;
  private final TransactionTemplate transactions;

  public DeferredEventReplayWorker(
      ProcessedMessageRepository inbox,
      ProjectionEventProcessor projections,
      ProjectionTrustCoordinator trust,
      EnvelopeJsonCodec codec,
      MessagingProperties properties,
      PlatformTransactionManager manager) {
    this.inbox = inbox;
    this.projections = projections;
    this.trust = trust;
    this.codec = codec;
    this.properties = properties;
    this.transactions = new TransactionTemplate(manager);
  }

  @Scheduled(
      fixedDelayString="${estoma.messaging.deferred-replay-poll-interval:1000}",
      initialDelayString="${estoma.messaging.deferred-replay-initial-delay:1000}")
  public void replay() {
    UUID claim = UUID.randomUUID();
    Instant now = Instant.now();
    var batch = transactions.execute(status -> inbox.claimDeferred(
        EventInboxProcessor.CONSUMER, 25, now, claim, now.plus(properties.inboxLease())));
    if (batch == null) return;
    for (var message : batch) {
      transactions.executeWithoutResult(status -> replayOne(message, claim));
    }
  }

  private void replayOne(ProcessedMessageRepository.ProcessedMessage m, UUID claim) {
    MessageEnvelope envelope = new MessageEnvelope(
        m.messageId(), m.messageType(), m.messageVersion(), MessageKind.EVENT, m.producer(), m.occurredAt(),
        m.correlationId(), m.causationId(), m.operationId(), m.idempotencyKey(), m.aggregateId(),
        m.aggregateVersion() == null ? null : Math.toIntExact(m.aggregateVersion()), codec.readActor(m.actorJson()),
        codec.readNode(m.payloadJson()));
    var outcome = projections.process(envelope);
    if (outcome.applied()) {
      if (!inbox.markApplied(EventInboxProcessor.CONSUMER, m.messageId(), claim, Instant.now())) {
        throw new IllegalStateException("EVENT_INBOX_FENCE_LOST");
      }
      trust.maybeRestoreAfterReplay(m.producer());
    } else {
      if (!inbox.keepDeferred(
          EventInboxProcessor.CONSUMER, m.messageId(), claim, outcome.deferredReason(), Instant.now())) {
        throw new IllegalStateException("EVENT_INBOX_FENCE_LOST");
      }
      trust.deferred(m.producer(), outcome.deferredReason());
    }
  }
}
