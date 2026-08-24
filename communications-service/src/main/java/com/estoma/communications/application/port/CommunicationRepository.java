package com.estoma.communications.application.port;

import com.estoma.communications.domain.communication.Communication;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the Communication aggregate. */
public interface CommunicationRepository {

  Optional<Communication> findById(UUID communicationId);
  default Optional<Communication> findByIdForUpdate(UUID communicationId) { return findById(communicationId); }
  Optional<Communication> findBySourceAndIdempotency(String sourceService, String idempotencyKey);

  /** Compatibility bridge for older in-process UUID callers; canonical durable type is String. */
  default Optional<Communication> findBySourceAndIdempotency(String sourceService, UUID idempotencyKey) {
    return findBySourceAndIdempotency(sourceService, idempotencyKey == null ? null : idempotencyKey.toString());
  }

  Optional<Communication> findLegacyByOrigin(Communication communication);

  Communication insert(Communication communication);

  int update(Communication communication);
}
