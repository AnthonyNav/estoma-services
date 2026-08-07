package com.estoma.users.application.handler;

import com.estoma.users.contract.MessageEnvelope;

/**
 * One handler per command {@code messageType}. Implementations perform
 * domain validation and persistence; they throw {@link
 * com.estoma.users.domain.PersonRejectedException} on any functional
 * rejection and let it propagate — {@code PersonCommandDispatcher}
 * catches it in exactly one place and turns it into a durable {@code
 * Rejected} result (docs/system-contract.md §7).
 */
public interface PersonCommandHandler {

  /** The {@code messageType} this handler is registered for (e.g. {@code MessageTypes.CREATE_PERSON}). */
  String commandMessageType();

  HandlerResult handle(MessageEnvelope envelope);
}
