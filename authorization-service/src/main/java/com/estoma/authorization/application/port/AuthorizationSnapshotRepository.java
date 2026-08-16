package com.estoma.authorization.application.port;

import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemAccessStatus;
import com.estoma.authorization.domain.model.SystemCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read port used only by authoritative recovery snapshot Queries. */
public interface AuthorizationSnapshotRepository {

  Optional<AccountAuthorizationSnapshot> find(UUID accountId, SystemCode systemCode);

  SnapshotPage list(String cursor, int limit, SystemCode systemCode);

  record RoleSnapshot(RoleCode roleCode, int roleVersion) {}

  record AccessSnapshot(
      UUID systemAccessId,
      UUID systemId,
      SystemCode systemCode,
      SystemAccessStatus status,
      int accessVersion) {}

  record AccountAuthorizationSnapshot(
      UUID accountId, RoleSnapshot role, List<AccessSnapshot> systemAccesses) {
    public AccountAuthorizationSnapshot {
      systemAccesses = List.copyOf(systemAccesses == null ? List.of() : systemAccesses);
    }
  }

  record SnapshotPage(
      List<AccountAuthorizationSnapshot> items, String nextCursor, boolean hasMore) {
    public SnapshotPage {
      items = List.copyOf(items == null ? List.of() : items);
    }
  }
}
