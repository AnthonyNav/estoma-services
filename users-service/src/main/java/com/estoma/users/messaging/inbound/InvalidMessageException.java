package com.estoma.users.messaging.inbound;

/**
 * A message that is structurally or contractually invalid: malformed
 * envelope, missing/blank {@code idempotencyKey}, unsupported {@code
 * messageVersion}, unrecognized {@code messageType}, an un-allow-listed
 * {@code producer}, or an actor that does not match what the operation
 * requires. Always a PERMANENT failure — straight to the DLQ, no retry
 * tier (docs/system-contract.md §7, messaging-architecture.md §16:
 * "mensajes inválidos... no se reintentan indefinidamente"). Contrast with
 * a functional rejection such as {@code INVALID_MESSAGE} for an
 * {@code aggregateId}/payload mismatch on an otherwise well-formed command
 * — that is a controlled {@code Rejected} result, not a DLQ'd message
 * (commands.md §2.1, §3).
 */
public class InvalidMessageException extends RuntimeException {

  public InvalidMessageException(String message) {
    super(message);
  }
}
