package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CommandIdempotencyRepository {
  IdempotencyDecision claim(String scope, String key, String fingerprint, UUID messageId, Instant now);
  boolean complete(String scope, String key, String outcomeJson, Instant completedAt);
  Optional<IdempotencyRecord> find(String scope, String key);
  record IdempotencyRecord(String scope, String key, String fingerprint, UUID messageId,
      String status, String outcomeJson, Instant createdAt, Instant completedAt) {}
  record IdempotencyDecision(boolean accepted, boolean replay, boolean conflict, IdempotencyRecord record) {}
}
