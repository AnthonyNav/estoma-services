package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.ProjectionRepositories;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.CabinType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcResourceProjectionRepository implements ProjectionRepositories.ResourceProjectionRepository {
  private final JdbcTemplate jdbc;

  public JdbcResourceProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public List<ProjectionRepositories.ResourceCandidate> findCandidates(AppointmentType type, UUID slotId) {
    return jdbc.query("""
        SELECT c.cabin_id,t.tank_id,c.code AS cabin_code,t.code AS tank_code,c.cabin_type,
          c.status AS cabin_status,t.status AS tank_status,t.configured_capacity,
          (SELECT count(*) FROM resource_assignments a
            WHERE a.tank_id=t.tank_id AND a.appointment_time_slot_id=? AND a.released_at IS NULL) AS active_count,
          COALESCE((SELECT min(p.priority_order) FROM resource_priority_projection p
            WHERE p.appointment_type=? AND p.cabin_type=c.cabin_type AND p.status='ACTIVE'),
            CASE
              WHEN ?='IMMUNOCOMPROMISED' AND c.cabin_type='IMMUNOCOMPROMISED' THEN 1
              WHEN ?='NORMAL' AND c.cabin_type='NORMAL' THEN 1
              WHEN ?='JOURNEY' AND c.cabin_type='JOURNEY' THEN 1
              WHEN ? IN ('NORMAL','JOURNEY') AND c.cabin_type IN ('NORMAL','JOURNEY') THEN 2
              ELSE 999
            END) AS priority_order
        FROM cabin_projection c
        JOIN tank_projection t ON t.cabin_id=c.cabin_id
        WHERE c.status='ACTIVE' AND t.status='ACTIVE'
          AND ((?='IMMUNOCOMPROMISED' AND c.cabin_type='IMMUNOCOMPROMISED')
            OR (?='NORMAL' AND c.cabin_type IN ('NORMAL','JOURNEY'))
            OR (?='JOURNEY' AND c.cabin_type IN ('JOURNEY','NORMAL')))
        ORDER BY priority_order,c.code,t.code,c.cabin_id,t.tank_id
        """, this::mapCandidate, slotId, type.name(), type.name(), type.name(), type.name(), type.name(),
        type.name(), type.name(), type.name());
  }

  @Override
  public Optional<ProjectionRepositories.ResourceCandidate> findCandidate(
      UUID cabinId, UUID tankId, UUID slotId) {
    return jdbc.query("""
        SELECT c.cabin_id,t.tank_id,c.code AS cabin_code,t.code AS tank_code,c.cabin_type,
          c.status AS cabin_status,t.status AS tank_status,t.configured_capacity,
          (SELECT count(*) FROM resource_assignments a
            WHERE a.tank_id=t.tank_id AND a.appointment_time_slot_id=? AND a.released_at IS NULL) AS active_count,
          1 AS priority_order
        FROM cabin_projection c JOIN tank_projection t ON t.cabin_id=c.cabin_id
        WHERE c.cabin_id=? AND t.tank_id=?
        """, this::mapCandidate, slotId, cabinId, tankId).stream().findFirst();
  }

  @Override
  public Optional<ProjectionRepositories.CabinProjection> findCabin(UUID cabinId) {
    return jdbc.query(
        "SELECT * FROM cabin_projection WHERE cabin_id=?",
        this::mapCabin,
        cabinId).stream().findFirst();
  }

  @Override
  public Optional<ProjectionRepositories.TankProjection> findTank(UUID tankId) {
    return jdbc.query(
        "SELECT * FROM tank_projection WHERE tank_id=?",
        this::mapTank,
        tankId).stream().findFirst();
  }

  @Override
  public void upsertCabin(ProjectionRepositories.CabinProjection c) {
    jdbc.update("""
        INSERT INTO cabin_projection(cabin_id,code,name,cabin_type,status,source_version,updated_at)
        VALUES(?,?,?,?,?,?,?)
        ON CONFLICT(cabin_id) DO UPDATE SET code=excluded.code,name=excluded.name,
          cabin_type=excluded.cabin_type,status=excluded.status,source_version=excluded.source_version,
          updated_at=excluded.updated_at
        WHERE cabin_projection.source_version < excluded.source_version
        """, c.cabinId(), c.code(), c.name(), c.type().name(), c.status(), c.sourceVersion(),
        JdbcTemporal.timestamp(Instant.now()));
  }

  @Override
  public void upsertTank(ProjectionRepositories.TankProjection t) {
    jdbc.update("""
        INSERT INTO tank_projection(tank_id,cabin_id,code,name,configured_capacity,status,source_version,updated_at)
        VALUES(?,?,?,?,?,?,?,?)
        ON CONFLICT(tank_id) DO UPDATE SET cabin_id=excluded.cabin_id,code=excluded.code,name=excluded.name,
          configured_capacity=excluded.configured_capacity,status=excluded.status,
          source_version=excluded.source_version,updated_at=excluded.updated_at
        WHERE tank_projection.source_version < excluded.source_version
        """, t.tankId(), t.cabinId(), t.code(), t.name(), t.configuredCapacity(), t.status(),
        t.sourceVersion(), JdbcTemporal.timestamp(Instant.now()));
  }

  @Override
  public void upsertPriority(ProjectionRepositories.ResourcePriorityProjection p) {
    jdbc.update("""
        INSERT INTO resource_priority_projection(resource_priority_id,appointment_type,cabin_type,usage_mode,
          priority_order,status,source_version,updated_at)
        VALUES(?,?,?,?,?,?,?,?)
        ON CONFLICT(resource_priority_id) DO UPDATE SET appointment_type=excluded.appointment_type,
          cabin_type=excluded.cabin_type,usage_mode=excluded.usage_mode,priority_order=excluded.priority_order,
          status=excluded.status,source_version=excluded.source_version,updated_at=excluded.updated_at
        WHERE resource_priority_projection.source_version < excluded.source_version
        """, p.resourcePriorityId(), p.appointmentType().name(), p.cabinType().name(), p.usageMode(),
        p.priorityOrder(), p.status(), p.sourceVersion(), JdbcTemporal.timestamp(Instant.now()));
  }

  private ProjectionRepositories.ResourceCandidate mapCandidate(ResultSet rs, int row) throws SQLException {
    return new ProjectionRepositories.ResourceCandidate(
        rs.getObject("cabin_id", UUID.class), rs.getObject("tank_id", UUID.class),
        rs.getString("cabin_code"), rs.getString("tank_code"), CabinType.valueOf(rs.getString("cabin_type")),
        rs.getString("cabin_status"), rs.getString("tank_status"), rs.getInt("configured_capacity"),
        rs.getInt("active_count"), rs.getInt("priority_order"));
  }

  private ProjectionRepositories.CabinProjection mapCabin(ResultSet rs, int row) throws SQLException {
    return new ProjectionRepositories.CabinProjection(
        rs.getObject("cabin_id", UUID.class), rs.getString("code"), rs.getString("name"),
        CabinType.valueOf(rs.getString("cabin_type")), rs.getString("status"), rs.getLong("source_version"));
  }

  private ProjectionRepositories.TankProjection mapTank(ResultSet rs, int row) throws SQLException {
    return new ProjectionRepositories.TankProjection(
        rs.getObject("tank_id", UUID.class), rs.getObject("cabin_id", UUID.class), rs.getString("code"),
        rs.getString("name"), rs.getInt("configured_capacity"), rs.getString("status"),
        rs.getLong("source_version"));
  }
}
