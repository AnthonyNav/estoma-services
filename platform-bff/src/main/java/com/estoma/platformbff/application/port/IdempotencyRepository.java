package com.estoma.platformbff.application.port;

import com.estoma.platformbff.domain.idempotency.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository {
  void insert(IdempotencyRecord record);

  boolean insertIfAbsent(IdempotencyRecord record);

  Optional<IdempotencyRecord> findByScopeKey(String scopeKey);

  Optional<IdempotencyRecord> findByLogicalScope(
      UUID actorAccountId, String publicOperation, String idempotencyKey);

  boolean ensureExpiresAt(UUID operationId, Instant expiresAt);
}
