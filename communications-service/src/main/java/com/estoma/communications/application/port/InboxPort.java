package com.estoma.communications.application.port;

import java.util.UUID;

/**
 * Consumer Inbox. Key is {@code (consumerName, messageId)}, not {@code
 * messageId} alone: {@code communications-service} has more than one
 * logically independent consumer from day one (durable commands,
 * recipient-projection events, the AppointmentCancelled adapter), each
 * with its own {@code consumerName}. Must be the first statement in a
 * dispatcher's transaction: a failed insert (duplicate) means the message
 * is a no-op redelivery.
 */
public interface InboxPort {

  /**
   * @return {@code true} if this is the first time {@code messageId} has been seen by {@code
   *     consumerName} (the row was inserted); {@code false} if it was already processed.
   */
  boolean tryInsert(String consumerName, UUID messageId, UUID correlationId);
}
