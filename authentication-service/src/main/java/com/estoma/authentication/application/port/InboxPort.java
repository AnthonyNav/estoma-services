package com.estoma.authentication.application.port;

import java.util.UUID;

/**
 * Consumer Inbox. Key is {@code (consumerName, messageId)}, not {@code
 * messageId} alone. Must be the first statement in a dispatcher's
 * transaction: a failed insert (duplicate) means the message is a no-op
 * redelivery. Used by durable command and event consumption; ephemeral RPC
 * never uses this port.
 */
public interface InboxPort {

  /**
   * @return {@code true} if this is the first time {@code messageId} has been seen by {@code
   *     consumerName} (the row was inserted); {@code false} if it was already processed.
   */
  boolean tryInsert(String consumerName, UUID messageId, UUID correlationId);
}
