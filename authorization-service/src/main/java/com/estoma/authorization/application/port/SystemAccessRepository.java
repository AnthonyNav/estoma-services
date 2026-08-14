package com.estoma.authorization.application.port;

import com.estoma.authorization.domain.model.SystemAccess;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SystemAccessRepository {

  Optional<SystemAccess> findById(UUID systemAccessId);

  Optional<SystemAccess> findByAccountIdAndSystemId(UUID accountId, UUID systemId);

  List<SystemAccess> findByAccountId(UUID accountId);

  void insert(SystemAccess access);

  int update(SystemAccess access, int expectedVersion);
}
