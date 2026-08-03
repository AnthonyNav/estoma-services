package com.estoma.users.application.port;

/**
 * Transactional Outbox write side, used by command handlers inside the
 * dispatcher's transaction. The read/claim/publish side belongs to {@code
 * OutboxRelay} (messaging.outbound), not to this port.
 */
public interface OutboxPort {

  void append(OutboxAppendRequest request);
}
