package com.estoma.platformbff.application.projection;

import static com.estoma.platformbff.application.projection.ProjectionPayloads.number;
import static com.estoma.platformbff.application.projection.ProjectionPayloads.text;
import static com.estoma.platformbff.application.projection.ProjectionPayloads.uuid;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.port.AccountPresentationRecoveryPort;
import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.application.port.ProjectionRecoveryJobRepository;
import com.estoma.platformbff.application.port.ProjectionRecoveryQueryRepository;
import com.estoma.platformbff.application.port.ProjectionStatusRepository;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.domain.messaging.InboxMessage;
import com.estoma.platformbff.domain.projection.ProjectionState;
import com.estoma.platformbff.domain.projection.ProjectionStatus;
import com.estoma.platformbff.domain.projection.RecoveryJob;
import com.estoma.platformbff.domain.projection.RecoveryQuery;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rebuilds the non-secret Authentication Account Presentation read model from owner snapshots. */
@Service
public class AccountPresentationRecoveryService {
  private static final String PROJECTION = "account-presentation";
  private static final String OWNER = "authentication";
  private static final int PAGE_LIMIT = 200;

  private final ProjectionRecoveryJobRepository jobs;
  private final ProjectionRecoveryQueryRepository queries;
  private final ProjectionStatusRepository statuses;
  private final AccountPresentationRecoveryPort projection;
  private final InboxRepository inbox;

  public AccountPresentationRecoveryService(ProjectionRecoveryJobRepository jobs,
      ProjectionRecoveryQueryRepository queries, ProjectionStatusRepository statuses,
      AccountPresentationRecoveryPort projection, InboxRepository inbox) {
    this.jobs = jobs;
    this.queries = queries;
    this.statuses = statuses;
    this.projection = projection;
    this.inbox = inbox;
  }

  @Transactional
  public boolean startFullIfBootstrapping() {
    var state = statuses.findByName(PROJECTION).orElse(null);
    if (state == null || state.status() != ProjectionStatus.BOOTSTRAPPING) return false;
    Instant now = Instant.now();
    if (!statuses.claimBootstrapping(PROJECTION, state.generation(), now)) return false;
    startFull(state.generation() + 1, now);
    return true;
  }

  @Transactional
  public UUID startFull() {
    Instant now = Instant.now();
    long generation = jobs.nextGeneration(PROJECTION);
    statuses.upsert(new ProjectionState(PROJECTION, ProjectionStatus.REBUILDING, null, null, generation, now));
    return startFull(generation, now);
  }

  @Transactional
  public UUID startEntity(UUID accountId) {
    if (accountId == null) throw new IllegalArgumentException("accountId is required");
    UUID jobId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    Instant now = Instant.now();
    long generation = jobs.nextGeneration(PROJECTION);
    RecoveryJob job = new RecoveryJob(jobId, PROJECTION, "ENTITY", accountId, generation, OWNER,
        "GetAccountPresentationSnapshot", jobId, null, "RUNNING", now, now, null, null, queryId);
    jobs.insert(job);
    queries.insert(query(queryId, job, job.queryType(), Map.of("accountId", accountId)));
    return jobId;
  }

  @Transactional
  public boolean applyResult(IncomingResult result) {
    if (!valid(result)) return false;
    RecoveryJob job = jobs.findById(result.operationId()).orElse(null);
    if (job == null || !OWNER.equals(result.producer())) return false;
    Instant now = Instant.now();
    if (!inbox.recordIfNew(new InboxMessage("bff-account-presentation-recovery", result.messageId(),
        MessageKind.RESULT.name(), result.messageType(), result.messageVersion(), result.producer(),
        result.correlationId(), result.causationId(), result.operationId(), result.aggregateId(),
        result.aggregateVersion(), now, now))) return true;
    if (job.mode().equals("FULL") && statuses.findByName(PROJECTION)
        .map(state -> state.generation() != job.generation()).orElse(true)) return true;
    if (!result.causationId().equals(job.currentQueryMessageId())) return true;
    return result.messageType().equals("GetAccountPresentationSnapshotResult")
        ? applyEntity(job, result, now) : applyPage(job, result, now);
  }

  private UUID startFull(long generation, Instant now) {
    UUID jobId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    RecoveryJob job = new RecoveryJob(jobId, PROJECTION, "FULL", null, generation, OWNER,
        "ListAccountPresentationSnapshotsPage", jobId, null, "RUNNING", now, now, null, null, queryId);
    projection.clearAccountPresentationProjection();
    jobs.insert(job);
    queries.insert(query(queryId, job, job.queryType(), Map.of("limit", PAGE_LIMIT)));
    return jobId;
  }

  private boolean applyEntity(RecoveryJob job, IncomingResult result, Instant now) {
    if ("FOUND".equals(result.status())) {
      applySnapshot(result.data(), now);
      finish(job, "COMPLETED", null, ProjectionStatus.READY, now);
    } else if ("NOT_FOUND".equals(result.status())) {
      ProjectionStatus next = projection.hasAccountPresentation(job.entityId())
          ? ProjectionStatus.DEGRADED : ProjectionStatus.READY;
      finish(job, "COMPLETED", null, next, now);
    } else {
      fail(job, result.errorCode(), now);
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private boolean applyPage(RecoveryJob job, IncomingResult result, Instant now) {
    if (!"SUCCEEDED".equals(result.status()) || result.data() == null
        || !(result.data().get("items") instanceof List<?> items)) {
      fail(job, result.errorCode() == null ? "INVALID_RECOVERY_RESULT" : result.errorCode(), now);
      return true;
    }
    try {
      for (Object item : items) {
        if (!(item instanceof Map<?, ?> raw)) throw new IllegalArgumentException("invalid snapshot item");
        applySnapshot((Map<String, Object>) raw, now);
      }
      boolean hasMore = Boolean.TRUE.equals(result.data().get("hasMore"));
      Object rawCursor = result.data().get("nextCursor");
      if (hasMore && (!(rawCursor instanceof String) || ((String) rawCursor).isBlank())) {
        throw new IllegalArgumentException("nextCursor is required when hasMore");
      }
      if (hasMore) {
        String cursor = (String) rawCursor;
        UUID queryId = UUID.randomUUID();
        RecoveryJob next = new RecoveryJob(job.recoveryJobId(), job.projectionName(), job.mode(), job.entityId(),
            job.generation(), job.ownerLogicalComponentId(), job.queryType(), job.operationId(), cursor,
            "RUNNING", job.startedAt(), now, null, null, queryId);
        jobs.update(next);
        queries.insert(query(queryId, next, job.queryType(), Map.of("cursor", UUID.fromString(cursor), "limit", PAGE_LIMIT)));
      } else {
        finish(job, "COMPLETED", null, ProjectionStatus.READY, now);
      }
    } catch (IllegalArgumentException invalid) {
      fail(job, "INVALID_RECOVERY_RESULT", now);
    }
    return true;
  }

  private void applySnapshot(Map<String, Object> data, Instant fallbackTime) {
    if (data == null) throw new IllegalArgumentException("snapshot data is required");
    projection.upsertAccountPresentationSnapshot(new ProjectionReadModel.AccountPresentation(
        uuid(data, "accountId"), uuid(data, "personId"), text(data, "loginType"),
        text(data, "loginIdentifier"), text(data, "status"), number(data, "sourceVersion"),
        data.containsKey("updatedAt") ? ProjectionPayloads.instant(data, "updatedAt") : fallbackTime));
  }

  private void finish(RecoveryJob job, String status, String error, ProjectionStatus readiness, Instant now) {
    jobs.update(new RecoveryJob(job.recoveryJobId(), job.projectionName(), job.mode(), job.entityId(), job.generation(),
        job.ownerLogicalComponentId(), job.queryType(), job.operationId(), job.cursor(), status,
        job.startedAt(), now, now, error, null));
    statuses.upsert(new ProjectionState(PROJECTION, readiness, now, error, job.generation(), now));
  }

  private void fail(RecoveryJob job, String error, Instant now) {
    finish(job, "FAILED", error == null ? "RECOVERY_FAILED" : error, ProjectionStatus.DEGRADED, now);
  }

  private static boolean valid(IncomingResult result) {
    return result != null && result.messageId() != null && result.messageKind() == MessageKind.RESULT
        && result.messageVersion() == 1 && result.occurredAt() != null && result.correlationId() != null
        && result.causationId() != null && result.operationId() != null && OWNER.equals(result.producer())
        && ("GetAccountPresentationSnapshotResult".equals(result.messageType())
            || "ListAccountPresentationSnapshotsPageResult".equals(result.messageType()));
  }

  private static RecoveryQuery query(UUID messageId, RecoveryJob job, String type, Map<String, Object> payload) {
    return new RecoveryQuery(messageId, job.recoveryJobId(), job.generation(), type, 1,
        "platform.queries", OWNER, job.recoveryJobId(), null, messageId.toString(), null,
        payload, "PENDING", null, null, 0, null, null, null, Instant.now(), Instant.now());
  }
}
