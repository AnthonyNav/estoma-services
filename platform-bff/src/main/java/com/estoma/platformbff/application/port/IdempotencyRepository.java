package com.estoma.platformbff.application.port;

import com.estoma.platformbff.domain.idempotency.IdempotencyRecord;
import java.util.Optional;

public interface IdempotencyRepository {
  void insert(IdempotencyRecord record);

  Optional<IdempotencyRecord> findByScopeKey(String scopeKey);
}
