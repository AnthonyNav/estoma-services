package com.estoma.platformbff.application.port;

import com.estoma.platformbff.domain.messaging.OutboxMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {
  void insert(OutboxMessage message);

  List<OutboxMessage> claimBatch(String workerId, Instant now, int limit);

  boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt);

  boolean markFailed(UUID messageId, UUID claimToken, String errorCode, Instant nextAttemptAt);
}
