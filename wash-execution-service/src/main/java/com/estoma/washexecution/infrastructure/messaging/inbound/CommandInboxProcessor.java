package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.application.command.CommandRequest;
import com.estoma.washexecution.application.port.ProcessedMessageRepository;
import com.estoma.washexecution.application.port.WashExecutionCommandUseCase;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.MessagingProperties;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class CommandInboxProcessor {
  private static final String CONSUMER = "wash-execution.commands";
  private final ProcessedMessageRepository inbox;
  private final WashExecutionCommandUseCase commands;
  private final EnvelopeJsonCodec codec;
  private final MessagingProperties properties;
  private final TransactionTemplate transactions;

  public CommandInboxProcessor(
      ProcessedMessageRepository inbox,
      WashExecutionCommandUseCase commands,
      EnvelopeJsonCodec codec,
      MessagingProperties properties,
      PlatformTransactionManager manager) {
    this.inbox = inbox;
    this.commands = commands;
    this.codec = codec;
    this.properties = properties;
    this.transactions = new TransactionTemplate(manager);
  }

  public void process(MessageEnvelope envelope, CommandRequest request) {
    transactions.executeWithoutResult(status -> processInTransaction(envelope, request));
  }

  private void processInTransaction(MessageEnvelope envelope, CommandRequest request) {
    Instant now = Instant.now();
    var existing = inbox.find(CONSUMER, envelope.messageId());
    if (existing.isPresent() && existing.get().status() == ProcessedMessageRepository.Status.APPLIED) return;
    if (existing.isPresent()) return;

    inbox.insertIfAbsent(toDeferred(envelope, now, "COMMAND_PROCESSING"));
    UUID claim = UUID.randomUUID();
    if (!inbox.claimDeferred(CONSUMER, envelope.messageId(), now, claim, now.plus(properties.inboxLease()))) {
      throw new IllegalStateException("COMMAND_INBOX_CLAIM_FAILED");
    }
    commands.dispatch(request);
    if (!inbox.markApplied(CONSUMER, envelope.messageId(), claim, Instant.now())) {
      throw new IllegalStateException("COMMAND_INBOX_FENCE_LOST");
    }
  }

  private ProcessedMessageRepository.ProcessedMessage toDeferred(MessageEnvelope e, Instant receivedAt, String reason) {
    return new ProcessedMessageRepository.ProcessedMessage(
        CONSUMER, e.messageId(), ProcessedMessageRepository.Status.DEFERRED, e.messageType(), e.messageVersion(),
        e.messageKind().name(), e.producer(), e.correlationId(), e.causationId(), e.operationId(), e.idempotencyKey(),
        e.aggregateId(), e.aggregateVersion() == null ? null : e.aggregateVersion().longValue(),
        codec.write(e.actor()), codec.write(e.payload()), receivedAt, e.occurredAt(), null, reason, receivedAt);
  }
}
