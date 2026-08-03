package com.estoma.users.application.port;

import java.util.UUID;

/**
 * Consumer Inbox. Key is {@code (consumerName, messageId)}, not {@code
 * messageId} alone (docs/system-contract.md §7). Must be the first
 * statement in the dispatcher's transaction: a failed insert (duplicate)
 * means the command is a no-op redelivery.
 */
public interface InboxPort {

  /**
   * @return {@code true} if this is the first time {@code messageId} has
   *     been seen by {@code consumerName} (the row was inserted); {@code
   *     false} if it was already processed (a duplicate delivery).
   */
  boolean tryInsert(String consumerName, UUID messageId, UUID correlationId);
}
