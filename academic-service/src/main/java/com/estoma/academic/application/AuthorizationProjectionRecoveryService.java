package com.estoma.academic.application;

import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.messaging.EnvelopeCodec;
import com.estoma.academic.observability.AcademicMessagingMetrics;
import com.estoma.academic.persistence.OutboxRepository;
import com.estoma.academic.persistence.ProjectionRecoveryJobRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Coordinates durable repair of Academic's local Authorization projection. */
@Component
public class AuthorizationProjectionRecoveryService {
  private static final String QUERY_GET = "GetAccountAuthorizationSnapshot";
  private static final String QUERY_LIST = "ListAccountAuthorizationSnapshotsPage";

  private final JdbcClient jdbc;
  private final EnvelopeCodec codec;
  private final OutboxRepository outbox;
  private final ProjectionRecoveryJobRepository jobs;
  private final AcademicMessagingMetrics metrics;
  private final int maxAttempts;

  public AuthorizationProjectionRecoveryService(
      JdbcClient jdbc,
      EnvelopeCodec codec,
      OutboxRepository outbox,
      ProjectionRecoveryJobRepository jobs,
      AcademicMessagingMetrics metrics,
      @Value("${estoma.authorization-recovery.max-attempts:3}") int maxAttempts) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.outbox = outbox;
    this.jobs = jobs;
    this.metrics = metrics;
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("authorization-recovery.max-attempts must be positive");
    }
    this.maxAttempts = maxAttempts;
  }

  @Transactional
  public UUID requestEntity(UUID accountId) {
    if (accountId == null) {
      throw new IllegalArgumentException("accountId is required");
    }
    return jobs.requestEntity(accountId, Instant.now());
  }

  /**
   * Starts a FULL rebuild atomically with the empty-target initialization.
   * Once this transaction commits, every later Authorization Event is applied to the target that
   * will survive the rebuild; the worker never clears an already-active target afterward.
   */
  @Transactional
  public UUID requestFull() {
    Instant now = Instant.now();
    UUID job = jobs.requestFull(now);
    boolean alreadyInitialized = jdbc.sql("""
        SELECT status='REBUILDING' AND active_job_id=:job
        FROM academic_projection_recovery_state
        WHERE projection_type='AUTHORIZATION'
        FOR UPDATE
        """)
        .param("job", job)
        .query(Boolean.class)
        .optional()
        .orElse(false);
    if (!alreadyInitialized) {
      jobs.markRebuilding(job, now);
      clearAuthorizationProjection();
    }
    return job;
  }

  /** Appends one recovery Query and closes the claim in the same local transaction. */
  @Transactional
  public void publishNext(ProjectionRecoveryJobRepository.Job job) {
    Instant now = Instant.now();
    UUID messageId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID correlationId = job.recoveryJobId();
    String queryType;
    Map<String, Object> payload = new LinkedHashMap<>();
    if (job.full()) {
      queryType = QUERY_LIST;
      payload.put("limit", 100);
      if (job.cursor() != null) {
        payload.put("cursor", job.cursor());
      }
    } else {
      queryType = QUERY_GET;
      payload.put("accountId", accountId(job.entityKey()));
    }

    outbox.append(
        messageId,
        queryType,
        "QUERY",
        job.full() ? null : accountId(job.entityKey()),
        null,
        correlationId,
        null,
        codec.json(payload),
        codec.json(Map.of(
            "destination", "authorization",
            "operationId", operationId,
            "actor", new Actor("SYSTEM", null, "academic"))),
        now);
    if (jobs.markWaiting(job, queryType, messageId, operationId, correlationId, now) != 1) {
      throw new IllegalStateException("recovery job claim was lost before Query publication");
    }
  }

  /** Applies a recovery Result only to the current query attempt. */
  @Transactional
  public ResultOutcome applyResult(MessageEnvelope result) {
    validateResult(result);
    Instant now = Instant.now();
    ProjectionRecoveryJobRepository.Job job =
        jobs.findWaiting(result.correlationId(), result.operationId()).orElse(null);
    if (job == null || !result.causationId().equals(job.currentQueryMessageId())) {
      if (!recordInbox(result, now)) {
        return ResultOutcome.DUPLICATE;
      }
      metrics.staleResult();
      return ResultOutcome.STALE;
    }

    if (!(job.currentQueryType() + "Result").equals(result.messageType())) {
      throw new IllegalArgumentException("Result type does not match current recovery Query");
    }
    if (!recordInbox(result, now)) {
      return ResultOutcome.DUPLICATE;
    }

    String status = result.payload().path("status").asString();
    if ("REJECTED".equals(status) || "FAILED".equals(status)) {
      handleTerminalFailure(job, result, status, now);
      return ResultOutcome.APPLIED;
    }

    if ((QUERY_GET + "Result").equals(result.messageType())) {
      applyEntityResult(job, result, now);
      return ResultOutcome.APPLIED;
    }
    applyPageResult(job, result, now);
    return ResultOutcome.APPLIED;
  }

  public boolean authorizationProjectionReady() {
    return jobs.authorizationProjectionReady();
  }

  public boolean authorizationProjectionRebuilding() {
    return jobs.authorizationProjectionRebuilding();
  }

  /**
   * A NOT_FOUND conflict is not a permanent dead-end: fail closed and request a new ENTITY recovery.
   * ROLE_ABSENT, by contrast, is an authoritative negative snapshot and waits for a newer Event.
   */
  public boolean accountTrustworthy(UUID accountId) {
    if (accountId == null) {
      return false;
    }
    String degradedReason = jdbc.sql("""
        SELECT reason FROM authorization_projection_degraded_accounts WHERE account_id=:id
        """)
        .param("id", accountId)
        .query(String.class)
        .optional()
        .orElse(null);
    if (degradedReason == null) {
      return true;
    }
    if ("NOT_FOUND_CONFLICT".equals(degradedReason)) {
      jobs.requestEntity(accountId, Instant.now());
    }
    return false;
  }

  public boolean hasAuthorizationProjectionRows() {
    return jdbc.sql("""
        SELECT EXISTS (SELECT 1 FROM authorization_context_projection)
          OR EXISTS (SELECT 1 FROM system_access_context_projection)
        """).query(Boolean.class).single();
  }

  private void handleTerminalFailure(
      ProjectionRecoveryJobRepository.Job job,
      MessageEnvelope result,
      String status,
      Instant now) {
    String code = text(result.payload().path("error"), "code");
    if (code == null) {
      throw new IllegalArgumentException("terminal recovery Result requires error.code");
    }
    ProjectionRecoveryJobRepository.ResultFailureTransition transition =
        jobs.recordTerminalResultFailure(job, "RECOVERY_" + status + "_" + code, maxAttempts, now);
    if (transition.failed() && job.full()) {
      jobs.markFailed(job.recoveryJobId(), now);
    }
  }

  private void applyEntityResult(
      ProjectionRecoveryJobRepository.Job job, MessageEnvelope result, Instant now) {
    String status = result.payload().path("status").asString();
    if ("FOUND".equals(status)) {
      JsonNode data = result.payload().path("data");
      if (!data.isObject()) {
        throw new IllegalArgumentException("FOUND recovery Result requires data");
      }
      instant(data, "snapshotGeneratedAt");
      if (!accountId(job.entityKey()).equals(uuid(data, "accountId"))) {
        throw new IllegalArgumentException("authorization snapshot account does not match recovery job");
      }
      applySnapshot(data, true);
    } else if (!"NOT_FOUND".equals(status)) {
      throw new IllegalArgumentException("invalid authorization recovery Result status");
    } else if (hasAuthorizationProjection(accountId(job.entityKey()))) {
      jdbc.sql("""
          INSERT INTO projection_recovery_not_found_conflicts
            (conflict_id,recovery_job_id,account_id,component,result_message_id,created_at)
          VALUES (:id,:job,:account,'AUTHORIZATION',:message,:now)
          """)
          .param("id", UUID.randomUUID())
          .param("job", job.recoveryJobId())
          .param("account", accountId(job.entityKey()))
          .param("message", result.messageId())
          .param("now", java.sql.Timestamp.from(now))
          .update();
      jdbc.sql("""
          INSERT INTO authorization_projection_degraded_accounts (account_id,reason,updated_at)
          VALUES (:account,'NOT_FOUND_CONFLICT',:now)
          ON CONFLICT (account_id) DO UPDATE SET reason=excluded.reason,updated_at=excluded.updated_at
          """)
          .param("account", accountId(job.entityKey()))
          .param("now", java.sql.Timestamp.from(now))
          .update();
      metrics.recoveryConflict();
    }
    if (jobs.complete(job, now) != 1) {
      throw new IllegalStateException("recovery job changed while applying entity Result");
    }
  }

  private void applyPageResult(
      ProjectionRecoveryJobRepository.Job job, MessageEnvelope result, Instant now) {
    if (!"SUCCEEDED".equals(result.payload().path("status").asString())) {
      throw new IllegalArgumentException("page recovery Result must be SUCCEEDED");
    }
    JsonNode data = result.payload().path("data");
    if (!data.isObject() || !data.path("items").isArray()
        || !data.has("hasMore") || !data.path("hasMore").isBoolean()) {
      throw new IllegalArgumentException("page recovery Result requires items");
    }
    // snapshotGeneratedAt is observability only. Per-component sourceVersion provides ordering.
    Instant snapshotGeneratedAt = instant(data, "snapshotGeneratedAt");
    if (jobs.markSnapshotGenerated(job.recoveryJobId(), snapshotGeneratedAt, now) != 1) {
      throw new IllegalStateException("recovery state changed while recording snapshot metadata");
    }
    for (JsonNode item : data.path("items")) {
      if (!item.isObject() || !item.hasNonNull("accountId")) {
        throw new IllegalArgumentException("invalid authorization snapshot item");
      }
      applySnapshot(item, false);
    }
    boolean hasMore = data.path("hasMore").asBoolean(false);
    String cursor = data.hasNonNull("nextCursor") ? data.path("nextCursor").asString() : null;
    if (hasMore && (cursor == null || cursor.isBlank() || cursor.equals(job.cursor()))) {
      throw new IllegalArgumentException("page with hasMore requires nextCursor");
    }
    if (hasMore) {
      if (jobs.markPending(job, cursor, now) != 1) {
        throw new IllegalStateException("recovery job changed while advancing cursor");
      }
    } else {
      if (jobs.complete(job, now) != 1) {
        throw new IllegalStateException("recovery job changed while completing page recovery");
      }
      jobs.markReady(job.recoveryJobId(), now);
    }
  }

  private void applySnapshot(JsonNode data, boolean markRoleAbsent) {
    UUID accountId = uuid(data, "accountId");
    if (accountId == null) {
      throw new IllegalArgumentException("authorization snapshot requires accountId");
    }
    JsonNode role = data.path("role");
    if (!role.isMissingNode() && !role.isNull()
        && (!role.isObject() || !role.hasNonNull("roleCode") || !role.hasNonNull("roleVersion")
            || role.path("roleCode").asString().isBlank() || role.path("roleVersion").asInt(-1) < 0)) {
      throw new IllegalArgumentException("invalid authorization role snapshot");
    }
    boolean rolePresent = role.isObject();
    if (rolePresent) {
      jdbc.sql("""
          INSERT INTO authorization_context_projection
            (account_id,role_code,status,source_version,updated_at)
          VALUES (:account,:role,'ACTIVE',:version,now())
          ON CONFLICT (account_id,role_code) DO UPDATE SET
            status=excluded.status,source_version=excluded.source_version,updated_at=excluded.updated_at
          WHERE authorization_context_projection.source_version < excluded.source_version
          """)
          .param("account", accountId)
          .param("role", role.path("roleCode").asString())
          .param("version", role.path("roleVersion").asInt())
          .update();
      jdbc.sql("""
          UPDATE authorization_context_projection
          SET status='INACTIVE',updated_at=now()
          WHERE account_id=:account AND role_code<>:role AND status='ACTIVE'
            AND source_version <= :version
          """)
          .param("account", accountId)
          .param("role", role.path("roleCode").asString())
          .param("version", role.path("roleVersion").asInt())
          .update();
    }

    JsonNode accesses = data.path("systemAccesses");
    if (!accesses.isArray()) {
      throw new IllegalArgumentException("authorization snapshot requires systemAccesses array");
    }
    for (JsonNode access : accesses) {
      UUID systemAccessId = uuid(access, "systemAccessId");
      UUID systemId = uuid(access, "systemId");
      String systemCode = text(access, "systemCode");
      String status = text(access, "status");
      int version = access.path("accessVersion").asInt(-1);
      if (systemAccessId == null || systemId == null || systemCode == null || status == null || version < 0
          || !("ACTIVE".equals(status) || "SUSPENDED".equals(status) || "REVOKED".equals(status))) {
        throw new IllegalArgumentException("invalid authorization system access snapshot");
      }
      jdbc.sql("""
          INSERT INTO system_access_context_projection
            (account_id,system_access_id,system_id,system_code,status,source_version,updated_at)
          VALUES (:account,:systemAccessId,:systemId,:system,:status,:version,now())
          ON CONFLICT (account_id,system_code) DO UPDATE SET
            system_access_id=excluded.system_access_id,
            system_id=COALESCE(excluded.system_id,system_access_context_projection.system_id),
            status=excluded.status,source_version=excluded.source_version,updated_at=excluded.updated_at
          WHERE system_access_context_projection.source_version < excluded.source_version
          """)
          .param("account", accountId)
          .param("systemAccessId", systemAccessId)
          .param("systemId", systemId)
          .param("system", systemCode)
          .param("status", status)
          .param("version", version)
          .update();
    }
    if (rolePresent) {
      jdbc.sql("DELETE FROM authorization_projection_degraded_accounts WHERE account_id=:account")
          .param("account", accountId)
          .update();
    } else if (markRoleAbsent) {
      jdbc.sql("""
          INSERT INTO authorization_projection_degraded_accounts (account_id,reason,updated_at)
          VALUES (:account,'ROLE_ABSENT',now())
          ON CONFLICT (account_id) DO UPDATE SET reason=excluded.reason,updated_at=excluded.updated_at
          """)
          .param("account", accountId)
          .update();
    }
  }

  private boolean recordInbox(MessageEnvelope result, Instant now) {
    return jdbc.sql("""
        INSERT INTO processed_messages (
          consumer_name,message_id,correlation_id,causation_id,operation_id,message_type,message_kind,
          producer,aggregate_id,aggregate_version,envelope,processing_state,reconciliation_state,
          received_at,applied_at,processed_at)
        VALUES ('academic.authorization-recovery',:id,:correlation,:causation,:operation,:type,:kind,
          :producer,:aggregate,:version,CAST(:envelope AS jsonb),'APPLIED',NULL,:now,:now,:now)
        ON CONFLICT DO NOTHING
        """)
        .param("id", result.messageId())
        .param("correlation", result.correlationId())
        .param("causation", result.causationId())
        .param("operation", result.operationId())
        .param("type", result.messageType())
        .param("kind", result.messageKind())
        .param("producer", result.producer())
        .param("aggregate", result.aggregateId())
        .param("version", result.aggregateVersion())
        .param("envelope", codec.json(result))
        .param("now", java.sql.Timestamp.from(now))
        .update() == 1;
  }

  private void clearAuthorizationProjection() {
    jdbc.sql("DELETE FROM system_access_context_projection").update();
    jdbc.sql("DELETE FROM authorization_context_projection").update();
    jdbc.sql("DELETE FROM authorization_projection_degraded_accounts").update();
  }

  private boolean hasAuthorizationProjection(UUID accountId) {
    return jdbc.sql("""
        SELECT EXISTS (
          SELECT 1 FROM authorization_context_projection WHERE account_id=:account
          UNION ALL
          SELECT 1 FROM system_access_context_projection WHERE account_id=:account
        )
        """).param("account", accountId).query(Boolean.class).single();
  }

  private static void validateResult(MessageEnvelope result) {
    if (result == null || result.messageId() == null || result.messageVersion() != 1
        || result.correlationId() == null || result.causationId() == null || result.operationId() == null
        || !"RESULT".equals(result.messageKind()) || !"authorization".equals(result.producer())
        || result.actor() == null || !"SYSTEM".equals(result.actor().type())
        || result.actor().accountId() != null || !"academic".equals(result.actor().service())
        || result.payload() == null || !result.payload().isObject()) {
      throw new IllegalArgumentException("invalid authorization recovery Result envelope");
    }
    if (!(result.messageType().equals(QUERY_GET + "Result")
        || result.messageType().equals(QUERY_LIST + "Result"))) {
      throw new IllegalArgumentException("unsupported authorization recovery Result");
    }
  }

  private static UUID accountId(String entityKey) {
    if (entityKey == null || !entityKey.startsWith("account:")) {
      throw new IllegalArgumentException("invalid authorization recovery entity key");
    }
    return UUID.fromString(entityKey.substring("account:".length()));
  }

  private static UUID uuid(JsonNode node, String field) {
    if (!node.hasNonNull(field)) {
      return null;
    }
    try {
      return UUID.fromString(node.path(field).asString());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("invalid " + field, ex);
    }
  }

  private static String text(JsonNode node, String field) {
    return node.hasNonNull(field) && !node.path(field).asString().isBlank()
        ? node.path(field).asString() : null;
  }

  private static Instant instant(JsonNode node, String field) {
    if (!node.hasNonNull(field)) {
      throw new IllegalArgumentException(field + " is required");
    }
    try {
      return Instant.parse(node.path(field).asString());
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("invalid " + field, ex);
    }
  }

  public enum ResultOutcome { APPLIED, DUPLICATE, STALE }
}
