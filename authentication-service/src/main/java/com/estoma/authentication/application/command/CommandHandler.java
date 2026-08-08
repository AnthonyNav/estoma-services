package com.estoma.authentication.application.command;

import com.estoma.authentication.contracts.MessageEnvelope;

/**
 * One handler per durable command {@code messageType}. Implementations
 * perform domain validation and persistence; they throw {@link
 * com.estoma.authentication.domain.AuthenticationRejectedException} on any
 * functional rejection and let it propagate -- {@code CommandDispatcher}
 * catches it in exactly one place and turns it into a durable {@code
 * Rejected} result.
 */
public interface CommandHandler {

  /** The {@code messageType} this handler is registered for (e.g. {@code AuthenticationCommand.CREATE_ACCOUNT.wireName()}). */
  String commandMessageType();

  CommandHandlerResult handle(MessageEnvelope envelope);
}
