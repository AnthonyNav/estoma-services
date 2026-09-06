package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.ReassignmentCandidatesPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcReassignmentCandidatesRepository implements ReassignmentCandidatesPort {
  private static final Duration CLAIM_DURATION = Duration.ofSeconds(30);
  private final JdbcClient jdbc;

  public JdbcReassignmentCandidatesRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ProjectedExecution> findProjectedExecution(UUID washExecutionId) {
    return jdbc.sql("""
        SELECT wash_execution_id, status, execution_version
        FROM wash_execution_read_model WHERE wash_execution_id = :executionId
        """).param("executionId", washExecutionId)
        .query((rs, row) -> new ProjectedExecution(rs.getObject("wash_execution_id", UUID.class),
            rs.getString("status"), rs.getLong("execution_version"))).optional();
  }

  @Override
  public Optional<Snapshot> findSnapshot(UUID washExecutionId) {
    return jdbc.sql("""
        SELECT * FROM reassignment_candidates_read_model WHERE wash_execution_id = :executionId
        """).param("executionId", washExecutionId)
        .query((rs, row) -> snapshot(rs)).optional();
  }

  @Override
  @Transactional
  public int enqueueDuePending(Instant now, Instant refreshBefore, Instant abandonBefore, int limit) {
    List<ProjectedExecution> due = jdbc.sql("""
        SELECT e.wash_execution_id, e.status, e.execution_version
        FROM wash_execution_read_model e
        LEFT JOIN reassignment_candidates_refresh r
          ON r.wash_execution_id = e.wash_execution_id
        LEFT JOIN reassignment_candidates_read_model s
          ON s.wash_execution_id = e.wash_execution_id
        WHERE e.status = 'PENDING_REASSIGNMENT'
          AND (r.wash_execution_id IS NULL OR r.execution_version <> e.execution_version
            OR (r.status IN ('APPLIED', 'REJECTED') AND r.requested_at <= :refreshBefore)
            OR (r.status IN ('PENDING', 'PUBLISHED') AND r.requested_at <= :abandonBefore)
            OR (r.status = 'FAILED' AND r.next_attempt_at IS NULL
              AND r.requested_at <= :abandonBefore))
        ORDER BY COALESCE(r.requested_at, TIMESTAMPTZ '-infinity'), e.wash_execution_id
        LIMIT :limit
        """).param("refreshBefore", JdbcTimes.timestamp(refreshBefore))
        .param("abandonBefore", JdbcTimes.timestamp(abandonBefore)).param("limit", limit)
        .query((rs, row) -> new ProjectedExecution(rs.getObject("wash_execution_id", UUID.class),
            rs.getString("status"), rs.getLong("execution_version"))).list();
    int enqueued = 0;
    for (ProjectedExecution execution : due) {
      jdbc.sql("""
          INSERT INTO reassignment_candidates_refresh_history (
            query_message_id, wash_execution_id, execution_version, correlation_id, status,
            attempt_count, published_at, last_error_code, requested_at, retired_at)
          SELECT query_message_id, wash_execution_id, execution_version, correlation_id, status,
            attempt_count, published_at, last_error_code, requested_at, :now
          FROM reassignment_candidates_refresh WHERE wash_execution_id = :executionId
          ON CONFLICT (query_message_id) DO NOTHING
          """).param("now", JdbcTimes.timestamp(now))
          .param("executionId", execution.washExecutionId()).update();
      UUID messageId = UUID.randomUUID();
      UUID correlationId = UUID.randomUUID();
      enqueued += jdbc.sql("""
          INSERT INTO reassignment_candidates_refresh (
            wash_execution_id, execution_version, query_message_id, correlation_id, status,
            claim_token, claim_until, attempt_count, next_attempt_at, published_at,
            result_message_id, last_error_code, requested_at, updated_at)
          VALUES (:executionId, :executionVersion, :messageId, :correlationId, 'PENDING',
            NULL, NULL, 0, NULL, NULL, NULL, NULL, :now, :now)
          ON CONFLICT (wash_execution_id) DO UPDATE SET
            execution_version = EXCLUDED.execution_version,
            query_message_id = EXCLUDED.query_message_id,
            correlation_id = EXCLUDED.correlation_id,
            status = 'PENDING', claim_token = NULL, claim_until = NULL, attempt_count = 0,
            next_attempt_at = NULL, published_at = NULL, result_message_id = NULL,
            last_error_code = NULL, requested_at = EXCLUDED.requested_at,
            updated_at = EXCLUDED.updated_at
          WHERE reassignment_candidates_refresh.execution_version <> EXCLUDED.execution_version
             OR (reassignment_candidates_refresh.status IN ('APPLIED', 'REJECTED')
               AND reassignment_candidates_refresh.requested_at <= :refreshBefore)
             OR (reassignment_candidates_refresh.status IN ('PENDING', 'PUBLISHED')
               AND reassignment_candidates_refresh.requested_at <= :abandonBefore)
             OR (reassignment_candidates_refresh.status = 'FAILED'
               AND reassignment_candidates_refresh.next_attempt_at IS NULL
               AND reassignment_candidates_refresh.requested_at <= :abandonBefore)
          """).param("executionId", execution.washExecutionId())
          .param("executionVersion", execution.executionVersion()).param("messageId", messageId)
          .param("correlationId", correlationId).param("now", JdbcTimes.timestamp(now))
          .param("refreshBefore", JdbcTimes.timestamp(refreshBefore))
          .param("abandonBefore", JdbcTimes.timestamp(abandonBefore)).update();
    }
    return enqueued;
  }

  @Override
  public Optional<Refresh> findRefreshByMessageId(UUID messageId) {
    return jdbc.sql("""
        SELECT wash_execution_id, execution_version, query_message_id, correlation_id, status,
          claim_token, claim_until, attempt_count, next_attempt_at, published_at,
          last_error_code, requested_at
        FROM reassignment_candidates_refresh WHERE query_message_id = :id
        UNION ALL
        SELECT wash_execution_id, execution_version, query_message_id, correlation_id, status,
          NULL AS claim_token, NULL AS claim_until, attempt_count, NULL AS next_attempt_at,
          published_at, last_error_code, requested_at
        FROM reassignment_candidates_refresh_history WHERE query_message_id = :id
        LIMIT 1
        """)
        .param("id", messageId).query((rs, row) -> refresh(rs)).optional();
  }

  @Override
  public Optional<Refresh> claimNext(Instant now) {
    UUID token = UUID.randomUUID();
    return jdbc.sql("""
        WITH candidate AS (
          SELECT wash_execution_id FROM reassignment_candidates_refresh
          WHERE ((status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
              OR (status = 'FAILED' AND next_attempt_at IS NOT NULL AND next_attempt_at <= :now))
            AND (claim_until IS NULL OR claim_until < :now)
          ORDER BY requested_at, wash_execution_id FOR UPDATE SKIP LOCKED LIMIT 1
        )
        UPDATE reassignment_candidates_refresh refresh
        SET claim_token = :token, claim_until = :claimUntil,
            attempt_count = refresh.attempt_count + 1, updated_at = :now
        FROM candidate WHERE refresh.wash_execution_id = candidate.wash_execution_id
        RETURNING refresh.*
        """).param("now", JdbcTimes.timestamp(now)).param("token", token)
        .param("claimUntil", JdbcTimes.timestamp(now.plus(CLAIM_DURATION)))
        .query((rs, row) -> refresh(rs)).optional();
  }

  @Override
  public boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt) {
    return jdbc.sql("""
        UPDATE reassignment_candidates_refresh SET status = 'PUBLISHED',
          published_at = :at, claim_token = NULL, claim_until = NULL,
          next_attempt_at = NULL, last_error_code = NULL, updated_at = :at
        WHERE query_message_id = :id AND status IN ('PENDING', 'FAILED')
          AND claim_token = :token
        """).param("at", JdbcTimes.timestamp(publishedAt)).param("id", messageId)
        .param("token", claimToken).update() == 1;
  }

  @Override
  public boolean markFailed(UUID messageId, UUID claimToken, String errorCode,
      Instant nextAttemptAt) {
    Instant at = Instant.now();
    return jdbc.sql("""
        UPDATE reassignment_candidates_refresh SET status = 'FAILED',
          last_error_code = :error, next_attempt_at = :next,
          claim_token = NULL, claim_until = NULL, updated_at = :at
        WHERE query_message_id = :id AND status IN ('PENDING', 'FAILED')
          AND claim_token = :token
        """).param("error", errorCode).param("next", JdbcTimes.timestamp(nextAttemptAt))
        .param("at", JdbcTimes.timestamp(at)).param("id", messageId).param("token", claimToken)
        .update() == 1;
  }

  @Override
  @Transactional
  public boolean applySnapshot(Refresh expected, UUID resultMessageId, Snapshot snapshot,
      Instant appliedAt) {
    if (!requireCurrent(expected, resultMessageId, "APPLIED", null, appliedAt)) return false;
    int written = jdbc.sql("""
        INSERT INTO reassignment_candidates_read_model (
          wash_execution_id, execution_version, result_message_id, query_message_id,
          correlation_id, snapshot_generated_at, fresh_until, updated_at)
        VALUES (:executionId, :version, :resultId, :queryId, :correlationId,
          :generatedAt, :freshUntil, :appliedAt)
        ON CONFLICT (wash_execution_id) DO UPDATE SET
          execution_version = EXCLUDED.execution_version,
          result_message_id = EXCLUDED.result_message_id,
          query_message_id = EXCLUDED.query_message_id,
          correlation_id = EXCLUDED.correlation_id,
          snapshot_generated_at = EXCLUDED.snapshot_generated_at,
          fresh_until = EXCLUDED.fresh_until, updated_at = EXCLUDED.updated_at
        WHERE reassignment_candidates_read_model.execution_version < EXCLUDED.execution_version
           OR (reassignment_candidates_read_model.execution_version = EXCLUDED.execution_version
             AND reassignment_candidates_read_model.snapshot_generated_at <= EXCLUDED.snapshot_generated_at)
        """).param("executionId", snapshot.washExecutionId())
        .param("version", snapshot.executionVersion()).param("resultId", resultMessageId)
        .param("queryId", expected.messageId()).param("correlationId", expected.correlationId())
        .param("generatedAt", JdbcTimes.timestamp(snapshot.snapshotGeneratedAt()))
        .param("freshUntil", JdbcTimes.timestamp(snapshot.freshUntil()))
        .param("appliedAt", JdbcTimes.timestamp(appliedAt)).update();
    if (written != 1) throw new IllegalArgumentException("older snapshot cannot overwrite a newer one");
    jdbc.sql("DELETE FROM reassignment_candidate_read_model WHERE wash_execution_id = :id")
        .param("id", snapshot.washExecutionId()).update();
    for (int index = 0; index < snapshot.candidates().size(); index++) {
      Candidate value = snapshot.candidates().get(index);
      jdbc.sql("""
          INSERT INTO reassignment_candidate_read_model (
            wash_execution_id, candidate_order, cabin_id, cabin_code, cabin_name,
            tank_id, tank_code, tank_name, available_capacity)
          VALUES (:executionId, :candidateOrder, :cabinId, :cabinCode, :cabinName,
            :tankId, :tankCode, :tankName, :capacity)
          """).param("executionId", snapshot.washExecutionId()).param("candidateOrder", index)
          .param("cabinId", value.cabinId()).param("cabinCode", value.cabinCode())
          .param("cabinName", value.cabinName()).param("tankId", value.tankId())
          .param("tankCode", value.tankCode()).param("tankName", value.tankName())
          .param("capacity", value.availableCapacity()).update();
    }
    return true;
  }

  @Override
  public boolean rejectRefresh(Refresh expected, UUID resultMessageId, String errorCode,
      Instant appliedAt) {
    return requireCurrent(expected, resultMessageId, "REJECTED", errorCode, appliedAt);
  }

  private boolean requireCurrent(Refresh expected, UUID resultMessageId, String status,
      String errorCode, Instant appliedAt) {
    int updated = jdbc.sql("""
        UPDATE reassignment_candidates_refresh r SET status = :status,
          result_message_id = :resultId, last_error_code = :error, updated_at = :at
        WHERE r.wash_execution_id = :executionId AND r.execution_version = :version
          AND r.query_message_id = :queryId AND r.correlation_id = :correlationId
          AND r.status IN ('PENDING', 'PUBLISHED', 'FAILED')
          AND EXISTS (SELECT 1 FROM wash_execution_read_model e
            WHERE e.wash_execution_id = r.wash_execution_id
              AND e.status = 'PENDING_REASSIGNMENT'
              AND e.execution_version = r.execution_version)
        """).param("status", status).param("resultId", resultMessageId).param("error", errorCode)
        .param("at", JdbcTimes.timestamp(appliedAt)).param("executionId", expected.washExecutionId())
        .param("version", expected.executionVersion()).param("queryId", expected.messageId())
        .param("correlationId", expected.correlationId()).update();
    return updated == 1;
  }

  @Override
  @Transactional
  public int cleanupInactive(Instant olderThan, int limit) {
    List<UUID> ids = jdbc.sql("""
        SELECT r.wash_execution_id FROM reassignment_candidates_refresh r
        LEFT JOIN wash_execution_read_model e ON e.wash_execution_id = r.wash_execution_id
        WHERE (e.wash_execution_id IS NULL OR e.status <> 'PENDING_REASSIGNMENT')
          AND r.updated_at < :olderThan
        ORDER BY r.updated_at, r.wash_execution_id LIMIT :limit
        """).param("olderThan", JdbcTimes.timestamp(olderThan)).param("limit", limit)
        .query((rs, row) -> rs.getObject("wash_execution_id", UUID.class)).list();
    for (UUID id : ids) {
      jdbc.sql("DELETE FROM reassignment_candidates_read_model WHERE wash_execution_id = :id")
          .param("id", id).update();
      jdbc.sql("DELETE FROM reassignment_candidates_refresh WHERE wash_execution_id = :id")
          .param("id", id).update();
    }
    int historyDeleted = jdbc.sql("""
        DELETE FROM reassignment_candidates_refresh_history WHERE query_message_id IN (
          SELECT query_message_id FROM reassignment_candidates_refresh_history
          WHERE retired_at < :olderThan ORDER BY retired_at, query_message_id LIMIT :limit)
        """).param("olderThan", JdbcTimes.timestamp(olderThan)).param("limit", limit).update();
    return ids.size() + historyDeleted;
  }

  private Snapshot snapshot(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("wash_execution_id", UUID.class);
    List<Candidate> values = jdbc.sql("""
        SELECT * FROM reassignment_candidate_read_model WHERE wash_execution_id = :id
        ORDER BY candidate_order
        """).param("id", id).query((candidateRs, row) -> new Candidate(
            candidateRs.getObject("cabin_id", UUID.class), candidateRs.getString("cabin_code"),
            candidateRs.getString("cabin_name"), candidateRs.getObject("tank_id", UUID.class),
            candidateRs.getString("tank_code"), candidateRs.getString("tank_name"),
            candidateRs.getInt("available_capacity"))).list();
    return new Snapshot(id, rs.getLong("execution_version"),
        rs.getObject("result_message_id", UUID.class),
        JdbcTimes.instant(rs, "snapshot_generated_at"), JdbcTimes.instant(rs, "fresh_until"), values);
  }

  private static Refresh refresh(ResultSet rs) throws SQLException {
    return new Refresh(rs.getObject("wash_execution_id", UUID.class),
        rs.getLong("execution_version"), rs.getObject("query_message_id", UUID.class),
        rs.getObject("correlation_id", UUID.class), rs.getString("status"),
        rs.getObject("claim_token", UUID.class), JdbcTimes.instant(rs, "claim_until"),
        rs.getInt("attempt_count"), JdbcTimes.instant(rs, "next_attempt_at"),
        JdbcTimes.instant(rs, "published_at"), rs.getString("last_error_code"),
        JdbcTimes.instant(rs, "requested_at"));
  }
}
