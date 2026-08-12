package com.estoma.washadministration.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthorizationAccessProjectionRepository {

  Optional<AccessProjection> findByAccountId(UUID accountId, String systemCode);

  int upsert(UUID accountId, String systemCode, String accessStatus, int sourceVersion, Instant updatedAt);

  record AccessProjection(
      UUID accountId, String systemCode, String accessStatus, int sourceVersion, Instant updatedAt) {}
}
