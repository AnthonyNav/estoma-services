package com.estoma.communications.application.port;

import com.estoma.communications.domain.communication.Communication;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link Communication}.
 *
 * @see com.estoma.communications.infrastructure.persistence for the {@code
 *     Propagation.NESTED} + {@code DuplicateKeyException} translation
 *     rationale.
 */
public interface CommunicationRepository {

  Optional<Communication> findById(UUID communicationId);
  Optional<Communication> findBySourceAndIdempotency(String sourceService, UUID idempotencyKey);
  Optional<Communication> findLegacyByOrigin(Communication communication);

  /**
   * @throws com.estoma.communications.domain.CommunicationsRejectedException with {@code
   *     COMMUNICATION_ALREADY_EXISTS} if the idempotency key ({@code source_service +
   *     source_reference_type + source_reference_id + recipient_account_id + system_code +
   *     communication_type}) is already taken (commands.md §6).
   */
  void insert(Communication communication);

  void update(Communication communication);
}
