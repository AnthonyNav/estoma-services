package com.estoma.authentication.infrastructure.messaging.inbound;

/**
 * A message that is structurally or contractually invalid: malformed
 * envelope, unsupported {@code messageVersion}, wrong {@code messageKind},
 * or an unrecognized {@code messageType}. Always a PERMANENT failure --
 * straight to the DLQ, no retry tier (messaging-architecture.md §12:
 * "mensajes inválidos... no se reintentan indefinidamente").
 */
public class InvalidMessageException extends RuntimeException {

  public InvalidMessageException(String message) {
    super(message);
  }
}
