package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository;
import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository.RecoveryJob;
import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository.Scope;
import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository.Status;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectionRecoveryJobRepository implements ProjectionRecoveryJobRepository {

  private final JdbcClient jdbc;

  public JdbcProjectionRecoveryJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<RecoveryJob> claimNextPending(UUID claimToken, Instant leaseUntil) {
    jdbc.sql(
            """
            UPDATE projection_recovery_jobs
            SET claim_token = NULL, lease_until = NULL
            WHERE status = 'PENDING' AND lease_until IS NOT NULL AND lease_until < now()
            """)
        .update();
    Optional<UUID> id = jdbc.sql(
            """
            SELECT recovery_job_id
            FROM projection_recovery_jobs
            WHERE projection_type = 'AUTHORIZATION' AND status = 'PENDING'
              AND (claim_token IS NULL OR lease_until < now())
            ORDER BY created_at, recovery_job_id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
        .query((rs, rowNum) -> rs.getObject("recovery_job_id", UUID.class))
        .optional();
    if (id.isEmpty()) return Optional.empty();
    jdbc.sql(
            """
            UPDATE projection_recovery_jobs
            SET claim_token = :claimToken, lease_until = :leaseUntil, updated_at = now()
            WHERE recovery_job_id = :recoveryJobId AND status = 'PENDING'
            """)
        .param("claimToken", claimToken)
        .param("leaseUntil", Timestamps.toTimestamp(leaseUntil))
        .param("recoveryJobId", id.get())
        .update();
    return findById(id.get());
  }

  @Override
  public RecoveryJob createEntity(UUID accountId, Instant now) {
    UUID jobId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO projection_recovery_jobs
                (recovery_job_id, projection_type, scope, entity_key, status, created_at, updated_at)
            VALUES (:jobId, 'AUTHORIZATION', 'ENTITY', :entityKey, 'PENDING', :now, :now)
            ON CONFLICT DO NOTHING
            """)
        .param("jobId", jobId)
        .param("entityKey", "account:" + accountId)
        .param("now", Timestamps.toTimestamp(now))
        .update();
    return findEntity(accountId).orElseThrow();
  }

  @Override
  public RecoveryJob createFull(Instant now) {
    UUID jobId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO projection_recovery_jobs
                (recovery_job_id, projection_type, scope, status, created_at, updated_at)
            VALUES (:jobId, 'AUTHORIZATION', 'FULL', 'PENDING', :now, :now)
            ON CONFLICT DO NOTHING
            """)
        .param("jobId", jobId)
        .param("now", Timestamps.toTimestamp(now))
        .update();
    return findActiveFull().orElseThrow();
  }

  @Override
  public Optional<RecoveryJob> findWaitingByQueryMessageIdForUpdate(UUID queryMessageId) {
    return jdbc.sql(
            """
            SELECT recovery_job_id, projection_type, scope, entity_key, status, cursor, query_attempt,
                   current_query_type, current_query_message_id, current_operation_id,
                   current_correlation_id, attempts, last_error_code, created_at, updated_at,
                   completed_at, claim_token, lease_until
            FROM projection_recovery_jobs
            WHERE status = 'WAITING_RESULT' AND current_query_message_id = :queryMessageId
            FOR UPDATE
            """)
        .param("queryMessageId", queryMessageId)
        .query((rs, rowNum) -> map(rs))
        .optional();
  }

  @Override
  public boolean markWaiting(UUID recoveryJobId, UUID claimToken, String queryType,
      UUID queryMessageId, UUID operationId, UUID correlationId, int queryAttempt) {
    return jdbc.sql(
            """
            UPDATE projection_recovery_jobs
            SET status = 'WAITING_RESULT', current_query_type = :queryType,
                current_query_message_id = :queryMessageId, current_operation_id = :operationId,
                current_correlation_id = :correlationId, query_attempt = :queryAttempt,
                claim_token = NULL, lease_until = NULL, updated_at = now()
            WHERE recovery_job_id = :recoveryJobId AND status = 'PENDING' AND claim_token = :claimToken
            """)
        .param("recoveryJobId", recoveryJobId)
        .param("claimToken", claimToken)
        .param("queryType", queryType)
        .param("queryMessageId", queryMessageId)
        .param("operationId", operationId)
        .param("correlationId", correlationId)
        .param("queryAttempt", queryAttempt)
        .update() == 1;
  }

  @Override
  public boolean markPendingAfterPage(UUID recoveryJobId, UUID queryMessageId, String cursor) {
    return jdbc.sql(
            """
            UPDATE projection_recovery_jobs
            SET status = 'PENDING', cursor = :cursor, current_query_type = NULL,
                current_query_message_id = NULL, current_operation_id = NULL,
                current_correlation_id = NULL, attempts = attempts + 1, updated_at = now()
            WHERE recovery_job_id = :recoveryJobId AND status = 'WAITING_RESULT'
              AND current_query_message_id = :queryMessageId
            """)
        .param("recoveryJobId", recoveryJobId)
        .param("queryMessageId", queryMessageId)
        .param("cursor", cursor)
        .update() == 1;
  }

  @Override
  public boolean markCompleted(UUID recoveryJobId, UUID queryMessageId) {
    return jdbc.sql(
            """
            UPDATE projection_recovery_jobs
            SET status = 'COMPLETED', current_query_type = NULL,
                current_query_message_id = NULL, current_operation_id = NULL,
                current_correlation_id = NULL, completed_at = now(), updated_at = now()
            WHERE recovery_job_id = :recoveryJobId AND status = 'WAITING_RESULT'
              AND current_query_message_id = :queryMessageId
            """)
        .param("recoveryJobId", recoveryJobId)
        .param("queryMessageId", queryMessageId)
        .update() == 1;
  }

  @Override
  public boolean markFailed(UUID recoveryJobId, UUID queryMessageId, String errorCode) {
    return jdbc.sql(
            """
            UPDATE projection_recovery_jobs
            SET status = 'FAILED', last_error_code = :errorCode,
                current_query_type = NULL, current_query_message_id = NULL,
                current_operation_id = NULL, current_correlation_id = NULL,
                completed_at = now(), updated_at = now()
            WHERE recovery_job_id = :recoveryJobId AND status = 'WAITING_RESULT'
              AND current_query_message_id = :queryMessageId
            """)
        .param("recoveryJobId", recoveryJobId)
        .param("queryMessageId", queryMessageId)
        .param("errorCode", errorCode)
        .update() == 1;
  }

  @Override
  public int requeueExpiredWaiting(Instant cutoff, String errorCode) {
    return jdbc.sql(
            """
            UPDATE projection_recovery_jobs
            SET status = 'PENDING', current_query_type = NULL,
                current_query_message_id = NULL, current_operation_id = NULL,
                current_correlation_id = NULL, attempts = attempts + 1,
                last_error_code = :errorCode, updated_at = now()
            WHERE projection_type = 'AUTHORIZATION' AND status = 'WAITING_RESULT'
              AND updated_at < :cutoff
            """)
        .param("cutoff", Timestamps.toTimestamp(cutoff))
        .param("errorCode", errorCode)
        .update();
  }

  @Override
  public void clearAuthorizationProjection() {
    lockAuthorizationProjection();
    jdbc.sql("DELETE FROM authorization_access_projection WHERE system_code = 'LAVADO_ULTRASONICO'").update();
    jdbc.sql("DELETE FROM authorization_role_projection").update();
  }

  @Override
  public boolean hasActiveFullRecovery() {
    String status = latestFullStatus().orElse(null);
    return "PENDING".equals(status) || "WAITING_RESULT".equals(status);
  }

  @Override
  public boolean hasFailedFullRecovery() {
    return latestFullStatus().map("FAILED"::equals).orElse(false);
  }

  @Override
  public boolean hasCompletedFullRecovery() {
    return latestFullStatus().map("COMPLETED"::equals).orElse(false);
  }

  @Override
  public boolean hasActiveEntityRecovery(UUID accountId) {
    String status = latestRelevantEntityStatus(accountId).orElse(null);
    return "PENDING".equals(status) || "WAITING_RESULT".equals(status);
  }

  @Override
  public boolean hasFailedEntityRecovery(UUID accountId) {
    return latestRelevantEntityStatus(accountId).map("FAILED"::equals).orElse(false);
  }

  @Override
  public boolean hasCompletedEntityRecovery(UUID accountId) {
    return latestRelevantEntityStatus(accountId).map("COMPLETED"::equals).orElse(false);
  }

  private Optional<String> latestFullStatus() {
    return jdbc.sql(
            "SELECT status::text FROM projection_recovery_jobs WHERE projection_type = 'AUTHORIZATION' AND scope = 'FULL' ORDER BY created_at DESC, recovery_job_id DESC LIMIT 1")
        .query(String.class).optional();
  }

  /** Point repairs older than the latest successful FULL generation are superseded by that generation. */
  private Optional<String> latestRelevantEntityStatus(UUID accountId) {
    return jdbc.sql(
            """
            SELECT status::text
            FROM projection_recovery_jobs
            WHERE projection_type = 'AUTHORIZATION'
              AND scope = 'ENTITY'
              AND entity_key = :entityKey
              AND created_at > COALESCE(
                    (SELECT max(completed_at)
                     FROM projection_recovery_jobs
                     WHERE projection_type = 'AUTHORIZATION'
                       AND scope = 'FULL'
                       AND status = 'COMPLETED'),
                    '-infinity'::timestamptz)
            ORDER BY created_at DESC, recovery_job_id DESC
            LIMIT 1
            """)
        .param("entityKey", "account:" + accountId)
        .query(String.class).optional();
  }

  private void lockAuthorizationProjection() {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('wash-administration.authorization', 0))")
        .query((rs, rowNum) -> 1).list();
  }

  private Optional<RecoveryJob> findById(UUID id) {
    return jdbc.sql(baseSelect() + " WHERE recovery_job_id = :recoveryJobId")
        .param("recoveryJobId", id).query((rs, rowNum) -> map(rs)).optional();
  }

  private Optional<RecoveryJob> findEntity(UUID accountId) {
    return jdbc.sql(baseSelect() + " WHERE projection_type = 'AUTHORIZATION' AND scope = 'ENTITY' AND entity_key = :entityKey ORDER BY created_at DESC LIMIT 1")
        .param("entityKey", "account:" + accountId).query((rs, rowNum) -> map(rs)).optional();
  }

  private Optional<RecoveryJob> findActiveFull() {
    return jdbc.sql(baseSelect() + " WHERE projection_type = 'AUTHORIZATION' AND scope = 'FULL' AND status IN ('PENDING', 'WAITING_RESULT') ORDER BY created_at DESC LIMIT 1")
        .query((rs, rowNum) -> map(rs)).optional();
  }

  private static String baseSelect() {
    return """
        SELECT recovery_job_id, projection_type, scope, entity_key, status, cursor, query_attempt,
               current_query_type, current_query_message_id, current_operation_id,
               current_correlation_id, attempts, last_error_code, created_at, updated_at,
               completed_at, claim_token, lease_until
        FROM projection_recovery_jobs
        """;
  }

  private static RecoveryJob map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new RecoveryJob(
        rs.getObject("recovery_job_id", UUID.class), rs.getString("projection_type"),
        Scope.valueOf(rs.getString("scope")), rs.getString("entity_key"),
        Status.valueOf(rs.getString("status")), rs.getString("cursor"), rs.getInt("query_attempt"),
        rs.getString("current_query_type"), rs.getObject("current_query_message_id", UUID.class),
        rs.getObject("current_operation_id", UUID.class), rs.getObject("current_correlation_id", UUID.class),
        rs.getInt("attempts"), rs.getString("last_error_code"), Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"), Timestamps.toInstant(rs, "completed_at"),
        rs.getObject("claim_token", UUID.class), Timestamps.toInstant(rs, "lease_until"));
  }
}
