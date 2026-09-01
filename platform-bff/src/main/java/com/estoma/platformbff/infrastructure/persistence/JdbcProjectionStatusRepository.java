package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.ProjectionStatusRepository;
import com.estoma.platformbff.domain.projection.ProjectionState;
import com.estoma.platformbff.domain.projection.ProjectionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectionStatusRepository implements ProjectionStatusRepository {
  private final JdbcClient jdbc;

  public JdbcProjectionStatusRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void upsert(ProjectionState state) {
    jdbc.sql("""
        INSERT INTO projection_status (
          projection_name, status, last_applied_at, last_error_code, generation, updated_at
        ) VALUES (:projectionName, :status, :lastAppliedAt, :lastErrorCode, :generation, :updatedAt)
        ON CONFLICT (projection_name) DO UPDATE SET
          status = EXCLUDED.status,
          last_applied_at = EXCLUDED.last_applied_at,
          last_error_code = EXCLUDED.last_error_code,
          generation = EXCLUDED.generation,
          updated_at = EXCLUDED.updated_at
        WHERE projection_status.generation < EXCLUDED.generation
           OR (projection_status.generation = EXCLUDED.generation
               AND projection_status.updated_at <= EXCLUDED.updated_at)
        """)
        .param("projectionName", state.projectionName())
        .param("status", state.status().name())
        .param("lastAppliedAt", JdbcTimes.timestamp(state.lastAppliedAt()))
        .param("lastErrorCode", state.lastErrorCode())
        .param("generation", state.generation())
        .param("updatedAt", JdbcTimes.timestamp(state.updatedAt()))
        .update();
  }

  @Override
  public Optional<ProjectionState> findByName(String projectionName) {
    return jdbc.sql("SELECT * FROM projection_status WHERE projection_name = :projectionName")
        .param("projectionName", projectionName)
        .query((rs, rowNum) -> map(rs))
        .optional();
  }

  @Override
  public boolean claimBootstrapping(String projectionName, long expectedGeneration, Instant now) {
    return jdbc.sql("""
        UPDATE projection_status
        SET status = 'REBUILDING', generation = :nextGeneration,
            last_applied_at = NULL, last_error_code = NULL, updated_at = :updatedAt
        WHERE projection_name = :projectionName
          AND status = 'BOOTSTRAPPING'
          AND generation = :expectedGeneration
        """).param("projectionName", projectionName).param("expectedGeneration", expectedGeneration)
        .param("nextGeneration", expectedGeneration + 1).param("updatedAt", JdbcTimes.timestamp(now))
        .update() == 1;
  }

  private static ProjectionState map(ResultSet rs) throws SQLException {
    return new ProjectionState(rs.getString("projection_name"),
        ProjectionStatus.valueOf(rs.getString("status")),
        JdbcTimes.instant(rs, "last_applied_at"), rs.getString("last_error_code"),
        rs.getLong("generation"), JdbcTimes.instant(rs, "updated_at"));
  }
}
