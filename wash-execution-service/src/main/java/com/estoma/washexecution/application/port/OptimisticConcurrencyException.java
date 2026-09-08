package com.estoma.washexecution.application.port;

/** Signals a write race that must roll back before the original command is retried. */
public final class OptimisticConcurrencyException extends RuntimeException {
  public OptimisticConcurrencyException() {
    super("VERSION_CONFLICT");
  }
}
