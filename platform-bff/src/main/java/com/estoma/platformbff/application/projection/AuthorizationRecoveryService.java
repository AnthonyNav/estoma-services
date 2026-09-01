package com.estoma.platformbff.application.projection;

import static com.estoma.platformbff.application.projection.ProjectionPayloads.number;
import static com.estoma.platformbff.application.projection.ProjectionPayloads.optionalText;
import static com.estoma.platformbff.application.projection.ProjectionPayloads.text;
import static com.estoma.platformbff.application.projection.ProjectionPayloads.uuid;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.port.AuthorizationRecoveryPort;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationRecoveryService {
  private static final String PROJECTION = "authorization";
  private static final String OWNER = "authorization";
  private static final int PAGE_LIMIT = 1000;

  private final ProjectionRecoveryJobRepository jobs;
  private final ProjectionRecoveryQueryRepository queries;
  private final ProjectionStatusRepository statuses;
  private final AuthorizationRecoveryPort projection;
  private final InboxRepository inbox;

  public AuthorizationRecoveryService(ProjectionRecoveryJobRepository jobs,
      ProjectionRecoveryQueryRepository queries, ProjectionStatusRepository statuses,
      AuthorizationRecoveryPort projection, InboxRepository inbox) {
    this.jobs = jobs;
    this.queries = queries;
    this.statuses = statuses;
    this.projection = projection;
    this.inbox = inbox;
  }

  @Transactional
  public UUID startEntity(UUID accountId, String systemCode) {
    if (accountId == null) throw new IllegalArgumentException("accountId is required");
    UUID jobId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    Instant now = Instant.now();
    RecoveryJob job = new RecoveryJob(jobId, PROJECTION, "ENTITY", accountId, jobs.nextGeneration(PROJECTION),
        OWNER, "GetAccountAuthorizationSnapshot", jobId, null, "RUNNING", now, now, null, null, queryId);
    jobs.insert(job);
    statuses.upsert(new ProjectionState(PROJECTION, ProjectionStatus.REBUILDING, null, null, job.generation(), now));
    Map<String, Object> payload = new HashMap<>();
    payload.put("accountId", accountId);
    if (systemCode != null) payload.put("systemCode", systemCode);
    queries.insert(query(queryId, job, "GetAccountAuthorizationSnapshot", payload));
    return jobId;
  }

  @Transactional
  public UUID startFull() {
    UUID jobId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    Instant now = Instant.now();
    long generation = jobs.nextGeneration(PROJECTION);
    RecoveryJob job = new RecoveryJob(jobId, PROJECTION, "FULL", null, generation, OWNER,
        "ListAccountAuthorizationSnapshotsPage", jobId, null, "RUNNING", now, now, null, null, queryId);
    statuses.upsert(new ProjectionState(PROJECTION, ProjectionStatus.REBUILDING, null, null, generation, now));
    projection.clearAuthorizationProjection();
    jobs.insert(job);
    queries.insert(query(queryId, job, "ListAccountAuthorizationSnapshotsPage",
        Map.of("limit", PAGE_LIMIT)));
    return jobId;
  }

  /** Atomically claims the initial BOOTSTRAPPING state before publishing the first full Query. */
  @Transactional
  public boolean startFullIfBootstrapping() {
    var state = statuses.findByName(PROJECTION).orElse(null);
    if (state == null || state.status() != ProjectionStatus.BOOTSTRAPPING) return false;
    Instant now = Instant.now();
    if (!statuses.claimBootstrapping(PROJECTION, state.generation(), now)) return false;
    UUID jobId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    long generation = state.generation() + 1;
    RecoveryJob job = new RecoveryJob(jobId, PROJECTION, "FULL", null, generation, OWNER,
        "ListAccountAuthorizationSnapshotsPage", jobId, null, "RUNNING", now, now, null, null, queryId);
    projection.clearAuthorizationProjection();
    jobs.insert(job);
    queries.insert(query(queryId, job, "ListAccountAuthorizationSnapshotsPage", Map.of("limit", PAGE_LIMIT)));
    return true;
  }

  @Transactional
  public boolean applyResult(IncomingResult result) {
    if (!valid(result)) return false;
    RecoveryJob job = jobs.findById(result.operationId()).orElse(null);
    if (job == null || !OWNER.equals(result.producer())) return false;

    Instant now = Instant.now();
    if (!inbox.recordIfNew(new InboxMessage(
        "bff-authorization-recovery", result.messageId(), MessageKind.RESULT.name(), result.messageType(),
        result.messageVersion(), result.producer(), result.correlationId(), result.causationId(),
        result.operationId(), result.aggregateId(), result.aggregateVersion(), now, now))) {
      return true;
    }
    if (job.mode().equals("FULL")
        && statuses.findByName(PROJECTION).map(state -> state.generation() != job.generation()).orElse(true)) {
      return true;
    }
    // A Result from an obsolete query/generation is acknowledged but cannot advance current state.
    if (!result.causationId().equals(job.currentQueryMessageId())) return true;

    if (result.messageType().equals("GetAccountAuthorizationSnapshotResult")) {
      return applyEntityResult(job, result, now);
    }
    return applyPageResult(job, result, now);
  }

  private boolean applyEntityResult(RecoveryJob job, IncomingResult result, Instant now) {
    String status = result.status();
    if ("FOUND".equals(status)) {
      applySnapshot(result.data(), now);
      finish(job, "COMPLETED", null, ProjectionStatus.READY, now);
      return true;
    }
    if ("NOT_FOUND".equals(status)) {
      // NOT_FOUND has no source version and therefore never deletes an Event-projected row.
      ProjectionStatus next = projection.hasAuthorizationState(job.entityId())
          ? ProjectionStatus.DEGRADED : ProjectionStatus.READY;
      finish(job, "COMPLETED", null, next, now);
      return true;
    }
    fail(job, result.errorCode(), now);
    return true;
  }

  @SuppressWarnings("unchecked")
  private boolean applyPageResult(RecoveryJob job, IncomingResult result, Instant now) {
    if (!"SUCCEEDED".equals(result.status()) || result.data() == null) {
      fail(job, result.errorCode(), now);
      return true;
    }
    Object rawItems = result.data().get("items");
    if (!(rawItems instanceof List<?> items)) {
      fail(job, "INVALID_RECOVERY_RESULT", now);
      return true;
    }
    for (Object item : items) {
      if (!(item instanceof Map<?, ?> raw)) {
        fail(job, "INVALID_RECOVERY_RESULT", now);
        return true;
      }
      applySnapshot((Map<String, Object>) raw, now);
    }
    boolean hasMore = Boolean.TRUE.equals(result.data().get("hasMore"));
    String nextCursor = optionalText(result.data(), "nextCursor");
    if (hasMore && nextCursor != null) {
      UUID queryId = UUID.randomUUID();
      RecoveryJob next = new RecoveryJob(job.recoveryJobId(), job.projectionName(), job.mode(), job.entityId(),
          job.generation(), job.ownerLogicalComponentId(), job.queryType(), job.operationId(), nextCursor,
          "RUNNING", job.startedAt(), now, null, null, queryId);
      jobs.update(next);
      queries.insert(query(queryId, next, "ListAccountAuthorizationSnapshotsPage",
          Map.of("cursor", UUID.fromString(nextCursor), "limit", PAGE_LIMIT)));
    } else {
      finish(job, "COMPLETED", null, ProjectionStatus.READY, now);
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private void applySnapshot(Map<String, Object> data, Instant fallbackTime) {
    if (data == null) throw new IllegalArgumentException("recovery snapshot data is required");
    UUID accountId = uuid(data, "accountId");
    List<ProjectionReadModel.AuthorizationAccess> accesses = new ArrayList<>();
    Object rawAccesses = data.get("systemAccesses");
    if (!(rawAccesses instanceof List<?> list)) throw new IllegalArgumentException("systemAccesses is required");
    for (Object raw : list) {
      if (!(raw instanceof Map<?, ?> value)) throw new IllegalArgumentException("invalid system access snapshot");
      Map<String, Object> access = (Map<String, Object>) value;
      accesses.add(new ProjectionReadModel.AuthorizationAccess(
          uuid(access, "systemAccessId"), accountId, uuid(access, "systemId"), text(access, "systemCode"),
          text(access, "status"), number(access, "accessVersion"), fallbackTime));
    }
    ProjectionReadModel.AuthorizationRole role = null;
    Object rawRole = data.get("role");
    if (rawRole instanceof Map<?, ?> value) {
      Map<String, Object> roleData = (Map<String, Object>) value;
      role = new ProjectionReadModel.AuthorizationRole(accountId, text(roleData, "roleCode"),
          number(roleData, "roleVersion"), fallbackTime);
    }
    projection.upsertSnapshot(role, accesses);
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
        && result.messageVersion() == 1 && result.messageType() != null && result.producer() != null
        && result.occurredAt() != null && result.correlationId() != null && result.causationId() != null
        && result.operationId() != null
        && (result.messageType().equals("GetAccountAuthorizationSnapshotResult")
            || result.messageType().equals("ListAccountAuthorizationSnapshotsPageResult"));
  }

  private static RecoveryQuery query(UUID messageId, RecoveryJob job, String type, Map<String, Object> payload) {
    return new RecoveryQuery(messageId, job.recoveryJobId(), job.generation(), type, 1,
        "platform.queries", "authorization", job.recoveryJobId(), null, messageId.toString(), null,
        payload, "PENDING", null, null, 0, null, null, null, Instant.now(), Instant.now());
  }
}
