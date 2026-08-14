package com.estoma.authorization.application.port;

import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountStatusProjectionRepository {

  Optional<AccountStatusProjection> findByAccountId(UUID accountId);

  int upsertIfNewer(AccountStatusProjection projection);

  record AccountStatusProjection(
      UUID accountId,
      UUID personId,
      LoginType loginType,
      ProjectedStatus status,
      int sourceVersion,
      Instant updatedAt) {}
}
