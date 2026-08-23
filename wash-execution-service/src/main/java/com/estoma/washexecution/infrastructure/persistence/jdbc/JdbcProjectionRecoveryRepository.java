package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.ProjectionRecoveryRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectionRecoveryRepository implements ProjectionRecoveryRepository {
  private final JdbcTemplate jdbc;

  public JdbcProjectionRecoveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public Optional<RecoveryState> find(String projectionName) {
    return jdbc.query("""
        SELECT projection_name,source_logical_component_id,readiness_status,last_error,updated_at
        FROM projection_recovery_state WHERE projection_name=?
        """, (rs, row) -> new RecoveryState(
            rs.getString("projection_name"), rs.getString("source_logical_component_id"),
            rs.getString("readiness_status"), rs.getString("last_error"),
            rs.getTimestamp("updated_at").toInstant()), projectionName).stream().findFirst();
  }

  @Override
  public void mark(String projectionName, String source, String status, String error, Instant updatedAt) {
    jdbc.update("""
        INSERT INTO projection_recovery_state(
          projection_name,source_logical_component_id,readiness_status,last_successful_rebuild_at,last_error,updated_at)
        VALUES(?,?,?,CASE WHEN ?='READY' THEN ? ELSE NULL END,?,?)
        ON CONFLICT(projection_name) DO UPDATE SET source_logical_component_id=excluded.source_logical_component_id,
          readiness_status=excluded.readiness_status,
          last_successful_rebuild_at=CASE WHEN excluded.readiness_status='READY' THEN excluded.updated_at
            ELSE projection_recovery_state.last_successful_rebuild_at END,
          last_error=excluded.last_error,updated_at=excluded.updated_at
        """, projectionName, source, status, status, updatedAt, error, updatedAt);
  }
}
