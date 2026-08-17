package com.estoma.authorization.application.port;

import com.estoma.authorization.domain.model.ActorRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxRepository {

  void append(OutboxMessage message);

  Optional<OutboxMessage> findById(UUID messageId);

  List<OutboxMessage> claimBatch(int limit, UUID claimToken, Instant leaseUntil, Instant now);

  boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt);

  boolean releaseForRetry(
      UUID messageId,
      UUID claimToken,
      Instant availableAt,
      String errorCode,
      String errorDetail);

  boolean markFailed(UUID messageId, UUID claimToken, String errorCode, String errorDetail);

  int reclaimExpiredLeases(Instant now);

  Instant oldestPendingAvailableAt();

  boolean hasFailedMessages();

  boolean hasExpiredProcessing(Instant now);

  record OutboxMessage(
      UUID messageId,
      String messageType,
      int messageVersion,
      String messageKind,
      String producer,
      String routingKey,
      UUID operationId,
      String idempotencyKey,
      ActorRef actor,
      UUID aggregateId,
      Integer aggregateVersion,
      UUID correlationId,
      UUID causationId,
      String payloadJson,
      String status,
      int attempts,
      Instant occurredAt,
      Instant availableAt,
      Instant publishedAt,
      String lastErrorCode,
      String lastErrorDetail,
      UUID claimToken,
      Instant claimedAt,
      Instant leaseUntil) {}
}
