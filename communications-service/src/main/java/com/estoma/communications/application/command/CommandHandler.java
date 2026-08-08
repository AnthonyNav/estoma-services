package com.estoma.communications.application.command;

import com.estoma.communications.contracts.MessageEnvelope;

/**
 * One handler per durable command {@code messageType}. Implementations
 * perform domain validation and persistence; they throw {@link
 * com.estoma.communications.domain.CommunicationsRejectedException} on any
 * functional rejection and let it propagate -- {@code CommandDispatcher}
 * catches it in exactly one place and turns it into a durable {@code
 * Rejected} result.
 */
public interface CommandHandler {

  /** The {@code messageType} this handler is registered for (e.g. {@code CommunicationsCommand.REQUEST_COMMUNICATION.wireName()}). */
  String commandMessageType();

  CommandHandlerResult handle(MessageEnvelope envelope);
}
