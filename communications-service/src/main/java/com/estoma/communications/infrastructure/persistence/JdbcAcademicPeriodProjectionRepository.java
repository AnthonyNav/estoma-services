package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.AcademicPeriodProjectionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persists owner-versioned Academic period facts and a fenced recovery state. */
@Repository
class JdbcAcademicPeriodProjectionRepository implements AcademicPeriodProjectionRepository {

  private final JdbcClient jdbc;

  JdbcAcademicPeriodProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean applyActive(ActiveAcademicPeriod period, Instant sourceOccurredAt, Instant now) {
    upsertActive(period, sourceOccurredAt, now);

    int updated = jdbc.sql("""
        UPDATE academic_period_projection_state
        SET readiness = 'READY',
            active_academic_period_id = :id,
            active_source_version = :sourceVersion,
            active_source_occurred_at = :sourceOccurredAt,
            recovery_query_message_id = NULL,
            recovery_requested_at = NULL,
            updated_at = :updatedAt
        WHERE state_key = 1
          AND EXISTS (
            SELECT 1 FROM academic_period_projections p
            WHERE p.academic_period_id = :id AND p.status = 'ACTIVE' AND p.source_version = :sourceVersion
          )
          AND (active_academic_period_id IS NULL OR active_academic_period_id = :id)
        """)
        .param("id", period.academicPeriodId())
        .param("sourceVersion", period.sourceVersion())
        .param("sourceOccurredAt", java.sql.Timestamp.from(sourceOccurredAt))
        .param("updatedAt", java.sql.Timestamp.from(now))
        .update();
    if (updated == 1) {
      return false;
    }
    return jdbc.sql("""
        SELECT EXISTS (
          SELECT 1
          FROM academic_period_projection_state s
          JOIN academic_period_projections p ON p.academic_period_id = :id
          WHERE s.state_key = 1
            AND s.readiness <> 'BOOTSTRAPPING'
            AND s.active_academic_period_id IS NOT NULL
            AND s.active_academic_period_id <> :id
            AND p.status = 'ACTIVE'
            AND p.source_version = :sourceVersion
        )
        """)
        .param("id", period.academicPeriodId())
        .param("sourceVersion", period.sourceVersion())
        .query(Boolean.class)
        .single();
  }

  @Override
  public void recordRecoveryActive(ActiveAcademicPeriod period, Instant sourceOccurredAt, Instant now) {
    upsertActive(period, sourceOccurredAt, now);
  }

  private void upsertActive(ActiveAcademicPeriod period, Instant sourceOccurredAt, Instant now) {
    jdbc.sql("""
        INSERT INTO academic_period_projections (
          academic_period_id, code, start_date, end_date, status, source_version, source_occurred_at, updated_at
        ) VALUES (:id, :code, :startDate, :endDate, 'ACTIVE', :version, :sourceOccurredAt, :updatedAt)
        ON CONFLICT (academic_period_id) DO UPDATE SET
          code = EXCLUDED.code,
          start_date = EXCLUDED.start_date,
          end_date = EXCLUDED.end_date,
          status = 'ACTIVE',
          source_version = EXCLUDED.source_version,
          source_occurred_at = EXCLUDED.source_occurred_at,
          updated_at = EXCLUDED.updated_at
        WHERE academic_period_projections.source_version < EXCLUDED.source_version
        """)
        .param("id", period.academicPeriodId())
        .param("code", period.code())
        .param("startDate", period.startDate())
        .param("endDate", period.endDate())
        .param("version", period.sourceVersion())
        .param("sourceOccurredAt", java.sql.Timestamp.from(sourceOccurredAt))
        .param("updatedAt", java.sql.Timestamp.from(now))
        .update();
  }

  @Override
  public void applyClosed(UUID academicPeriodId, int sourceVersion, Instant sourceOccurredAt, Instant now) {
    jdbc.sql("""
        INSERT INTO academic_period_projections (
          academic_period_id, code, start_date, end_date, status, source_version, source_occurred_at, updated_at
        ) VALUES (:id, NULL, NULL, NULL, 'CLOSED', :version, :sourceOccurredAt, :updatedAt)
        ON CONFLICT (academic_period_id) DO UPDATE SET
          status = 'CLOSED',
          source_version = EXCLUDED.source_version,
          source_occurred_at = EXCLUDED.source_occurred_at,
          updated_at = EXCLUDED.updated_at
        WHERE academic_period_projections.source_version < EXCLUDED.source_version
        """)
        .param("id", academicPeriodId)
        .param("version", sourceVersion)
        .param("sourceOccurredAt", java.sql.Timestamp.from(sourceOccurredAt))
        .param("updatedAt", java.sql.Timestamp.from(now))
        .update();

    jdbc.sql("""
        UPDATE academic_period_projection_state
        SET readiness = 'READY',
            active_academic_period_id = NULL,
            active_source_version = NULL,
            active_source_occurred_at = :sourceOccurredAt,
            recovery_query_message_id = NULL,
            recovery_requested_at = NULL,
            updated_at = :updatedAt
        WHERE state_key = 1
          AND active_academic_period_id = :id
          AND EXISTS (
            SELECT 1 FROM academic_period_projections p
            WHERE p.academic_period_id = :id AND p.status = 'CLOSED' AND p.source_version = :sourceVersion
          )
          AND (active_source_version IS NULL OR active_source_version < :sourceVersion)
        """)
        .param("id", academicPeriodId)
        .param("sourceVersion", sourceVersion)
        .param("sourceOccurredAt", java.sql.Timestamp.from(sourceOccurredAt))
        .param("updatedAt", java.sql.Timestamp.from(now))
        .update();
  }

  @Override
  public Optional<ActiveAcademicPeriod> findAuthoritativeActive() {
    return jdbc.sql("""
        SELECT p.academic_period_id, p.code, p.start_date, p.end_date, p.source_version
        FROM academic_period_projection_state s
        JOIN academic_period_projections p ON p.academic_period_id = s.active_academic_period_id
        WHERE s.state_key = 1 AND s.readiness = 'READY' AND p.status = 'ACTIVE'
        """)
        .query((rs, row) -> new ActiveAcademicPeriod(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class),
            rs.getObject(4, LocalDate.class), rs.getInt(5)))
        .optional();
  }

  @Override
  public String readiness() {
    return jdbc.sql("SELECT readiness FROM academic_period_projection_state WHERE state_key = 1")
        .query(String.class)
        .single();
  }

  @Override
  public void startRecovery(UUID queryMessageId, Instant now) {
    jdbc.sql("""
        UPDATE academic_period_projection_state
        SET readiness = 'BOOTSTRAPPING', recovery_query_message_id = :queryMessageId,
            recovery_requested_at = :now, updated_at = :now
        WHERE state_key = 1
        """)
        .param("queryMessageId", queryMessageId)
        .param("now", java.sql.Timestamp.from(now))
        .update();
  }

  @Override
  public boolean retryRecoveryIfOverdue(UUID queryMessageId, Instant now, Instant overdueBefore) {
    return jdbc.sql("""
        UPDATE academic_period_projection_state
        SET recovery_query_message_id = :queryMessageId, recovery_requested_at = :now, updated_at = :now
        WHERE state_key = 1 AND readiness = 'BOOTSTRAPPING' AND recovery_requested_at < :overdueBefore
        """)
        .param("queryMessageId", queryMessageId)
        .param("now", java.sql.Timestamp.from(now))
        .param("overdueBefore", java.sql.Timestamp.from(overdueBefore))
        .update() == 1;
  }

  @Override
  public boolean completeRecovery(
      UUID queryMessageId, Optional<ActiveAcademicPeriod> period, Instant sourceOccurredAt, Instant now) {
    return jdbc.sql("""
        UPDATE academic_period_projection_state
        SET readiness = 'READY',
            active_academic_period_id = :activeAcademicPeriodId,
            active_source_version = :activeSourceVersion,
            active_source_occurred_at = CASE
              WHEN :hasActive THEN CAST(:sourceOccurredAt AS timestamptz) ELSE NULL
            END,
            recovery_query_message_id = NULL,
            recovery_requested_at = NULL,
            updated_at = :updatedAt
        WHERE state_key = 1 AND recovery_query_message_id = :queryMessageId
        """)
        .param("activeAcademicPeriodId", period.map(ActiveAcademicPeriod::academicPeriodId).orElse(null))
        .param("activeSourceVersion", period.map(ActiveAcademicPeriod::sourceVersion).orElse(null))
        .param("hasActive", period.isPresent())
        .param("sourceOccurredAt", java.sql.Timestamp.from(sourceOccurredAt))
        .param("updatedAt", java.sql.Timestamp.from(now))
        .param("queryMessageId", queryMessageId)
        .update() == 1;
  }
}
