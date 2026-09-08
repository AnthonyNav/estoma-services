package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.WashCancellationProjectionPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcWashCancellationProjectionRepository implements WashCancellationProjectionPort {
  private final JdbcClient jdbc;

  public JdbcWashCancellationProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void projectCancellation(UUID washExecutionId, long executionVersion, Instant cancelledAt) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:executionId AS TEXT), 0))")
        .param("executionId", washExecutionId)
        .query()
        .singleRow();
    jdbc.sql("""
        UPDATE wash_resource_assignment_read_model ra
        SET released_at = :cancelledAt,
            source_version = :version,
            updated_at = GREATEST(ra.updated_at, :cancelledAt)
        WHERE ra.wash_execution_id = :executionId
          AND ra.released_at IS NULL
          AND ra.source_version <= :version
          AND EXISTS (
            SELECT 1
            FROM wash_execution_read_model e
            WHERE e.wash_execution_id = :executionId
              AND e.status = 'CANCELLED'
              AND e.execution_version = :version
              AND e.cancelled_at = :cancelledAt
          )
        """)
        .param("cancelledAt", JdbcTimes.timestamp(cancelledAt))
        .param("version", executionVersion)
        .param("executionId", washExecutionId)
        .update();
  }
}
