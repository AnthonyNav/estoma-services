package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthorizationProjectionRepository {
  void upsertAccountRole(AccountRoleProjection projection);
  void upsertSystemAccess(SystemAccessProjection projection);
  Optional<AccountRoleProjection> findAccountRole(UUID accountId);
  Optional<SystemAccessProjection> findSystemAccess(UUID accountId, String systemCode);
  boolean hasAccess(UUID accountId, String roleCode, String systemCode);
  boolean reliable();

  record AccountRoleProjection(UUID accountId, UUID roleId, String roleCode,
      long sourceVersion, Instant updatedAt, boolean reliable) {}

  record SystemAccessProjection(UUID systemAccessId, UUID accountId, UUID systemId,
      String systemCode, String accessStatus, long sourceVersion, Instant updatedAt,
      boolean reliable) {}
}
