package com.estoma.communications.contracts;

/**
 * The message kinds {@link MessageEnvelope} may carry
 * (messaging-architecture.md §8). {@code communications-service} receives
 * COMMAND and EVENT, and publishes RESULT and EVENT. This service has no
 * ephemeral Security RPC surface (that pattern is scoped exclusively to
 * {@code authentication-service}, messaging-architecture.md §3), so unlike
 * {@code authentication-service}'s equivalent enum, there is no RPC-related
 * value here.
 */
public enum MessageKind {
  COMMAND,
  QUERY,
  RESULT,
  EVENT
}
