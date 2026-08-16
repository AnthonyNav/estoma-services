package com.estoma.washadministration.infrastructure.messaging.outbound;

import com.estoma.washadministration.application.port.JsonCodec;
import com.estoma.washadministration.application.port.OutboxRepository;
import com.estoma.washadministration.application.port.OutboxRepository.OutboxMessage;
import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository;
import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository.RecoveryJob;
import com.estoma.washadministration.contracts.Actor;
import com.estoma.washadministration.contracts.query.GetAccountAuthorizationSnapshotRequest;
import com.estoma.washadministration.contracts.query.ListAccountAuthorizationSnapshotsPageRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuthorizationProjectionRecoveryCoordinator {

  private static final String SYSTEM_CODE = "LAVADO_ULTRASONICO";
  private static final String PRODUCER = "wash-administration";

  private final ProjectionRecoveryJobRepository jobs;
  private final OutboxRepository outbox;
  private final JsonCodec json;

  public AuthorizationProjectionRecoveryCoordinator(
      ProjectionRecoveryJobRepository jobs, OutboxRepository outbox, JsonCodec json) {
    this.jobs = jobs;
    this.outbox = outbox;
    this.json = json;
  }

  @Transactional
  public RecoveryJob requestEntity(UUID accountId) {
    if (accountId == null) {
      throw new IllegalArgumentException("accountId is required");
    }
    return jobs.createEntity(accountId, Instant.now());
  }

  @Transactional
  public RecoveryJob requestFull() {
    return jobs.createFull(Instant.now());
  }

  @Transactional
  public boolean processNext() {
    UUID claimToken = UUID.randomUUID();
    Optional<RecoveryJob> claimed = jobs.claimNextPending(claimToken, Instant.now().plusSeconds(30));
    if (claimed.isEmpty()) {
      return false;
    }
    RecoveryJob job = claimed.get();
    if (job.scope() == ProjectionRecoveryJobRepository.Scope.FULL
        && job.cursor() == null
        && job.queryAttempt() == 0) {
      jobs.clearAuthorizationProjection();
    }

    UUID queryMessageId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    String queryType;
    Object payload;
    if (job.scope() == ProjectionRecoveryJobRepository.Scope.ENTITY) {
      queryType = "GetAccountAuthorizationSnapshot";
      UUID accountId = accountId(job.entityKey());
      payload = new GetAccountAuthorizationSnapshotRequest(accountId, SYSTEM_CODE);
    } else {
      queryType = "ListAccountAuthorizationSnapshotsPage";
      payload = new ListAccountAuthorizationSnapshotsPageRequest(job.cursor(), 100, SYSTEM_CODE);
    }

    outbox.append(
        new OutboxMessage(
            queryMessageId,
            queryType,
            1,
            "QUERY",
            PRODUCER,
            "authorization",
            Instant.now(),
            operationId,
            null,
            null,
            null,
            correlationId,
            null,
            json.write(Actor.system(PRODUCER)),
            json.write(payload),
            null));
    if (!jobs.markWaiting(
        job.recoveryJobId(),
        claimToken,
        queryType,
        queryMessageId,
        operationId,
        correlationId,
        job.queryAttempt() + 1)) {
      throw new IllegalStateException("recovery job claim was lost");
    }
    return true;
  }

  @Transactional
  public int requeueExpiredResults(Instant cutoff) {
    return jobs.requeueExpiredWaiting(cutoff, "AUTHORIZATION_RECOVERY_RESULT_TIMEOUT");
  }

  private static UUID accountId(String entityKey) {
    if (entityKey == null || !entityKey.startsWith("account:")) {
      throw new IllegalArgumentException("invalid authorization recovery entity key");
    }
    return UUID.fromString(entityKey.substring("account:".length()));
  }
}
