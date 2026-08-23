package com.estoma.platformbff.application.operations;

import com.estoma.platformbff.application.commands.CommandRegistry;
import com.estoma.platformbff.application.port.ClientOperationRepository;
import com.estoma.platformbff.application.port.IdempotencyRepository;
import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.application.port.OutboxRepository;
import com.estoma.platformbff.application.port.StructuredDataComparator;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.contracts.messaging.OwnerRoute;
import com.estoma.platformbff.domain.messaging.InboxMessage;
import com.estoma.platformbff.domain.operation.ClientOperation;
import com.estoma.platformbff.domain.operation.OperationStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResultApplicationService {
  private static final String CONSUMER_NAME = "bff-command-results";

  private final ClientOperationRepository operations;
  private final InboxRepository inbox;
  private final OutboxRepository outbox;
  private final IdempotencyRepository idempotency;
  private final CommandRegistry registry;
  private final StructuredDataComparator dataComparator;
  private final long idempotencyRetentionHours;

  public ResultApplicationService(
      ClientOperationRepository operations,
      InboxRepository inbox,
      OutboxRepository outbox,
      IdempotencyRepository idempotency,
      CommandRegistry registry,
      StructuredDataComparator dataComparator,
      @Value("${platform.retention.idempotency-hours:24}") long idempotencyRetentionHours) {
    if (idempotencyRetentionHours < 24L) {
      throw new IllegalArgumentException("idempotency retention cannot be below the V1 24h minimum");
    }
    this.operations = operations;
    this.inbox = inbox;
    this.outbox = outbox;
    this.idempotency = idempotency;
    this.registry = registry;
    this.dataComparator = dataComparator;
    this.idempotencyRetentionHours = idempotencyRetentionHours;
  }

  @Transactional
  public boolean apply(IncomingResult result) {
    if (!validEnvelope(result)) {
      return false;
    }

    ClientOperation operation = operations.findById(result.operationId()).orElse(null);
    if (operation == null
        || !operation.correlationId().equals(result.correlationId())
        || !operation.ownerLogicalComponentId().equals(result.producer())) {
      return false;
    }

    OwnerRoute route = routeFor(operation.commandType());
    if (route == null
        || !route.logicalComponentId().equals(operation.ownerLogicalComponentId())
        || !route.logicalComponentId().equals(result.producer())
        || !route.acceptsResult(result.messageType(), result.messageVersion())) {
      return false;
    }

    var commandMessageId = outbox.findMessageIdByOperationId(operation.operationId()).orElse(null);
    if (commandMessageId == null || !commandMessageId.equals(result.causationId())) {
      return false;
    }

    OperationStatus terminalStatus;
    try {
      terminalStatus = OperationStatus.valueOf(result.status());
    } catch (RuntimeException invalidStatus) {
      return false;
    }
    if (!terminalStatus.terminal()) {
      return false;
    }

    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    if (operation.status().terminal()) {
      if (!terminalMatches(operation, terminalStatus, result)) {
        return false;
      }
      if (!recordInboxIfNew(result, now)) {
        return true;
      }
      ensureIdempotencyExpiry(operation.operationId(), now);
      return true;
    }

    if (!recordInboxIfNew(result, now)) {
      return true;
    }

    var terminal = operation.withTerminalResult(
        terminalStatus, result.dataJson(), result.errorCode(), result.errorDetailsJson(), now);
    if (operations.completeIfPending(operation.operationId(), terminal, now)) {
      ensureIdempotencyExpiry(operation.operationId(), now);
      return true;
    }

    ClientOperation winner = operations.findById(operation.operationId())
        .orElseThrow(() -> new IllegalStateException("client operation disappeared during terminalization"));
    if (!winner.status().terminal() || !terminalMatches(winner, terminalStatus, result)) {
      throw new ConflictingTerminalResultException(
          "another terminal Result won with a different observable outcome");
    }
    ensureIdempotencyExpiry(operation.operationId(), now);
    return true;
  }

  private boolean recordInboxIfNew(IncomingResult result, Instant now) {
    return inbox.recordIfNew(new InboxMessage(
        CONSUMER_NAME,
        result.messageId(),
        MessageKind.RESULT.name(),
        result.messageType(),
        result.messageVersion(),
        result.producer(),
        result.correlationId(),
        result.causationId(),
        result.operationId(),
        result.aggregateId(),
        result.aggregateVersion(),
        now,
        now));
  }

  private void ensureIdempotencyExpiry(java.util.UUID operationId, Instant terminalAt) {
    if (!idempotency.ensureExpiresAt(
        operationId, terminalAt.plus(idempotencyRetentionHours, ChronoUnit.HOURS))) {
      throw new IllegalStateException("terminal client operation has no idempotency record");
    }
  }

  private boolean terminalMatches(
      ClientOperation operation, OperationStatus expectedStatus, IncomingResult result) {
    return operation.status() == expectedStatus
        && java.util.Objects.equals(operation.errorCode(), result.errorCode())
        && dataComparator.equivalent(operation.resultData(), result.dataJson())
        && dataComparator.equivalent(operation.errorDetails(), result.errorDetailsJson());
  }

  private static boolean validEnvelope(IncomingResult result) {
    return result != null
        && result.messageId() != null
        && result.messageKind() == MessageKind.RESULT
        && result.messageType() != null
        && result.messageVersion() > 0
        && result.producer() != null
        && result.occurredAt() != null
        && result.correlationId() != null
        && result.causationId() != null
        && result.operationId() != null
        && result.status() != null;
  }

  private OwnerRoute routeFor(String commandType) {
    try {
      return registry.require(commandType);
    } catch (IllegalArgumentException unknownCommand) {
      return null;
    }
  }

  public static class ConflictingTerminalResultException extends RuntimeException {
    public ConflictingTerminalResultException(String message) {
      super(message);
    }
  }
}
