package com.estoma.communications.application.port;

import java.time.Instant;
import java.util.UUID;

public interface CommunicationAdminActionRepository {
  void record(UUID actionId, UUID commandMessageId, UUID emailDeliveryId, String action, Instant createdAt);

  default void record(UUID actionId, UUID commandMessageId, UUID emailDeliveryId, String action,
      String actorService, String reason, String context, Integer resultingRetryCycle, boolean duplicateRisk, Instant createdAt) {
    record(actionId, commandMessageId, emailDeliveryId, action, createdAt);
  }
}
