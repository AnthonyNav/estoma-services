package com.estoma.platformbff.application.port;

import com.estoma.platformbff.domain.projection.RecoveryQuery;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProjectionRecoveryQueryRepository {
  void insert(RecoveryQuery query);

  Optional<RecoveryQuery> findById(UUID messageId);

  Optional<RecoveryQuery> claimNext(String workerId, Instant now);

  boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt);

  boolean markFailed(UUID messageId, UUID claimToken, String errorCode, Instant nextAttemptAt);
}
