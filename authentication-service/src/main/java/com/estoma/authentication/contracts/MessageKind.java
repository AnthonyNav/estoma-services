package com.estoma.authentication.contracts;

/**
 * The three message kinds {@link MessageEnvelope} may carry
 * (messaging-architecture.md §8). {@code authentication-service} receives
 * COMMAND and EVENT, and publishes RESULT and EVENT. Ephemeral security RPC
 * (`platform.security.rpc` / Direct Reply-To) does not use this envelope at
 * all -- see the {@code contracts.rpc} package.
 */
public enum MessageKind {
  COMMAND,
  RESULT,
  EVENT
}
