package com.estoma.communications.application.projection;

import com.estoma.communications.application.EventToAppend;
import java.util.List;

/** What a successful {@link ProjectionHandler#handle} produces: zero or more non-sensitive events to append to the Outbox. */
public record ProjectionHandlerResult(List<EventToAppend> events) {

  public static final ProjectionHandlerResult NONE = new ProjectionHandlerResult(List.of());

  public static ProjectionHandlerResult of(EventToAppend... events) {
    return new ProjectionHandlerResult(List.of(events));
  }
}
