package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessageKind;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class EnvelopeValidator {
  private static final Set<String> COMMAND_TYPES = Set.of(
      "RegisterWashArrival", "DecideWashEntry", "ResolvePendingReassignment",
      "DisableOperationalResource", "RestoreOperationalResource", "SubmitWashExit",
      "CompleteWashExecution", "CancelWashExecutionByClinic");
  private static final Set<String> QUERY_TYPES = Set.of(
      "GetWashExecutionIntegrationContext", "ListWashExecutionIntegrationContextsPage");

  public void validateCommand(MessageEnvelope envelope) {
    common(envelope, MessageKind.COMMAND);
    if (!COMMAND_TYPES.contains(envelope.messageType())) invalid("UNSUPPORTED_COMMAND");
    if (envelope.actor() == null || envelope.operationId() == null || envelope.idempotencyKey() == null
        || envelope.idempotencyKey().isBlank()) invalid("INVALID_COMMAND_ENVELOPE");
  }

  public void validateEvent(MessageEnvelope envelope) {
    common(envelope, MessageKind.EVENT);
    if (envelope.aggregateId() == null || envelope.aggregateVersion() == null || envelope.aggregateVersion() < 1) {
      invalid("INVALID_EVENT_ENVELOPE");
    }
  }

  public void validateResult(MessageEnvelope envelope) {
    common(envelope, MessageKind.RESULT);
    if (envelope.causationId() == null) invalid("INVALID_RESULT_ENVELOPE");
  }

  public void validateQuery(MessageEnvelope envelope) {
    common(envelope, MessageKind.QUERY);
    if (!QUERY_TYPES.contains(envelope.messageType())) invalid("UNSUPPORTED_QUERY");
    if (envelope.operationId() == null) invalid("INVALID_QUERY_ENVELOPE");
  }

  private static void common(MessageEnvelope envelope, MessageKind kind) {
    if (envelope == null || envelope.messageId() == null || envelope.messageVersion() != 1
        || envelope.messageKind() != kind || envelope.producer() == null || envelope.producer().isBlank()
        || envelope.occurredAt() == null || envelope.correlationId() == null || envelope.payload() == null) {
      invalid("INVALID_MESSAGE_ENVELOPE");
    }
  }

  private static void invalid(String code) { throw new IllegalArgumentException(code); }
}
