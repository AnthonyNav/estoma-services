package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProjectionRecoveryJobRepository implements ProjectionRecoveryJobRepository {

  private static final String COLUMNS =
      "recovery_job_id, projection_type, scope, entity_key, status, cursor, query_attempt, "
          + "current_query_type, current_query_message_id, current_operation_id, current_correlation_id, attempts, "
          + "last_error_code, created_at, updated_at, completed_at, claim_token, lease_until";
  private final JdbcClient jdbc;

  JdbcProjectionRecoveryJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<RecoveryJob> findById(UUID recoveryJobId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM projection_recovery_jobs WHERE recovery_job_id = :recoveryJobId")
        .param("recoveryJobId", recoveryJobId)
        .query(RecoveryJobRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<RecoveryJob> findActive(String projectionType, String scope, String entityKey) {
    String entityPredicate = "FULL".equals(scope) ? "entity_key IS NULL" : "entity_key = :entityKey";
    var spec = jdbc.sql(
            "SELECT " + COLUMNS + " FROM projection_recovery_jobs "
                + "WHERE projection_type = :projectionType AND scope = CAST(:scope AS recovery_scope) "
                + "AND " + entityPredicate + " AND status IN ('PENDING', 'WAITING_RESULT') "
                + "ORDER BY created_at LIMIT 1")
        .param("projectionType", projectionType)
        .param("scope", scope);
    if (!"FULL".equals(scope)) {
      spec = spec.param("entityKey", entityKey);
    }
    return spec.query(RecoveryJobRowMapper.INSTANCE).optional();
  }

  @Override
  public Optional<RecoveryJob> findWaitingByQueryMessageId(UUID queryMessageId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM projection_recovery_jobs "
                + "WHERE status = 'WAITING_RESULT' AND current_query_message_id = :queryMessageId")
        .param("queryMessageId", queryMessageId)
        .query(RecoveryJobRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public boolean hasActiveFull(String projectionType) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM projection_recovery_jobs "
                + "WHERE projection_type = :projectionType AND scope = 'FULL' "
                + "AND status IN ('PENDING', 'WAITING_RESULT'))")
        .param("projectionType", projectionType)
        .query(Boolean.class)
        .single();
  }

  @Override
  public boolean hasCompletedFull(String projectionType) {
    return latestStatus(projectionType, "FULL", null)
        .map("COMPLETED"::equals)
        .orElse(false);
  }

  @Override
  public boolean hasFailedFull(String projectionType) {
    return latestStatus(projectionType, "FULL", null)
        .map("FAILED"::equals)
        .orElse(false);
  }

  @Override
  public List<RecoveryJob> findWaitingBefore(Instant cutoff, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("recovery job limit must be positive");
    }
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM projection_recovery_jobs "
                + "WHERE status = 'WAITING_RESULT' AND updated_at < :cutoff "
                + "ORDER BY updated_at, recovery_job_id LIMIT :limit")
        .param("cutoff", Timestamps.toTimestamp(cutoff))
        .param("limit", limit)
        .query(RecoveryJobRowMapper.INSTANCE)
        .list();
  }

  @Override
  public boolean isProjectionTrusted(String projectionType) {
    return latestStatus(projectionType, "FULL", null)
        .map("COMPLETED"::equals)
        .orElse(true);
  }

  @Override
  public boolean isEntityTrusted(String projectionType, String entityKey) {
    return latestStatus(projectionType, "ENTITY", entityKey)
        .map("COMPLETED"::equals)
        .orElse(true);
  }

  private Optional<String> latestStatus(String projectionType, String scope, String entityKey) {
    String entityPredicate = "FULL".equals(scope) ? "entity_key IS NULL" : "entity_key = :entityKey";
    var spec = jdbc.sql(
            "SELECT status::text FROM projection_recovery_jobs "
                + "WHERE projection_type = :projectionType AND scope = CAST(:scope AS recovery_scope) "
                + "AND " + entityPredicate + " ORDER BY updated_at DESC, created_at DESC LIMIT 1")
        .param("projectionType", projectionType)
        .param("scope", scope);
    if (!"FULL".equals(scope)) {
      spec = spec.param("entityKey", entityKey);
    }
    return spec.query(String.class).optional();
  }

  @Override
  public void insert(RecoveryJob job) {
    insertSql(job, false);
  }

  @Override
  public boolean insertIfAbsent(RecoveryJob job) {
    return insertSql(job, true) == 1;
  }

  private int insertSql(RecoveryJob job, boolean ignoreConflict) {
    return jdbc.sql(
            """
            INSERT INTO projection_recovery_jobs (
              recovery_job_id, projection_type, scope, entity_key, status, cursor, query_attempt,
              current_query_type, current_query_message_id, current_operation_id, current_correlation_id,
              attempts, last_error_code, created_at, updated_at, completed_at, claim_token, lease_until
            ) VALUES (
              :recoveryJobId, :projectionType, CAST(:scope AS recovery_scope), :entityKey, CAST(:status AS recovery_job_status),
              :cursor, :queryAttempt, :currentQueryType, :currentQueryMessageId, :currentOperationId,
              :currentCorrelationId, :attempts, :lastErrorCode, :createdAt, :updatedAt, :completedAt, :claimToken, :leaseUntil
            )
            """ + (ignoreConflict ? " ON CONFLICT DO NOTHING" : ""))
        .param("recoveryJobId", job.recoveryJobId())
        .param("projectionType", job.projectionType())
        .param("scope", job.scope())
        .param("entityKey", job.entityKey())
        .param("status", job.status())
        .param("cursor", job.cursor())
        .param("queryAttempt", job.queryAttempt())
        .param("currentQueryType", job.currentQueryType())
        .param("currentQueryMessageId", job.currentQueryMessageId())
        .param("currentOperationId", job.currentOperationId())
        .param("currentCorrelationId", job.currentCorrelationId())
        .param("attempts", job.attempts())
        .param("lastErrorCode", job.lastErrorCode())
        .param("createdAt", Timestamps.toTimestamp(job.createdAt()))
        .param("updatedAt", Timestamps.toTimestamp(job.updatedAt()))
        .param("completedAt", Timestamps.toTimestamp(job.completedAt()))
        .param("claimToken", job.claimToken())
        .param("leaseUntil", Timestamps.toTimestamp(job.leaseUntil()))
        .update();
  }

  @Override
  public int update(RecoveryJob job, String expectedStatus, UUID expectedCurrentQueryMessageId) {
    String expectedQueryPredicate = expectedCurrentQueryMessageId == null
        ? ""
        : " AND current_query_message_id = :expectedCurrentQueryMessageId";
    var statement = jdbc.sql(
            """
            UPDATE projection_recovery_jobs SET
              status = CAST(:status AS recovery_job_status), cursor = :cursor,
              query_attempt = :queryAttempt, current_query_type = :currentQueryType,
              current_query_message_id = :currentQueryMessageId,
              current_operation_id = :currentOperationId, current_correlation_id = :currentCorrelationId,
              attempts = :attempts, last_error_code = :lastErrorCode, updated_at = :updatedAt,
              completed_at = :completedAt, claim_token = :claimToken, lease_until = :leaseUntil
            WHERE recovery_job_id = :recoveryJobId
              AND status = CAST(:expectedStatus AS recovery_job_status)
            """ + expectedQueryPredicate)
        .param("recoveryJobId", job.recoveryJobId())
        .param("status", job.status())
        .param("cursor", job.cursor())
        .param("queryAttempt", job.queryAttempt())
        .param("currentQueryType", job.currentQueryType())
        .param("currentQueryMessageId", job.currentQueryMessageId())
        .param("currentOperationId", job.currentOperationId())
        .param("currentCorrelationId", job.currentCorrelationId())
        .param("attempts", job.attempts())
        .param("lastErrorCode", job.lastErrorCode())
        .param("updatedAt", Timestamps.toTimestamp(job.updatedAt()))
        .param("completedAt", Timestamps.toTimestamp(job.completedAt()))
        .param("claimToken", job.claimToken())
        .param("leaseUntil", Timestamps.toTimestamp(job.leaseUntil()))
        .param("expectedStatus", expectedStatus);
    if (expectedCurrentQueryMessageId != null) {
      statement = statement.param("expectedCurrentQueryMessageId", expectedCurrentQueryMessageId);
    }
    return statement.update();
  }
}
