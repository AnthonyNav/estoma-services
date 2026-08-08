package com.estoma.communications.domain;

/**
 * A functional rejection against any of this service's aggregates
 * (Communication, InboxMessage, InboxVisit, EmailDelivery) -- one shared
 * exception type, since they all draw from the same {@link RejectionCode}
 * catalog (commands.md §2.1).
 *
 * <p>For durable commands, this is caught INSIDE {@code CommandDispatcher}'s
 * transaction and turned into a durable {@code Rejected} result. For the
 * {@code AppointmentCancelled} adapter (dispatched via {@code
 * ProjectionDispatcher}), only {@code COMMUNICATION_ALREADY_EXISTS} is
 * expected and treated as an idempotent no-op; any other code is a real bug
 * (system_contract.md "Internal Architecture"). In neither case may it be
 * allowed to unwind the dispatcher's transaction.
 */
public final class CommunicationsRejectedException extends RuntimeException {

  private final RejectionCode code;

  public CommunicationsRejectedException(RejectionCode code, String message) {
    super(message);
    this.code = code;
  }

  public RejectionCode code() {
    return code;
  }
}
