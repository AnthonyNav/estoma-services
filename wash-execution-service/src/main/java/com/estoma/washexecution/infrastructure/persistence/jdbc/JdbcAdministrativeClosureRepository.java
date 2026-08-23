package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.ProjectionRepositories;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdministrativeClosureRepository implements ProjectionRepositories.AdministrativeClosureRepository {
  private final JdbcTemplate jdbc;

  public JdbcAdministrativeClosureRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public List<ProjectionRepositories.AdministrativeClosureProjection> findScheduledOverlapping(
      Instant startsAt, Instant endsAt) {
    return jdbc.query("""
        SELECT * FROM administrative_closure_projection
        WHERE status='SCHEDULED' AND starts_at < ? AND ends_at > ?
        ORDER BY starts_at,administrative_closure_id
        """, this::map, endsAt, startsAt);
  }

  @Override
  public void upsert(ProjectionRepositories.AdministrativeClosureProjection c) {
    jdbc.update("""
        INSERT INTO administrative_closure_projection(
          administrative_closure_id,calendar_id,scope,cabin_id,tank_id,starts_at,ends_at,status,source_version,updated_at)
        VALUES(?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(administrative_closure_id) DO UPDATE SET calendar_id=excluded.calendar_id,
          scope=excluded.scope,cabin_id=excluded.cabin_id,tank_id=excluded.tank_id,
          starts_at=excluded.starts_at,ends_at=excluded.ends_at,status=excluded.status,
          source_version=excluded.source_version,updated_at=excluded.updated_at
        WHERE administrative_closure_projection.source_version < excluded.source_version
        """, c.administrativeClosureId(), c.calendarId(), c.scope(), c.cabinId(), c.tankId(), c.startsAt(),
        c.endsAt(), c.status(), c.sourceVersion(), Instant.now());
  }

  private ProjectionRepositories.AdministrativeClosureProjection map(ResultSet rs, int row) throws SQLException {
    return new ProjectionRepositories.AdministrativeClosureProjection(
        rs.getObject("administrative_closure_id", UUID.class), rs.getObject("calendar_id", UUID.class),
        rs.getString("scope"), rs.getObject("cabin_id", UUID.class), rs.getObject("tank_id", UUID.class),
        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
        rs.getString("status"), rs.getLong("source_version"));
  }
}
