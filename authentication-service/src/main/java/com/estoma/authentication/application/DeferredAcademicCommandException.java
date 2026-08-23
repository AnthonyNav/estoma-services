package com.estoma.authentication.application;

/**
 * Non-terminal coordinated outcome: the Academic intention remains durable under its idempotency
 * key and is re-evaluated on the next same-generation retry after its prerequisite projection
 * converges. No terminal Result is produced while this exception is active.
 */
public final class DeferredAcademicCommandException extends RuntimeException {

  private final String pendingReason;

  public DeferredAcademicCommandException(String pendingReason) {
    super(pendingReason);
    this.pendingReason = pendingReason;
  }

  public String pendingReason() {
    return pendingReason;
  }
}
