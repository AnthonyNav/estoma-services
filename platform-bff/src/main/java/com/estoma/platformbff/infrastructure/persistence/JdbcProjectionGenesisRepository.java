package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.ProjectionGenesisPort;
import com.estoma.platformbff.domain.projection.ProjectionState;
import com.estoma.platformbff.domain.projection.ProjectionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectionGenesisRepository implements ProjectionGenesisPort {
  private final JdbcClient jdbc;

  public JdbcProjectionGenesisRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ProjectionState> findStatus(String projectionName) {
    return jdbc.sql("SELECT * FROM projection_status WHERE projection_name = :projectionName")
        .param("projectionName", projectionName)
        .query((rs, rowNum) -> map(rs))
        .optional();
  }

  @Override
  public boolean exists(String markerName) {
    return jdbc.sql("""
        SELECT EXISTS(
          SELECT 1 FROM projection_genesis_markers WHERE marker_name = :markerName
        )
        """)
        .param("markerName", markerName)
        .query(Boolean.class)
        .single();
  }

  @Override
  public boolean claim(String markerName, Instant establishedAt) {
    return jdbc.sql("""
        INSERT INTO projection_genesis_markers(marker_name, established_at)
        VALUES (:markerName, :establishedAt)
        ON CONFLICT (marker_name) DO NOTHING
        """)
        .param("markerName", markerName)
        .param("establishedAt", JdbcTimes.timestamp(establishedAt))
        .update() == 1;
  }

  private static ProjectionState map(ResultSet rs) throws SQLException {
    return new ProjectionState(rs.getString("projection_name"),
        ProjectionStatus.valueOf(rs.getString("status")),
        JdbcTimes.instant(rs, "last_applied_at"), rs.getString("last_error_code"),
        rs.getLong("generation"), JdbcTimes.instant(rs, "updated_at"));
  }
}
