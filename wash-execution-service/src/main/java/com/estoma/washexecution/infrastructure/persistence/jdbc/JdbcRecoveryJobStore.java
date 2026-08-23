package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.infrastructure.messaging.recovery.RecoveryJobStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcRecoveryJobStore implements RecoveryJobStore {
  private final JdbcTemplate jdbc;

  public JdbcRecoveryJobStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean hasCompletedFull(String projection, LocalDate date) {
    Integer count = jdbc.queryForObject("""
        SELECT count(*) FROM projection_recovery_jobs
        WHERE projection_name=? AND scope='FULL' AND service_date IS NOT DISTINCT FROM ?
          AND status='COMPLETED'
        """, Integer.class, projection, date);
    return count != null && count > 0;
  }

  @Override
  public Optional<Job> findActiveFull(String projection, LocalDate date) {
    return jdbc.query("""
        SELECT * FROM projection_recovery_jobs
        WHERE projection_name=? AND scope='FULL' AND service_date IS NOT DISTINCT FROM ?
          AND status IN ('PENDING','WAITING_RESULT')
        ORDER BY created_at DESC LIMIT 1
        """, this::map, projection, date).stream().findFirst();
  }

  @Override
  public Optional<Job> findActiveEntity(String projection, String key) {
    return jdbc.query("""
        SELECT * FROM projection_recovery_jobs
        WHERE projection_name=? AND scope='ENTITY' AND entity_key=?
          AND status IN ('PENDING','WAITING_RESULT')
        ORDER BY created_at DESC LIMIT 1
        """, this::map, projection, key).stream().findFirst();
  }

  @Override
  public Job createFull(String projection, LocalDate date, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO projection_recovery_jobs(
          recovery_job_id,projection_name,scope,service_date,status,created_at,updated_at)
        VALUES(?,?,'FULL',?,'PENDING',?,?)
        """, id, projection, date, now, now);
    return find(id);
  }

  @Override
  public Job createEntity(String projection, String key, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO projection_recovery_jobs(
          recovery_job_id,projection_name,scope,entity_key,status,created_at,updated_at)
        VALUES(?,?,'ENTITY',?,'PENDING',?,?)
        """, id, projection, key, now, now);
    return find(id);
  }

  @Override
  public List<Job> claimPending(int limit, Instant now, UUID token, Instant leaseUntil) {
    return jdbc.query("""
        WITH candidate AS (
          SELECT recovery_job_id FROM projection_recovery_jobs
          WHERE status='PENDING' AND (lease_until IS NULL OR lease_until<=?)
          ORDER BY updated_at,recovery_job_id
          FOR UPDATE SKIP LOCKED LIMIT ?
        )
        UPDATE projection_recovery_jobs j
        SET claim_token=?,lease_until=?,updated_at=?
        FROM candidate c
        WHERE j.recovery_job_id=c.recovery_job_id
        RETURNING j.*
        """, this::map, now, limit, token, leaseUntil, now);
  }

  @Override
  public boolean markWaiting(
      UUID id,
      UUID token,
      String type,
      UUID messageId,
      UUID operationId,
      UUID correlationId,
      Instant now) {
    return jdbc.update("""
        UPDATE projection_recovery_jobs
        SET status='WAITING_RESULT',query_attempt=query_attempt+1,current_query_type=?,
          current_query_message_id=?,current_operation_id=?,current_correlation_id=?,
          claim_token=NULL,lease_until=NULL,updated_at=?
        WHERE recovery_job_id=? AND status='PENDING' AND claim_token=?
        """, type, messageId, operationId, correlationId, now, id, token) == 1;
  }

  @Override
  public Optional<Job> lockWaitingByQueryMessageId(UUID queryId) {
    return jdbc.query("""
        SELECT * FROM projection_recovery_jobs
        WHERE status='WAITING_RESULT' AND current_query_message_id=? FOR UPDATE
        """, this::map, queryId).stream().findFirst();
  }

  @Override
  public void advancePage(UUID id, String cursor, Instant now) {
    updated(id, """
        UPDATE projection_recovery_jobs
        SET status='PENDING',cursor=?,current_query_type=NULL,current_query_message_id=NULL,
          current_operation_id=NULL,current_correlation_id=NULL,last_error_code=NULL,updated_at=?
        WHERE recovery_job_id=? AND status='WAITING_RESULT'
        """, cursor, now, id);
  }

  @Override
  public void complete(UUID id, Instant now) {
    updated(id, """
        UPDATE projection_recovery_jobs
        SET status='COMPLETED',current_query_type=NULL,current_query_message_id=NULL,
          current_operation_id=NULL,current_correlation_id=NULL,last_error_code=NULL,
          completed_at=?,updated_at=?
        WHERE recovery_job_id=? AND status='WAITING_RESULT'
        """, now, now, id);
  }

  @Override
  public void fail(UUID id, String error, Instant now) {
    updated(id, """
        UPDATE projection_recovery_jobs
        SET status='FAILED',current_query_type=NULL,current_query_message_id=NULL,
          current_operation_id=NULL,current_correlation_id=NULL,claim_token=NULL,lease_until=NULL,
          last_error_code=?,updated_at=?
        WHERE recovery_job_id=? AND status IN ('PENDING','WAITING_RESULT')
        """, error, now, id);
  }

  @Override
  public int requeueTimedOut(Instant threshold, Instant now) {
    return jdbc.update("""
        UPDATE projection_recovery_jobs
        SET status='PENDING',current_query_type=NULL,current_query_message_id=NULL,
          current_operation_id=NULL,current_correlation_id=NULL,last_error_code='RESULT_TIMEOUT',updated_at=?
        WHERE status='WAITING_RESULT' AND updated_at<=?
        """, now, threshold);
  }

  private Job find(UUID id) {
    return jdbc.query(
        "SELECT * FROM projection_recovery_jobs WHERE recovery_job_id=?",
        this::map,
        id).stream().findFirst().orElseThrow();
  }

  private void updated(UUID id, String sql, Object... args) {
    if (jdbc.update(sql, args) != 1) {
      throw new IllegalStateException("RECOVERY_JOB_STATE_CONFLICT:" + id);
    }
  }

  private Job map(ResultSet rs, int row) throws SQLException {
    return new Job(
        rs.getObject("recovery_job_id", UUID.class),
        rs.getString("projection_name"),
        Scope.valueOf(rs.getString("scope")),
        rs.getString("entity_key"),
        rs.getObject("service_date", LocalDate.class),
        Status.valueOf(rs.getString("status")),
        rs.getString("cursor"),
        rs.getInt("query_attempt"),
        rs.getString("current_query_type"),
        rs.getObject("current_query_message_id", UUID.class),
        rs.getObject("current_operation_id", UUID.class),
        rs.getObject("current_correlation_id", UUID.class),
        rs.getString("last_error_code"),
        rs.getTimestamp("updated_at").toInstant());
  }
}
