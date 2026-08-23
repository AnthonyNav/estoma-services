package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.ProjectionRepositories;
import com.estoma.washexecution.domain.execution.AppointmentType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAppointmentProjectionRepository implements ProjectionRepositories.AppointmentRepository {
  private final JdbcTemplate jdbc;

  public JdbcAppointmentProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public Optional<ProjectionRepositories.AppointmentContext> findById(UUID id) {
    return jdbc.query("SELECT * FROM appointment_projection WHERE appointment_id=?", this::map, id).stream().findFirst();
  }

  @Override
  public void upsert(ProjectionRepositories.AppointmentContext c) {
    jdbc.update("""
        INSERT INTO appointment_projection (
          appointment_id,student_account_id,appointment_time_slot_id,course_section_id,appointment_type,
          instrument_count,piece_type,appointment_status,entry_authorized_at,source_version,updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT (appointment_id) DO UPDATE SET
          student_account_id=excluded.student_account_id,
          appointment_time_slot_id=excluded.appointment_time_slot_id,
          course_section_id=excluded.course_section_id,
          appointment_type=excluded.appointment_type,
          instrument_count=excluded.instrument_count,
          piece_type=excluded.piece_type,
          appointment_status=excluded.appointment_status,
          entry_authorized_at=excluded.entry_authorized_at,
          source_version=excluded.source_version,
          updated_at=excluded.updated_at
        WHERE appointment_projection.source_version < excluded.source_version
        """, c.appointmentId(), c.studentAccountId(), c.appointmentTimeSlotId(), c.courseSectionId(),
        c.appointmentType().name(), c.instrumentCount(), c.pieceType(), c.status(), c.entryAuthorizedAt(),
        c.sourceVersion(), Instant.now());
  }

  private ProjectionRepositories.AppointmentContext map(ResultSet rs, int row) throws SQLException {
    return new ProjectionRepositories.AppointmentContext(
        rs.getObject("appointment_id", UUID.class), rs.getObject("student_account_id", UUID.class),
        rs.getObject("appointment_time_slot_id", UUID.class), rs.getObject("course_section_id", UUID.class),
        AppointmentType.valueOf(rs.getString("appointment_type")), rs.getInt("instrument_count"),
        rs.getString("piece_type"), rs.getString("appointment_status"), instant(rs, "entry_authorized_at"),
        rs.getLong("source_version"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
