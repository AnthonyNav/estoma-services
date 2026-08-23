package com.estoma.washexecution.domain.execution;

public enum WashExecutionStatus {
  PENDING_ENTRY,
  ENTRY_REJECTED,
  PENDING_REASSIGNMENT,
  IN_PROGRESS,
  EXIT_SUBMITTED,
  COMPLETED,
  CANCELLED;

  public boolean terminal() {
    return this == ENTRY_REJECTED || this == COMPLETED || this == CANCELLED;
  }
}
