package com.estoma.communications.application.port;

/**
 * Transactional Outbox write side, used by dispatchers and the email
 * delivery worker inside their own transaction. The read/claim/publish
 * side (the relay) belongs to the messaging tramo (Phase 3), not to this
 * port.
 */
public interface OutboxPort {

  void append(OutboxAppendRequest request);
}
