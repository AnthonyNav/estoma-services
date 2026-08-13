package com.estoma.washadministration.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthorizationRoleProjectionRepository {

  Optional<RoleProjection> findByAccountId(UUID accountId);

  int upsert(UUID accountId, String roleCode, int sourceVersion, Instant updatedAt);

  record RoleProjection(UUID accountId, String roleCode, int sourceVersion, Instant updatedAt) {}
}
