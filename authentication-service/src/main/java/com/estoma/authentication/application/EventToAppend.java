package com.estoma.authentication.application;

/** One non-sensitive event a handler wants appended to the Outbox alongside its result, if any. */
public record EventToAppend(
    String messageType,
    Object payload,
    java.util.UUID aggregateId,
    Integer aggregateVersion,
    boolean inheritHandlerAggregate) {

  public EventToAppend(String messageType, Object payload) {
    this(messageType, payload, null, null, true);
  }

  public EventToAppend(String messageType, Object payload, java.util.UUID aggregateId, Integer aggregateVersion) {
    this(messageType, payload, aggregateId, aggregateVersion, false);
  }
}
