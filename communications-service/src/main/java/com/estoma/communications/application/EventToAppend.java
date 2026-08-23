package com.estoma.communications.application;

import java.util.UUID;

/** One non-sensitive event a handler wants appended to the Outbox alongside its result, if any. */
public record EventToAppend(String messageType, Object payload, UUID aggregateId, Integer aggregateVersion) {

  public EventToAppend(String messageType, Object payload) {
    this(messageType, payload, null, null);
  }

  public EventToAppend onAggregate(UUID aggregateId, Integer aggregateVersion) {
    return new EventToAppend(messageType, payload, aggregateId, aggregateVersion);
  }
}
