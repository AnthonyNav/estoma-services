package com.estoma.users.contract;

/**
 * The message kinds the platform envelope may carry
 * (messaging-architecture.md §8). {@code users-service} receives {@code
 * COMMAND} and {@code QUERY}, and publishes only {@code RESULT} and
 * {@code EVENT} (a Query's response is a {@code RESULT}, same as a
 * Command's).
 */
public enum MessageKind {
  COMMAND,
  QUERY,
  RESULT,
  EVENT
}
