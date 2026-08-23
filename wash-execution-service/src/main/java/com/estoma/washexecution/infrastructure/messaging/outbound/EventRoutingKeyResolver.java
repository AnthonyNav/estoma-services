package com.estoma.washexecution.infrastructure.messaging.outbound;

import org.springframework.stereotype.Component;

@Component
public final class EventRoutingKeyResolver {
  public String resolve(String messageType) {
    return switch (messageType) {
      case "WashArrivalRegistered" -> "wash-execution.execution.arrival-registered";
      case "WashEntryAuthorized" -> "wash-execution.entry.authorized";
      case "WashEntryRejected" -> "wash-execution.entry.rejected";
      case "WashResourceAssigned" -> "wash-execution.resource.assigned";
      case "WashResourceReassigned" -> "wash-execution.resource.reassigned";
      case "WashExecutionStarted" -> "wash-execution.execution.started";
      case "WashExecutionPendingReassignment" -> "wash-execution.execution.pending-reassignment";
      case "OperationalResourceDisabled" -> "wash-execution.operational-resource.disabled";
      case "OperationalResourceRestored" -> "wash-execution.operational-resource.restored";
      case "WashExitSubmitted" -> "wash-execution.exit.submitted";
      case "WashExecutionCompleted" -> "wash-execution.execution.completed";
      case "WashExecutionCancelled" -> "wash-execution.execution.cancelled";
      case "CleaningPeriodStarted" -> "wash-execution.cleaning-period.started";
      case "CleaningPeriodCompleted" -> "wash-execution.cleaning-period.completed";
      default -> throw new IllegalArgumentException("UNSUPPORTED_EVENT_ROUTING:" + messageType);
    };
  }
}
