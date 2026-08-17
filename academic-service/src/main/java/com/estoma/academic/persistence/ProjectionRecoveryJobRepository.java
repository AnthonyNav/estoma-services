package com.estoma.academic.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Durable state and fenced transitions for Academic authorization projection recovery. */
@Repository
public class ProjectionRecoveryJobRepository {
  private static final String PROJECTION = "AUTHORIZATION";
  private final JdbcClient jdbc;

  public ProjectionRecoveryJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public UUID requestEntity(UUID accountId, Instant now) {
    String key = "account:" + accountId;
    Optional<UUID> current = activeEntity(key);
    if (current.isPresent()) {
      return current.get();
    }
    UUID id = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO projection_recovery_jobs (
          recovery_job_id,projection_type,scope,entity_key,status,created_at,updated_at)
        VALUES (:id,:projection,'ENTITY',:entity,'PENDING',:now,:now)
        ON CONFLICT DO NOTHING
        """)
        .param("id", id).param("projection", PROJECTION).param("entity", key)
        .param("now", java.sql.Timestamp.from(now)).update();
    return activeEntity(key).orElse(id);
  }

  public UUID requestFull(Instant now) {
    Optional<UUID> current = activeFull();
    if (current.isPresent()) {
      return current.get();
    }
    UUID id = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO projection_recovery_jobs (
          recovery_job_id,projection_type,scope,status,created_at,updated_at)
        VALUES (:id,:projection,'FULL','PENDING',:now,:now)
        ON CONFLICT DO NOTHING
        """)
        .param("id", id).param("projection", PROJECTION)
        .param("now", java.sql.Timestamp.from(now)).update();
    return activeFull().orElse(id);
  }

  /** Claims work without counting a successful page as a retry/failure attempt. */
  public List<Job> claimPending(int limit, Duration lease) {
    UUID token = UUID.randomUUID();
    Instant now = Instant.now();
    return jdbc.sql("""
        WITH candidates AS (
          SELECT recovery_job_id
          FROM projection_recovery_jobs
          WHERE status='PENDING'
            AND (claim_token IS NULL OR lease_until < :now)
          ORDER BY updated_at
          FOR UPDATE SKIP LOCKED
          LIMIT :limit
        )
        UPDATE projection_recovery_jobs j
        SET claim_token=:token,lease_until=:lease,updated_at=:now
        FROM candidates c
        WHERE j.recovery_job_id=c.recovery_job_id
        RETURNING j.recovery_job_id,j.scope::text,j.entity_key,j.status::text,j.cursor,
          j.query_attempt,j.current_query_type,j.current_query_message_id,j.current_operation_id,
          j.current_correlation_id,j.current_system_code,j.attempts,j.last_error_code,
          j.last_error_at,j.created_at,j.updated_at,j.completed_at,j.claim_token,j.lease_until
        """)
        .param("now", java.sql.Timestamp.from(now))
        .param("lease", java.sql.Timestamp.from(now.plus(lease)))
        .param("limit", limit).param("token", token)
        .query((rs, n) -> map(rs)).list();
  }

  public int markWaiting(Job job, String queryType, UUID queryMessageId, UUID operationId,
      UUID correlationId, Instant now) {
    return jdbc.sql("""
        UPDATE projection_recovery_jobs
        SET status='WAITING_RESULT',current_query_type=:type,current_query_message_id=:message,
            current_operation_id=:operation,current_correlation_id=:correlation,
            query_attempt=query_attempt+1,claim_token=NULL,lease_until=NULL,updated_at=:now
        WHERE recovery_job_id=:id AND status='PENDING' AND claim_token=:token
        """)
        .param("type", queryType).param("message", queryMessageId).param("operation", operationId)
        .param("correlation", correlationId).param("now", java.sql.Timestamp.from(now))
        .param("id", job.recoveryJobId()).param("token", job.claimToken()).update();
  }

  /** A successfully applied page resets the consecutive technical failure counter. */
  public int markPending(Job job, String cursor, Instant now) {
    return jdbc.sql("""
        UPDATE projection_recovery_jobs
        SET status='PENDING',cursor=:cursor,current_query_type=NULL,current_query_message_id=NULL,
            current_operation_id=NULL,current_correlation_id=NULL,claim_token=NULL,lease_until=NULL,
            attempts=0,last_error_code=NULL,last_error_at=NULL,updated_at=:now
        WHERE recovery_job_id=:id AND status='WAITING_RESULT'
          AND current_query_message_id=:message AND current_operation_id=:operation
        """)
        .param("cursor", cursor).param("now", java.sql.Timestamp.from(now))
        .param("id", job.recoveryJobId()).param("message", job.currentQueryMessageId())
        .param("operation", job.currentOperationId()).update();
  }

  /** Records one failed local publish attempt and releases the claim for retry. */
  public int release(Job job, String error, Instant now) {
    return jdbc.sql("""
        UPDATE projection_recovery_jobs
        SET status='PENDING',attempts=attempts+1,last_error_code=:error,last_error_at=:now,
            claim_token=NULL,lease_until=NULL,updated_at=:now
        WHERE recovery_job_id=:id AND status='PENDING' AND claim_token=:token
        """)
        .param("error", error).param("now", java.sql.Timestamp.from(now))
        .param("id", job.recoveryJobId()).param("token", job.claimToken()).update();
  }

  /** A waiting-result timeout is one technical failure, independent from page count. */
  public List<ExpiredJob> requeueExpiredWaiting(Duration timeout, int maxAttempts) {
    Instant cutoff = Instant.now().minus(timeout);
    return jdbc.sql("""
        UPDATE projection_recovery_jobs
        SET attempts=attempts+1,
            status=CASE WHEN attempts+1 >= :max THEN 'FAILED'::projection_recovery_status
                        ELSE 'PENDING'::projection_recovery_status END,
            last_error_code='RECOVERY_RESULT_TIMEOUT',last_error_at=now(),
            current_query_type=NULL,current_query_message_id=NULL,current_operation_id=NULL,
            current_correlation_id=NULL,claim_token=NULL,lease_until=NULL,
            completed_at=CASE WHEN attempts+1 >= :max THEN now() ELSE NULL END,updated_at=now()
        WHERE status='WAITING_RESULT' AND updated_at < :cutoff
        RETURNING recovery_job_id,scope::text,status::text
        """)
        .param("max", maxAttempts).param("cutoff", java.sql.Timestamp.from(cutoff))
        .query((rs, n) -> new ExpiredJob(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
  }

  /**
   * Consumes a terminal REJECTED/FAILED Result for the current Query attempt.
   * The Result itself is acknowledged/deduplicated, while the side-effect-free Query work is
   * retried with a new messageId until the consecutive-failure budget is exhausted.
   */
  public ResultFailureTransition recordTerminalResultFailure(
      Job job, String error, int maxAttempts, Instant now) {
    return jdbc.sql("""
        UPDATE projection_recovery_jobs
        SET attempts=attempts+1,
            status=CASE WHEN attempts+1 >= :max THEN 'FAILED'::projection_recovery_status
                        ELSE 'PENDING'::projection_recovery_status END,
            last_error_code=:error,last_error_at=:now,
            current_query_type=NULL,current_query_message_id=NULL,current_operation_id=NULL,
            current_correlation_id=NULL,claim_token=NULL,lease_until=NULL,
            completed_at=CASE WHEN attempts+1 >= :max THEN :now ELSE NULL END,
            updated_at=:now
        WHERE recovery_job_id=:id AND status='WAITING_RESULT'
          AND current_query_message_id=:message AND current_operation_id=:operation
        RETURNING status::text,attempts
        """)
        .param("max", maxAttempts)
        .param("error", error)
        .param("now", java.sql.Timestamp.from(now))
        .param("id", job.recoveryJobId())
        .param("message", job.currentQueryMessageId())
        .param("operation", job.currentOperationId())
        .query((rs, n) -> new ResultFailureTransition(rs.getString(1), rs.getInt(2)))
        .optional()
        .orElseThrow(() -> new IllegalStateException("recovery job changed while recording terminal Result failure"));
  }

  public Optional<Job> findWaiting(UUID correlationId, UUID operationId) {
    return jdbc.sql("""
        SELECT recovery_job_id,scope::text,entity_key,status::text,cursor,query_attempt,
          current_query_type,current_query_message_id,current_operation_id,current_correlation_id,
          current_system_code,attempts,last_error_code,last_error_at,created_at,updated_at,
          completed_at,claim_token,lease_until
        FROM projection_recovery_jobs
        WHERE projection_type=:projection AND status='WAITING_RESULT'
          AND current_correlation_id=:correlation AND current_operation_id=:operation
        FOR UPDATE
        """)
        .param("projection", PROJECTION).param("correlation", correlationId)
        .param("operation", operationId).query((rs, n) -> map(rs)).optional();
  }

  public Optional<Job> find(UUID jobId) {
    return jdbc.sql("""
        SELECT recovery_job_id,scope::text,entity_key,status::text,cursor,query_attempt,
          current_query_type,current_query_message_id,current_operation_id,current_correlation_id,
          current_system_code,attempts,last_error_code,last_error_at,created_at,updated_at,
          completed_at,claim_token,lease_until
        FROM projection_recovery_jobs WHERE recovery_job_id=:id
        """).param("id", jobId).query((rs, n) -> map(rs)).optional();
  }

  public int complete(Job job, Instant now) {
    return jdbc.sql("""
        UPDATE projection_recovery_jobs
        SET status='COMPLETED',current_query_type=NULL,current_query_message_id=NULL,
            current_operation_id=NULL,current_correlation_id=NULL,claim_token=NULL,lease_until=NULL,
            attempts=0,last_error_code=NULL,last_error_at=NULL,completed_at=:now,updated_at=:now
        WHERE recovery_job_id=:id AND status='WAITING_RESULT'
          AND current_query_message_id=:message AND current_operation_id=:operation
        """)
        .param("now", java.sql.Timestamp.from(now)).param("id", job.recoveryJobId())
        .param("message", job.currentQueryMessageId()).param("operation", job.currentOperationId()).update();
  }

  public int fail(Job job, String error, Instant now) {
    return jdbc.sql("""
        UPDATE projection_recovery_jobs
        SET status='FAILED',attempts=attempts+1,last_error_code=:error,last_error_at=:now,
            current_query_type=NULL,current_query_message_id=NULL,current_operation_id=NULL,
            current_correlation_id=NULL,claim_token=NULL,lease_until=NULL,completed_at=:now,updated_at=:now
        WHERE recovery_job_id=:id AND status='PENDING' AND claim_token=:token
        """)
        .param("error", error).param("now", java.sql.Timestamp.from(now))
        .param("id", job.recoveryJobId()).param("token", job.claimToken()).update();
  }

  public void markRebuilding(UUID jobId, Instant now) {
    jdbc.sql("""
        UPDATE academic_projection_recovery_state
        SET status='REBUILDING',active_job_id=:job,
            recovery_started_at=CASE WHEN status='REBUILDING' AND active_job_id=:job
                                     THEN recovery_started_at ELSE :now END,
            snapshot_generated_at=CASE WHEN status='REBUILDING' AND active_job_id=:job
                                       THEN snapshot_generated_at ELSE NULL END,
            updated_at=:now
        WHERE projection_type='AUTHORIZATION'
        """).param("job", jobId).param("now", java.sql.Timestamp.from(now)).update();
  }

  /** Records the first observed page timestamp for observability only; it is not a consistency fence. */
  public int markSnapshotGenerated(UUID jobId, Instant snapshotGeneratedAt, Instant now) {
    return jdbc.sql("""
        UPDATE academic_projection_recovery_state
        SET snapshot_generated_at=COALESCE(snapshot_generated_at,:snapshot),updated_at=:now
        WHERE projection_type='AUTHORIZATION' AND active_job_id=:job
        """)
        .param("job", jobId).param("snapshot", java.sql.Timestamp.from(snapshotGeneratedAt))
        .param("now", java.sql.Timestamp.from(now)).update();
  }

  public void markReady(UUID jobId, Instant now) {
    jdbc.sql("""
        UPDATE academic_projection_recovery_state
        SET status='READY',active_job_id=NULL,updated_at=:now
        WHERE projection_type='AUTHORIZATION' AND active_job_id=:job
        """).param("job", jobId).param("now", java.sql.Timestamp.from(now)).update();
  }

  public void markFailed(UUID jobId, Instant now) {
    jdbc.sql("""
        UPDATE academic_projection_recovery_state
        SET status='FAILED',active_job_id=:job,updated_at=:now
        WHERE projection_type='AUTHORIZATION'
        """).param("job", jobId).param("now", java.sql.Timestamp.from(now)).update();
  }

  public boolean authorizationProjectionReady() {
    return jdbc.sql("SELECT status='READY' FROM academic_projection_recovery_state WHERE projection_type='AUTHORIZATION'")
        .query(Boolean.class).optional().orElse(false);
  }

  public boolean authorizationProjectionRebuilding() {
    return jdbc.sql("SELECT status='REBUILDING' FROM academic_projection_recovery_state WHERE projection_type='AUTHORIZATION'")
        .query(Boolean.class).optional().orElse(false);
  }

  public boolean fullRecoveryRetryDue(Duration delay) {
    Instant cutoff = Instant.now().minus(delay);
    return jdbc.sql("""
        SELECT status='FAILED' AND updated_at <= :cutoff
        FROM academic_projection_recovery_state
        WHERE projection_type='AUTHORIZATION'
        """).param("cutoff", java.sql.Timestamp.from(cutoff)).query(Boolean.class).optional().orElse(false);
  }

  private Optional<UUID> activeEntity(String key) {
    return jdbc.sql("""
        SELECT recovery_job_id FROM projection_recovery_jobs
        WHERE projection_type='AUTHORIZATION' AND scope='ENTITY' AND entity_key=:key
          AND status IN ('PENDING','WAITING_RESULT')
        """).param("key", key).query(UUID.class).optional();
  }

  private Optional<UUID> activeFull() {
    return jdbc.sql("""
        SELECT recovery_job_id FROM projection_recovery_jobs
        WHERE projection_type='AUTHORIZATION' AND scope='FULL'
          AND status IN ('PENDING','WAITING_RESULT')
        """).query(UUID.class).optional();
  }

  private static Job map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Job(
        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
        rs.getInt(6), rs.getString(7), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class),
        rs.getObject(10, UUID.class), rs.getString(11), rs.getInt(12), rs.getString(13),
        timestamp(rs, 14), timestamp(rs, 15), timestamp(rs, 16), timestamp(rs, 17),
        rs.getObject(18, UUID.class), timestamp(rs, 19));
  }

  private static Instant timestamp(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
    java.sql.Timestamp value = rs.getTimestamp(index);
    return value == null ? null : value.toInstant();
  }

  public record Job(
      UUID recoveryJobId,
      String scope,
      String entityKey,
      String status,
      String cursor,
      int queryAttempt,
      String currentQueryType,
      UUID currentQueryMessageId,
      UUID currentOperationId,
      UUID currentCorrelationId,
      String currentSystemCode,
      int attempts,
      String lastErrorCode,
      Instant lastErrorAt,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt,
      UUID claimToken,
      Instant leaseUntil) {
    public boolean full() { return "FULL".equals(scope); }
  }

  public record ExpiredJob(UUID recoveryJobId, String scope, String status) {
    public boolean full() { return "FULL".equals(scope); }
  }

  public record ResultFailureTransition(String status, int attempts) {
    public boolean failed() { return "FAILED".equals(status); }
  }
}
