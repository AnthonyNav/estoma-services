package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
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
  public void insert(RecoveryJob job) {
    jdbc.sql(
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
            """)
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
}
