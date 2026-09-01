package com.estoma.platformbff.application.projection;

import static com.estoma.platformbff.application.projection.ProjectionPayloads.number;
import static com.estoma.platformbff.application.projection.ProjectionPayloads.optionalText;
import static com.estoma.platformbff.application.projection.ProjectionPayloads.text;
import static com.estoma.platformbff.application.projection.ProjectionPayloads.uuid;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.application.port.PersonPresentationRecoveryPort;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rebuilds the Users-owned Person Presentation needed by closed BFF session contexts. */
@Service
public class PersonPresentationRecoveryService {
  private static final String PROJECTION = "person";
  private static final String OWNER = "users";
  private static final String RESULT_TYPE = "ListPersonPresentationSnapshotsPageResult";
  private static final int PAGE_LIMIT = 200;

  private final ProjectionRecoveryJobRepository jobs;
  private final ProjectionRecoveryQueryRepository queries;
  private final ProjectionStatusRepository statuses;
  private final PersonPresentationRecoveryPort projection;
  private final InboxRepository inbox;

  public PersonPresentationRecoveryService(ProjectionRecoveryJobRepository jobs,
      ProjectionRecoveryQueryRepository queries, ProjectionStatusRepository statuses,
      PersonPresentationRecoveryPort projection, InboxRepository inbox) {
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
  public boolean applyResult(IncomingResult result) {
    if (!valid(result)) return false;
    RecoveryJob job = jobs.findById(result.operationId()).orElse(null);
    if (job == null || !OWNER.equals(result.producer())) return false;
    Instant now = Instant.now();
    if (!inbox.recordIfNew(new InboxMessage("bff-person-presentation-recovery", result.messageId(),
        MessageKind.RESULT.name(), result.messageType(), result.messageVersion(), result.producer(),
        result.correlationId(), result.causationId(), result.operationId(), result.aggregateId(),
        result.aggregateVersion(), now, now))) return true;
    if (statuses.findByName(PROJECTION).map(state -> state.generation() != job.generation()).orElse(true)) {
      return true;
    }
    if (!result.causationId().equals(job.currentQueryMessageId())) return true;
    if (!"SUCCEEDED".equals(result.status()) || result.data() == null
        || !(result.data().get("items") instanceof List<?> items)) {
      fail(job, result.errorCode() == null ? "INVALID_RECOVERY_RESULT" : result.errorCode(), now);
      return true;
    }
    try {
      for (Object item : items) {
        if (!(item instanceof Map<?, ?> raw)) throw new IllegalArgumentException("invalid snapshot item");
        applySnapshot(cast(raw), now);
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
        queries.insert(query(queryId, next, Map.of("cursor", UUID.fromString(cursor), "limit", PAGE_LIMIT)));
      } else {
        finish(job, "COMPLETED", null, ProjectionStatus.READY, now);
      }
    } catch (IllegalArgumentException invalid) {
      fail(job, "INVALID_RECOVERY_RESULT", now);
    }
    return true;
  }

  private UUID startFull(long generation, Instant now) {
    UUID jobId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    RecoveryJob job = new RecoveryJob(jobId, PROJECTION, "FULL", null, generation, OWNER,
        "ListPersonPresentationSnapshotsPage", jobId, null, "RUNNING", now, now, null, null, queryId);
    projection.clearPersonPresentationProjection();
    jobs.insert(job);
    queries.insert(query(queryId, job, Map.of("limit", PAGE_LIMIT)));
    return jobId;
  }

  private void applySnapshot(Map<String, Object> data, Instant fallbackTime) {
    projection.upsertPersonPresentationSnapshot(new ProjectionReadModel.Person(
        uuid(data, "personId"), text(data, "firstName"), text(data, "paternalSurname"),
        optionalText(data, "maternalSurname"), text(data, "institutionalEmail"), true,
        text(data, "status"), number(data, "sourceVersion"),
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
        && RESULT_TYPE.equals(result.messageType());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Map<?, ?> data) {
    return (Map<String, Object>) data;
  }

  private static RecoveryQuery query(UUID messageId, RecoveryJob job, Map<String, Object> payload) {
    return new RecoveryQuery(messageId, job.recoveryJobId(), job.generation(), job.queryType(), 1,
        "platform.queries", OWNER, job.recoveryJobId(), null, messageId.toString(), null,
        payload, "PENDING", null, null, 0, null, null, null, Instant.now(), Instant.now());
  }
}
