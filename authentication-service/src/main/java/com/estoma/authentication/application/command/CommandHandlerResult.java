package com.estoma.authentication.application.command;

import com.estoma.authentication.application.EventToAppend;
import java.util.List;
import java.util.UUID;

/**
 * What a successful {@link CommandHandler#handle} produces. {@code
 * aggregateId}/{@code aggregateVersion} may both be null (only {@code
 * RequestPasswordRecovery}, which never reveals whether an account was
 * found). {@code events} may be empty (RequestPasswordRecovery when no
 * account matched) or carry more than one (DeactivateAccount:
 * AccountDeactivated + AllAccountSessionsRevoked, in the same transaction).
 */
public record CommandHandlerResult(UUID aggregateId, Integer aggregateVersion, List<EventToAppend> events) {

  public static CommandHandlerResult of(UUID aggregateId, int aggregateVersion, EventToAppend... events) {
    return new CommandHandlerResult(aggregateId, aggregateVersion, List.of(events));
  }
}
