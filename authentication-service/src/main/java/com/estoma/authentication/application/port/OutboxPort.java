package com.estoma.authentication.application.port;

/**
 * Transactional Outbox write side, used by dispatchers inside their
 * transaction. The read/claim/publish side (the relay) belongs to the
 * messaging tramo, not to this port.
 */
public interface OutboxPort {

  void append(OutboxAppendRequest request);
}
