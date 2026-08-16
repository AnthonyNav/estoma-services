package com.estoma.authorization.application.recovery;

import com.estoma.authorization.application.command.ApplicationResult;
import com.estoma.authorization.application.command.AuthorizationCommandDispatcher;
import com.estoma.authorization.application.command.CommandOutcome;
import com.estoma.authorization.application.command.CommandResultSupport;
import com.estoma.authorization.application.command.ImportExecution;
import com.estoma.authorization.application.port.AuthorizationAuditRepository;
import com.estoma.authorization.application.port.CommandIdempotencyRepository;
import com.estoma.authorization.application.port.DeferredCommandDecoder;
import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.support.CanonicalJson;
import com.estoma.authorization.application.support.OutboxIntentWriter;
import com.estoma.authorization.application.support.ResultIntentFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Re-evaluates the original durable Academic Command instead of creating another intention. */
public final class DeferredCommandRecoveryService {

  private static final String COMMAND_CONSUMER = "authorization-commands";
  private static final int DEFAULT_LIMIT = 500;

  private final ProcessedMessageRepository inbox;
  private final DeferredCommandDecoder decoder;
  private final AuthorizationCommandDispatcher dispatcher;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;
  private final AuthorizationAuditRepository audit;
  private final Clock clock;

  public DeferredCommandRecoveryService(
      ProcessedMessageRepository inbox,
      DeferredCommandDecoder decoder,
      AuthorizationCommandDispatcher dispatcher,
      CommandIdempotencyRepository idempotency,
      OutboxRepository outbox,
      AuthorizationAuditRepository audit,
      Clock clock) {
    this.inbox = inbox;
    this.decoder = decoder;
    this.dispatcher = dispatcher;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.audit = audit;
    this.clock = clock;
  }

  public List<CommandOutcome> replayForAccount(
      UUID accountId, String expectedPendingReason, boolean authoritativeNotFound) {
    List<CommandOutcome> outcomes = new ArrayList<>();
    for (ProcessedMessageRepository.ProcessedMessage message :
        inbox.findDeferredCommandsByAccountId(COMMAND_CONSUMER, accountId, DEFAULT_LIMIT)) {
      if (!expectedPendingReason.equals(message.deferredReason())) {
        continue;
      }
      DeferredCommandDecoder.DecodedCommand decoded = decoder.decode(message);
      outcomes.add(authoritativeNotFound
          ? resolveAuthoritativeAbsence(decoded)
          : dispatcher.replayDeferred(decoded.metadata(), decoded.command()));
    }
    return List.copyOf(outcomes);
  }

  public List<CommandOutcome> drainProjectionReconciliation() {
    List<CommandOutcome> outcomes = new ArrayList<>();
    for (ProcessedMessageRepository.ProcessedMessage message :
        inbox.findDeferred(COMMAND_CONSUMER, "COMMAND", DEFAULT_LIMIT)) {
      if (!"PROJECTION_RECONCILIATION".equals(message.deferredReason())) {
        continue;
      }
      DeferredCommandDecoder.DecodedCommand decoded = decoder.decode(message);
      outcomes.add(dispatcher.replayDeferred(decoded.metadata(), decoded.command()));
    }
    return List.copyOf(outcomes);
  }

  private CommandOutcome resolveAuthoritativeAbsence(
      DeferredCommandDecoder.DecodedCommand decoded) {
    var metadata = decoded.metadata();
    var command = decoded.command();
    Instant now = clock.instant();
    CommandIdempotencyRepository.IdempotencyRecord record = idempotency.find(
            metadata.producer(), command.operationType(), metadata.idempotencyKey())
        .orElseThrow(() -> new IllegalStateException("deferred idempotency record is missing"));
    if (!"DEFERRED".equals(record.status())) {
      inbox.markApplied(COMMAND_CONSUMER, metadata.messageId(), now, now);
      return CommandOutcome.duplicate();
    }

    String errorCode = "ACCOUNT_NOT_FOUND";
    String details = "authoritative Account recovery confirmed target absence";
    ImportExecution execution = command.importExecution();
    if (execution != null) {
      Optional<Integer> latest = idempotency.findLatestExecutionGeneration(
          metadata.producer(), execution.importId(), execution.planItemId());
      if (latest.isPresent() && execution.executionGeneration() < latest.get()) {
        errorCode = "STALE_EXECUTION_GENERATION";
        details = "deferred execution generation is older than the latest observed generation";
      }
    }
    ApplicationResult result = CommandResultSupport.ensureImportExecutionData(
        command, ApplicationResult.rejected(errorCode, details));
    var resultIntent = ResultIntentFactory.create(
        metadata, command, result, null, null, now);
    OutboxIntentWriter.append(outbox, resultIntent);

    CommandIdempotencyRepository.IdempotencyRecord completed =
        new CommandIdempotencyRepository.IdempotencyRecord(
            record.producer(),
            record.operationType(),
            record.idempotencyKey(),
            record.requestFingerprint(),
            "COMPLETED",
            "REJECTED",
            result.dataJson(),
            errorCode,
            CanonicalJson.write(Map.of("details", details)),
            null,
            null,
            record.importId(),
            record.planItemId(),
            record.executionGeneration(),
            null,
            record.createdAt(),
            now,
            now);
    if (idempotency.update(completed) != 1) {
      throw new IllegalStateException("deferred idempotency record changed concurrently");
    }
    inbox.markApplied(COMMAND_CONSUMER, metadata.messageId(), now, now);
    audit.append(new AuthorizationAuditRepository.AuditEntry(
        UUID.randomUUID(),
        metadata.messageId(),
        metadata.producer(),
        command.operationType(),
        command.targetAccountId(),
        null,
        "DEFERRED_RESOLVED_NOT_FOUND",
        metadata.actor(),
        execution == null ? null : execution.authorizedByAccountId(),
        execution == null ? null : execution.importId(),
        metadata.correlationId(),
        metadata.causationId(),
        metadata.operationId(),
        metadata.idempotencyKey(),
        "REJECTED",
        errorCode,
        details,
        now));
    return CommandOutcome.terminal(result, List.of(), List.of(resultIntent));
  }
}
