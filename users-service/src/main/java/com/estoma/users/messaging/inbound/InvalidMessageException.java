package com.estoma.users.messaging.inbound;

/**
 * A message that is structurally or contractually invalid: malformed
 * envelope, wrong {@code targetService}, unsupported {@code
 * messageVersion}, unrecognized {@code messageType}, or an un-allow-listed
 * {@code producer}. Always a PERMANENT failure — straight to the DLQ, no
 * retry tier (docs/system-contract.md §7, messaging-architecture.md
 * §16: "mensajes inválidos... no se reintentan indefinidamente").
 */
public class InvalidMessageException extends RuntimeException {

  public InvalidMessageException(String message) {
    super(message);
  }
}
