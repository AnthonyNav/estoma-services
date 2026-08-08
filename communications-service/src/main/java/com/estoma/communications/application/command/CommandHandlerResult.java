package com.estoma.communications.application.command;

import com.estoma.communications.application.EventToAppend;
import java.util.List;
import java.util.UUID;

/** What a successful {@link CommandHandler#handle} produces. */
public record CommandHandlerResult(UUID aggregateId, Integer aggregateVersion, List<EventToAppend> events) {

  public static CommandHandlerResult of(UUID aggregateId, Integer aggregateVersion, EventToAppend... events) {
    return new CommandHandlerResult(aggregateId, aggregateVersion, List.of(events));
  }
}
