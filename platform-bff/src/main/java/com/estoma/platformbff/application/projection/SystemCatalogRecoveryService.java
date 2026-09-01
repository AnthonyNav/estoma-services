package com.estoma.platformbff.application.projection;

import static com.estoma.platformbff.application.projection.ProjectionPayloads.number;
import static com.estoma.platformbff.application.projection.ProjectionPayloads.text;
import static com.estoma.platformbff.application.projection.ProjectionPayloads.uuid;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.application.port.ProjectionRecoveryJobRepository;
import com.estoma.platformbff.application.port.ProjectionRecoveryQueryRepository;
import com.estoma.platformbff.application.port.ProjectionStatusRepository;
import com.estoma.platformbff.application.port.SystemCatalogRecoveryPort;
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

/** Recovers Authorization's configuration-owned System catalog without synthetic catalog events. */
@Service
public class SystemCatalogRecoveryService {
  private static final String PROJECTION = "system-catalog";
  private static final String OWNER = "authorization";
  private static final int PAGE_LIMIT = 1000;

  private final ProjectionRecoveryJobRepository jobs;
  private final ProjectionRecoveryQueryRepository queries;
  private final ProjectionStatusRepository statuses;
  private final SystemCatalogRecoveryPort projection;
  private final InboxRepository inbox;

  public SystemCatalogRecoveryService(ProjectionRecoveryJobRepository jobs,
      ProjectionRecoveryQueryRepository queries, ProjectionStatusRepository statuses,
      SystemCatalogRecoveryPort projection, InboxRepository inbox) {
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

  /**
   * Deployment supplies a code only when Authorization changed its static catalog. The local row
   * becomes untrusted before the Query is published, so only that system fails closed.
   */
  @Transactional
  public UUID startEntity(String systemCode) {
    if (systemCode == null || systemCode.isBlank()) throw new IllegalArgumentException("systemCode is required");
    String canonical = systemCode.trim();
    UUID jobId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    Instant now = Instant.now();
    long generation = jobs.nextGeneration(PROJECTION);
    projection.markSystemCatalogUntrusted(canonical);
    RecoveryJob job = new RecoveryJob(jobId, PROJECTION, "ENTITY", null, generation, OWNER,
        "GetSystemCatalogSnapshot", jobId, canonical, "RUNNING", now, now, null, null, queryId);
    jobs.insert(job);
    queries.insert(query(queryId, job, job.queryType(), Map.of("systemCode", canonical)));
    return jobId;
  }

  @Transactional
  public boolean applyResult(IncomingResult result) {
    if (!valid(result)) return false;
    RecoveryJob job = jobs.findById(result.operationId()).orElse(null);
    if (job == null || !OWNER.equals(result.producer())) return false;
    Instant now = Instant.now();
    if (!inbox.recordIfNew(new InboxMessage("bff-system-catalog-recovery", result.messageId(),
        MessageKind.RESULT.name(), result.messageType(), result.messageVersion(), result.producer(),
        result.correlationId(), result.causationId(), result.operationId(), result.aggregateId(),
        result.aggregateVersion(), now, now))) return true;
    if (job.mode().equals("FULL") && statuses.findByName(PROJECTION)
        .map(state -> state.generation() != job.generation()).orElse(true)) return true;
    if (!result.causationId().equals(job.currentQueryMessageId())) return true;
    return result.messageType().equals("GetSystemCatalogSnapshotResult")
        ? applyEntity(job, result, now) : applyPage(job, result, now);
  }

  private UUID startFull(long generation, Instant now) {
    UUID jobId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    RecoveryJob job = new RecoveryJob(jobId, PROJECTION, "FULL", null, generation, OWNER,
        "ListSystemCatalogSnapshotsPage", jobId, null, "RUNNING", now, now, null, null, queryId);
    projection.clearSystemCatalogProjection();
    jobs.insert(job);
    queries.insert(query(queryId, job, job.queryType(), Map.of("limit", PAGE_LIMIT)));
    return jobId;
  }

  private boolean applyEntity(RecoveryJob job, IncomingResult result, Instant now) {
    if ("FOUND".equals(result.status())) {
      applySnapshot(result.data(), now);
      complete(job, now);
    } else if ("NOT_FOUND".equals(result.status())) {
      // The refresh fence remains false, so an obsolete catalog row cannot keep granting access.
      complete(job, now);
    } else {
      fail(job, result.errorCode(), now, job.mode().equals("FULL"));
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private boolean applyPage(RecoveryJob job, IncomingResult result, Instant now) {
    if (!"SUCCEEDED".equals(result.status()) || result.data() == null
        || !(result.data().get("items") instanceof List<?> items)) {
      fail(job, result.errorCode() == null ? "INVALID_RECOVERY_RESULT" : result.errorCode(), now, true);
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
        queries.insert(query(queryId, next, job.queryType(), Map.of("cursor", cursor, "limit", PAGE_LIMIT)));
      } else {
        complete(job, now);
      }
    } catch (IllegalArgumentException invalid) {
      fail(job, "INVALID_RECOVERY_RESULT", now, true);
    }
    return true;
  }

  private void applySnapshot(Map<String, Object> data, Instant fallbackTime) {
    if (data == null) throw new IllegalArgumentException("snapshot data is required");
    projection.upsertSystemCatalogSnapshot(new ProjectionReadModel.SystemCatalog(
        uuid(data, "systemId"), text(data, "systemCode"), text(data, "status"),
        number(data, "sourceVersion"), true,
        data.containsKey("snapshotGeneratedAt") ? ProjectionPayloads.instant(data, "snapshotGeneratedAt") : fallbackTime));
  }

  private void complete(RecoveryJob job, Instant now) {
    jobs.update(new RecoveryJob(job.recoveryJobId(), job.projectionName(), job.mode(), job.entityId(), job.generation(),
        job.ownerLogicalComponentId(), job.queryType(), job.operationId(), job.cursor(), "COMPLETED",
        job.startedAt(), now, now, null, null));
    if (job.mode().equals("FULL")) {
      statuses.upsert(new ProjectionState(PROJECTION, ProjectionStatus.READY, now, null, job.generation(), now));
    }
  }

  private void fail(RecoveryJob job, String error, Instant now, boolean global) {
    String code = error == null ? "RECOVERY_FAILED" : error;
    jobs.update(new RecoveryJob(job.recoveryJobId(), job.projectionName(), job.mode(), job.entityId(), job.generation(),
        job.ownerLogicalComponentId(), job.queryType(), job.operationId(), job.cursor(), "FAILED",
        job.startedAt(), now, now, code, null));
    if (global) {
      statuses.upsert(new ProjectionState(PROJECTION, ProjectionStatus.DEGRADED, now, code, job.generation(), now));
    }
  }

  private static boolean valid(IncomingResult result) {
    return result != null && result.messageId() != null && result.messageKind() == MessageKind.RESULT
        && result.messageVersion() == 1 && result.occurredAt() != null && result.correlationId() != null
        && result.causationId() != null && result.operationId() != null && OWNER.equals(result.producer())
        && ("GetSystemCatalogSnapshotResult".equals(result.messageType())
            || "ListSystemCatalogSnapshotsPageResult".equals(result.messageType()));
  }

  private static RecoveryQuery query(UUID messageId, RecoveryJob job, String type, Map<String, Object> payload) {
    return new RecoveryQuery(messageId, job.recoveryJobId(), job.generation(), type, 1,
        "platform.queries", OWNER, job.recoveryJobId(), null, messageId.toString(), null,
        payload, "PENDING", null, null, 0, null, null, null, Instant.now(), Instant.now());
  }
}
