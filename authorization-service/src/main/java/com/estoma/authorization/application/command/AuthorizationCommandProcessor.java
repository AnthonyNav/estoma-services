package com.estoma.authorization.application.command;

import com.estoma.authorization.application.exception.AuthorizationErrorCode;
import com.estoma.authorization.application.exception.OptimisticLockConflictException;
import com.estoma.authorization.application.port.AuthorizationAuditRepository;
import com.estoma.authorization.application.port.CommandDispatcher;
import com.estoma.authorization.application.port.CommandIdempotencyRepository;
import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.port.StoredApplicationResultDecoder;
import com.estoma.authorization.application.port.TechnicalFailureClassifier;
import com.estoma.authorization.application.port.TransactionRunner;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.authorization.application.support.CanonicalJson;
import com.estoma.authorization.application.support.OutboxIntentWriter;
import com.estoma.authorization.application.support.ResultIntentFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Canonical inbound Command application boundary.
 *
 * <p>Delivery dedupe is independent from functional idempotency. The delegate owns the business
 * transaction; this boundary deliberately does not wrap a new command execution in another
 * transaction so optimistic-lock/technical failures can roll the complete mutation back before a
 * terminal REJECTED/FAILED Result is persisted in a fresh transaction. Retryable infrastructure
 * failures escape this boundary so the PR3 transport policy can apply retry/backoff before deciding
 * that a Command has reached a terminal technical failure.
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
  private final TechnicalFailureClassifier failureClassifier;
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
      TechnicalFailureClassifier failureClassifier,
      Clock clock) {
    this.transactions = transactions;
    this.delegate = delegate;
    this.inbox = inbox;
    this.idempotency = idempotency;
    this.resultDecoder = resultDecoder;
    this.outbox = outbox;
    this.audit = audit;
    this.recovery = recovery;
    this.failureClassifier = failureClassifier;
    this.clock = clock;
  }

  @Override
  public CommandOutcome dispatch(CommandMetadata metadata, AuthorizationCommand command) {
    validateEnvelopeShape(metadata, command);

    Optional<ProcessedMessageRepository.ProcessedMessage> delivery =
        transactions.inTransaction(() -> inbox.find(CONSUMER, metadata.messageId()));
    if (delivery.isPresent()) {
      return "APPLIED".equals(delivery.get().status())
          ? CommandOutcome.duplicate()
          : CommandOutcome.deferred(delivery.get().deferredReason());
    }

    String fingerprint = CommandPayloads.fingerprint(metadata, command);
    Optional<CommandIdempotencyRepository.IdempotencyRecord> record =
        transactions.inTransaction(() ->
            idempotency.find(metadata.producer(), command.operationType(), metadata.idempotencyKey()));
    if (record.isPresent()
        && record.get().requestFingerprint().equals(fingerprint)
        && !"DEFERRED".equals(record.get().status())) {
      return transactions.inTransaction(() -> replayTerminalResult(metadata, command, record.get()));
    }

    if (isStaleAcademicGeneration(metadata, command)) {
      return transactions.inTransaction(() -> recordTerminalWithoutBusinessMutation(
          metadata,
          command,
          ApplicationResult.rejected(
              AuthorizationErrorCode.STALE_EXECUTION_GENERATION.name(),
              "execution generation is older than the latest observed generation"),
          fingerprint,
          null,
          null));
    }

    CommandOutcome outcome;
    try {
      outcome = delegate.dispatch(metadata, command);
    } catch (OptimisticLockConflictException conflict) {
      outcome = transactions.inTransaction(() -> recordTerminalWithoutBusinessMutation(
          metadata,
          command,
          ApplicationResult.rejected(conflict.code().name(), conflict.getMessage()),
          fingerprint,
          null,
          null));
    } catch (RuntimeException technicalFailure) {
      if (failureClassifier.isRetryable(technicalFailure)) {
        throw technicalFailure;
      }
      try {
        outcome = transactions.inTransaction(() -> recordTerminalWithoutBusinessMutation(
            metadata,
            command,
            ApplicationResult.failed(
                AuthorizationErrorCode.TECHNICAL_FAILURE.name(),
                "authorization command failed with a terminal technical error"),
            fingerprint,
            null,
            null));
      } catch (RuntimeException persistenceFailure) {
        technicalFailure.addSuppressed(persistenceFailure);
        throw technicalFailure;
      }
    }

    if (outcome.deferred()
        && "PROJECTION_RECONCILIATION".equals(outcome.pendingReason())
        && command.targetAccountId() != null) {
      recovery.requestAccount(command.targetAccountId());
    }
    return outcome;
  }

  private boolean isStaleAcademicGeneration(
      CommandMetadata metadata, AuthorizationCommand command) {
    ImportExecution execution = command.importExecution();
    if (!"academic".equals(metadata.producer()) || execution == null) {
      return false;
    }
    return transactions.inTransaction(() -> idempotency.findLatestExecutionGeneration(
            metadata.producer(), execution.importId(), execution.planItemId())
        .map(latest -> execution.executionGeneration() < latest)
        .orElse(false));
  }

  private CommandOutcome replayTerminalResult(
      CommandMetadata metadata,
      AuthorizationCommand command,
      CommandIdempotencyRepository.IdempotencyRecord record) {
    Instant now = clock.instant();
    ensureInbox(metadata, command, now);

    ApplicationResult result = resultDecoder.decode(record);
    OutboundResultIntent resultIntent = ResultIntentFactory.create(
        metadata, command, result, record.aggregateId(), record.aggregateVersion(), now);
    OutboxIntentWriter.append(outbox, resultIntent);
    inbox.markApplied(CONSUMER, metadata.messageId(), now, now);

    appendAudit(
        metadata,
        command,
        record.aggregateId(),
        record.aggregateVersion(),
        "IDEMPOTENT_RESULT_REPLAY",
        result,
        now);
    return CommandOutcome.terminal(result, List.of(), List.of(resultIntent));
  }

  private CommandOutcome recordTerminalWithoutBusinessMutation(
      CommandMetadata metadata,
      AuthorizationCommand command,
      ApplicationResult rawResult,
      String fingerprint,
      UUID aggregateId,
      Integer aggregateVersion) {
    Instant now = clock.instant();
    ensureInbox(metadata, command, now);

    Optional<CommandIdempotencyRepository.IdempotencyRecord> existing =
        idempotency.find(metadata.producer(), command.operationType(), metadata.idempotencyKey());
    if (existing.isPresent()) {
      if (!existing.get().requestFingerprint().equals(fingerprint)) {
        ApplicationResult mismatch = CommandResultSupport.ensureImportExecutionData(
            command,
            ApplicationResult.rejected(
                AuthorizationErrorCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST.name(),
                "idempotency key was already used for a different request"));
        return emitStandaloneResult(metadata, command, mismatch, null, null, now);
      }
      if (!"DEFERRED".equals(existing.get().status())) {
        return replayTerminalResult(metadata, command, existing.get());
      }
    } else {
      CommandIdempotencyRepository.IdempotencyRecord initial = initialRecord(
          metadata, command, fingerprint, now);
      if (!idempotency.insertIfAbsent(initial)) {
        CommandIdempotencyRepository.IdempotencyRecord concurrent = idempotency.find(
                metadata.producer(), command.operationType(), metadata.idempotencyKey())
            .orElseThrow(() -> new IllegalStateException("idempotency reservation disappeared"));
        if (!concurrent.requestFingerprint().equals(fingerprint)) {
          ApplicationResult mismatch = CommandResultSupport.ensureImportExecutionData(
              command,
              ApplicationResult.rejected(
                  AuthorizationErrorCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST.name(),
                  "idempotency key was already used for a different request"));
          return emitStandaloneResult(metadata, command, mismatch, null, null, now);
        }
        if (!"DEFERRED".equals(concurrent.status())) {
          return replayTerminalResult(metadata, command, concurrent);
        }
      }
    }

    ApplicationResult result = CommandResultSupport.ensureImportExecutionData(command, rawResult);
    ImportExecution execution = command.importExecution();
    CommandIdempotencyRepository.IdempotencyRecord terminal =
        new CommandIdempotencyRepository.IdempotencyRecord(
            metadata.producer(),
            command.operationType(),
            metadata.idempotencyKey(),
            fingerprint,
            result.status() == ApplicationResult.Status.FAILED ? "FAILED" : "COMPLETED",
            result.status().name(),
            result.dataJson(),
            result.error() == null ? null : result.error().code(),
            result.error() == null
                ? null
                : CanonicalJson.write(Map.of("details", result.error().details())),
            aggregateId,
            aggregateVersion,
            execution == null ? null : execution.importId(),
            execution == null ? null : execution.planItemId(),
            execution == null ? null : execution.executionGeneration(),
            null,
            existing.map(CommandIdempotencyRepository.IdempotencyRecord::createdAt).orElse(now),
            now,
            now);
    if (idempotency.update(terminal) != 1) {
      throw new IllegalStateException("terminal idempotency update lost its row");
    }

    OutboundResultIntent resultIntent = ResultIntentFactory.create(
        metadata, command, result, aggregateId, aggregateVersion, now);
    OutboxIntentWriter.append(outbox, resultIntent);
    inbox.markApplied(CONSUMER, metadata.messageId(), now, now);
    appendAudit(metadata, command, aggregateId, aggregateVersion, command.operationType(), result, now);
    return CommandOutcome.terminal(result, List.of(), List.of(resultIntent));
  }

  private CommandOutcome emitStandaloneResult(
      CommandMetadata metadata,
      AuthorizationCommand command,
      ApplicationResult result,
      UUID aggregateId,
      Integer aggregateVersion,
      Instant now) {
    OutboundResultIntent resultIntent = ResultIntentFactory.create(
        metadata, command, result, aggregateId, aggregateVersion, now);
    OutboxIntentWriter.append(outbox, resultIntent);
    inbox.markApplied(CONSUMER, metadata.messageId(), now, now);
    appendAudit(metadata, command, aggregateId, aggregateVersion, command.operationType(), result, now);
    return CommandOutcome.terminal(result, List.of(), List.of(resultIntent));
  }

  private CommandIdempotencyRepository.IdempotencyRecord initialRecord(
      CommandMetadata metadata,
      AuthorizationCommand command,
      String fingerprint,
      Instant now) {
    ImportExecution execution = command.importExecution();
    return new CommandIdempotencyRepository.IdempotencyRecord(
        metadata.producer(),
        command.operationType(),
        metadata.idempotencyKey(),
        fingerprint,
        "DEFERRED",
        null,
        null,
        null,
        null,
        null,
        null,
        execution == null ? null : execution.importId(),
        execution == null ? null : execution.planItemId(),
        execution == null ? null : execution.executionGeneration(),
        execution == null ? null : "EVENT_PENDING",
        now,
        null,
        now);
  }

  private void ensureInbox(
      CommandMetadata metadata, AuthorizationCommand command, Instant now) {
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
  }

  private void appendAudit(
      CommandMetadata metadata,
      AuthorizationCommand command,
      UUID aggregateId,
      Integer aggregateVersion,
      String action,
      ApplicationResult result,
      Instant now) {
    ImportExecution execution = command.importExecution();
    audit.append(new AuthorizationAuditRepository.AuditEntry(
        UUID.randomUUID(),
        metadata.messageId(),
        metadata.producer(),
        command.operationType(),
        aggregateId,
        aggregateVersion,
        action,
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
