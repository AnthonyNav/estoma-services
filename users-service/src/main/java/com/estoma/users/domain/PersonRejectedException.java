package com.estoma.users.domain;

/**
 * A functional rejection of a command against a {@link Person}. This is
 * caught INSIDE the dispatcher's transaction and turned into a durable
 * {@code <Command>Rejected} result — it must never be allowed to unwind
 * the transaction that contains the Consumer Inbox insert, or the message
 * would be redelivered forever and eventually reach the DLQ, contradicting
 * commands.md:285. See docs/system-contract.md §7.
 */
public final class PersonRejectedException extends RuntimeException {

  private final RejectionCode code;

  public PersonRejectedException(RejectionCode code, String message) {
    super(message);
    this.code = code;
  }

  public RejectionCode code() {
    return code;
  }
}
