package com.estoma.authorization.application.command;

import com.estoma.authorization.application.port.AuthorizationAuditRepository;
import com.estoma.authorization.application.port.CommandDispatcher;
import com.estoma.authorization.application.port.CommandIdempotencyRepository;
import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.port.StoredApplicationResultDecoder;
import com.estoma.authorization.application.port.TransactionRunner;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.authorization.application.support.OutboxIntentWriter;
import com.estoma.authorization.application.support.ResultIntentFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Canonical inbound Command application boundary. Delivery dedupe belongs here; the delegate remains
 * focused on functional idempotency, authority and state transitions.
 */
public final class AuthorizationCommandProcessor implements CommandDispatcher {

  private static final String CONSUMER = "authorization-commands";

  private final TransactionRunner transactions;
  private final AuthorizationCommandDispatcher delegate;
  private final ProcessedMessageRepository inbox;
  private final CommandIdempotencyRepository idempotency;
  private final StoredApplicationResultDecoder resultDecoder;
  private final OutboxRepository outbox;
  private final AuthorizationAuditRepository audit;
  private final ProjectionRecoveryCoordinator recovery;
  private final Clock clock;

  public AuthorizationCommandProcessor(
      TransactionRunner transactions,
      AuthorizationCommandDispatcher delegate,
      ProcessedMessageRepository inbox,
      CommandIdempotencyRepository idempotency,
      StoredApplicationResultDecoder resultDecoder,
      OutboxRepository outbox,
      AuthorizationAuditRepository audit,
      ProjectionRecoveryCoordinator recovery,
      Clock clock) {
    this.transactions = transactions;
    this.delegate = delegate;
    this.inbox = inbox;
    this.idempotency = idempotency;
    this.resultDecoder = resultDecoder;
    this.outbox = outbox;
    this.audit = audit;
    this.recovery = recovery;
    this.clock = clock;
  }

  @Override
  public CommandOutcome dispatch(CommandMetadata metadata, AuthorizationCommand command) {
    validateEnvelopeShape(metadata, command);
    CommandOutcome outcome =
        transactions.inTransaction(() -> processInTransaction(metadata, command));
    if (outcome.deferred()
        && "PROJECTION_RECONCILIATION".equals(outcome.pendingReason())
        && command.targetAccountId() != null) {
      recovery.requestAccount(command.targetAccountId());
    }
    return outcome;
  }

  private CommandOutcome processInTransaction(
      CommandMetadata metadata, AuthorizationCommand command) {
    Optional<ProcessedMessageRepository.ProcessedMessage> delivery =
        inbox.find(CONSUMER, metadata.messageId());
    if (delivery.isPresent()) {
      return "APPLIED".equals(delivery.get().status())
          ? CommandOutcome.duplicate()
          : CommandOutcome.deferred(delivery.get().deferredReason());
    }

    String fingerprint = CommandPayloads.fingerprint(metadata, command);
    Optional<CommandIdempotencyRepository.IdempotencyRecord> record =
        idempotency.find(metadata.producer(), command.operationType(), metadata.idempotencyKey());
    if (record.isPresent()
        && record.get().requestFingerprint().equals(fingerprint)
        && !"DEFERRED".equals(record.get().status())) {
      return replayTerminalResult(metadata, command, record.get());
    }

    // The delegate creates Inbox + idempotency reservation and is safe inside this same transaction.
    return delegate.dispatch(metadata, command);
  }

  private CommandOutcome replayTerminalResult(
      CommandMetadata metadata,
      AuthorizationCommand command,
      CommandIdempotencyRepository.IdempotencyRecord record) {
    Instant now = clock.instant();
    inbox.insertIfAbsent(new ProcessedMessageRepository.ProcessedMessage(
        CONSUMER,
        metadata.messageId(),
        "DEFERRED",
        metadata.messageType(),
        metadata.messageVersion(),
        "COMMAND",
        metadata.producer(),
        metadata.correlationId(),
        metadata.causationId(),
        metadata.operationId(),
        metadata.idempotencyKey(),
        metadata.aggregateId(),
        metadata.aggregateVersion(),
        metadata.actor(),
        CommandPayloads.write(command),
        now,
        metadata.occurredAt(),
        null,
        "PROCESSING",
        now));

    ApplicationResult result = resultDecoder.decode(record);
    OutboundResultIntent resultIntent = ResultIntentFactory.create(
        metadata, command, result, record.aggregateId(), record.aggregateVersion(), now);
    OutboxIntentWriter.append(outbox, resultIntent);
    inbox.markApplied(CONSUMER, metadata.messageId(), now, now);

    ImportExecution execution = command.importExecution();
    audit.append(new AuthorizationAuditRepository.AuditEntry(
        UUID.randomUUID(),
        metadata.messageId(),
        metadata.producer(),
        command.operationType(),
        record.aggregateId(),
        record.aggregateVersion(),
        "IDEMPOTENT_RESULT_REPLAY",
        metadata.actor(),
        execution == null ? null : execution.authorizedByAccountId(),
        execution == null ? null : execution.importId(),
        metadata.correlationId(),
        metadata.causationId(),
        metadata.operationId(),
        metadata.idempotencyKey(),
        result.status().name(),
        result.error() == null ? null : result.error().code(),
        result.error() == null ? null : result.error().details(),
        now));
    return CommandOutcome.terminal(result, List.of(), List.of(resultIntent));
  }

  private static void validateEnvelopeShape(
      CommandMetadata metadata, AuthorizationCommand command) {
    if (!command.operationType().equals(metadata.messageType())) {
      throw new IllegalArgumentException("messageType does not match command");
    }
    if ("academic".equals(metadata.producer()) && command.importExecution() == null) {
      throw new IllegalArgumentException("Academic command requires importExecution");
    }
    if (!"academic".equals(metadata.producer()) && command.importExecution() != null) {
      throw new IllegalArgumentException("importExecution is restricted to Academic");
    }
  }
}
