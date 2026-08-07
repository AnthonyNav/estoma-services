package com.estoma.users.contract;

/**
 * The three message kinds the platform envelope may carry
 * (messaging-architecture.md §8). {@code users-service} receives only
 * {@code COMMAND} and publishes only {@code RESULT} and {@code EVENT}.
 */
public enum MessageKind {
  COMMAND,
  RESULT,
  EVENT
}
