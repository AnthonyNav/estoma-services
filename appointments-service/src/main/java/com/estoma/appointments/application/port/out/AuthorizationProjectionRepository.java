package com.estoma.appointments.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface AuthorizationProjectionRepository {
  void upsertAccountRole(UUID accountId, String role, long sourceVersion);
  void upsertSystemAccess(UUID accountId, String systemCode, boolean granted, long sourceVersion);
  boolean hasAccess(UUID accountId, String role, String systemCode);
  boolean reliable();
  record AuthorizationProjection(UUID accountId, String role, String systemCode, boolean granted, long sourceVersion) {}
}
