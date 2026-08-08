package com.estoma.authentication.application;

/** One non-sensitive event a handler wants appended to the Outbox alongside its result, if any. */
public record EventToAppend(String messageType, Object payload) {}
