package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.ProjectionRepositories;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTimeSlotProjectionRepository implements ProjectionRepositories.TimeSlotRepository {
  private final JdbcTemplate jdbc;

  public JdbcTimeSlotProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public Optional<ProjectionRepositories.TimeSlotContext> findById(UUID id) {
    return jdbc.query(
        "SELECT * FROM appointment_time_slot_projection WHERE appointment_time_slot_id=?",
        this::map,
        id).stream().findFirst();
  }

  @Override
  public void upsert(ProjectionRepositories.TimeSlotContext c) {
    jdbc.update("""
        INSERT INTO appointment_time_slot_projection (
          appointment_time_slot_id,calendar_id,service_date,starts_at,ends_at,timezone,
          arrival_tolerance_until,status,source_version,updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT (appointment_time_slot_id) DO UPDATE SET
          calendar_id=excluded.calendar_id,
          service_date=excluded.service_date,
          starts_at=excluded.starts_at,
          ends_at=excluded.ends_at,
          timezone=excluded.timezone,
          arrival_tolerance_until=excluded.arrival_tolerance_until,
          status=excluded.status,
          source_version=excluded.source_version,
          updated_at=excluded.updated_at
        WHERE appointment_time_slot_projection.source_version < excluded.source_version
        """, c.appointmentTimeSlotId(), c.calendarId(), c.serviceDate(), c.startsAt(), c.endsAt(),
        c.timezone(), c.arrivalToleranceUntil(), c.status(), c.sourceVersion(), Instant.now());
  }

  private ProjectionRepositories.TimeSlotContext map(ResultSet rs, int row) throws SQLException {
    return new ProjectionRepositories.TimeSlotContext(
        rs.getObject("appointment_time_slot_id", UUID.class),
        rs.getObject("calendar_id", UUID.class),
        rs.getDate("service_date").toLocalDate(),
        rs.getTimestamp("starts_at").toInstant(),
        rs.getTimestamp("ends_at").toInstant(),
        rs.getString("timezone"),
        rs.getTimestamp("arrival_tolerance_until").toInstant(),
        rs.getString("status"),
        rs.getLong("source_version"));
  }
}
