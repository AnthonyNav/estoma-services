package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedMessageRepository {
  boolean tryClaim(UUID messageId, String messageType, Instant receivedAt);
  boolean markApplied(UUID messageId, Instant appliedAt);
  boolean markDeferred(UUID messageId, String reason, Instant deferredAt);
  boolean wasProcessed(UUID messageId);
}
