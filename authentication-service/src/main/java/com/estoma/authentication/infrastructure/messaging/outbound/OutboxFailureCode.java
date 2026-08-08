package com.estoma.authentication.infrastructure.messaging.outbound;

/**
 * Technical, sanitized failure codes the Outbox pipeline can log --
 * never a payload, a stack trace, or {@code exception.getMessage()}, just
 * one of these constants (system_contract.md "Observability Parameters").
 */
public enum OutboxFailureCode {
  PUBLISH_NACK,
  PUBLISH_TIMEOUT,
  PUBLISH_RETURNED,
  PUBLISH_CONNECTION_FAILURE,
  PUBLISH_INTERRUPTED,
  PUBLISH_UNEXPECTED_ERROR
}
