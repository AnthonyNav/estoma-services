package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentStatus;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.CancellationSubreason;
import com.estoma.appointments.domain.model.CancellationType;
import com.estoma.appointments.domain.model.PieceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAppointmentRepository implements AppointmentRepository {
  private static final String COLUMNS = "appointment_id, appointment_time_slot_id, student_account_id, course_section_id, appointment_type, instrument_count, piece_type, status, entry_authorized_at, exceptional_authorization_id, cancellation_type, cancellation_subreason, created_at, updated_at, version";
  private final JdbcClient jdbc;
  public JdbcAppointmentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public Appointment save(Appointment a) {
    jdbc.sql("""
      INSERT INTO appointments (appointment_id, appointment_time_slot_id, student_account_id, course_section_id, appointment_type, instrument_count, piece_type, status, entry_authorized_at, exceptional_authorization_id, cancellation_type, cancellation_subreason, created_at, updated_at, version)
      VALUES (:id,:slot,:student,:course,:type,:count,:piece,:status,:entry,:authorization,:cancelType,:cancelSubreason,:created,:updated,:version)
      """)
      .param("id", a.appointmentId()).param("slot", a.appointmentTimeSlotId()).param("student", a.studentAccountId())
      .param("course", a.courseSectionId()).param("type", a.appointmentType().name()).param("count", a.instrumentCount())
      .param("piece", a.pieceType().name()).param("status", a.status().name()).param("entry", Timestamps.toTimestamp(a.entryAuthorizedAt()))
      .param("authorization", a.exceptionalAuthorizationId()).param("cancelType", a.cancellationType() == null ? null : a.cancellationType().name())
      .param("cancelSubreason", a.cancellationSubreason() == null ? null : a.cancellationSubreason().name())
      .param("created", Timestamps.toTimestamp(a.createdAt())).param("updated", Timestamps.toTimestamp(a.updatedAt())).param("version", a.version()).update();
    return a;
  }
  @Override public Optional<Appointment> findById(UUID id) { return find("appointment_id = :id", "id", id, false); }
  @Override public Optional<Appointment> findByIdForUpdate(UUID id) { return find("appointment_id = :id", "id", id, true); }
  private Optional<Appointment> find(String predicate, String param, Object value, boolean lock) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM appointments WHERE " + predicate + (lock ? " FOR UPDATE" : ""))
        .param(param, value).query(JdbcAppointmentRepository::map).optional();
  }
  private static Appointment map(ResultSet rs, int row) throws SQLException {
    String cancellationType = rs.getString("cancellation_type");
    String cancellationSubreason = rs.getString("cancellation_subreason");
    return Appointment.reconstitute(rs.getObject("appointment_id", UUID.class), rs.getObject("appointment_time_slot_id", UUID.class),
        rs.getObject("student_account_id", UUID.class), rs.getObject("course_section_id", UUID.class),
        AppointmentType.valueOf(rs.getString("appointment_type")), rs.getInt("instrument_count"), PieceType.valueOf(rs.getString("piece_type")),
        AppointmentStatus.valueOf(rs.getString("status")), Timestamps.toInstant(rs, "entry_authorized_at"),
        rs.getObject("exceptional_authorization_id", UUID.class), cancellationType == null ? null : CancellationType.valueOf(cancellationType),
        cancellationSubreason == null ? null : CancellationSubreason.valueOf(cancellationSubreason), Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"), rs.getLong("version"));
  }
  @Override public boolean updateIfVersion(Appointment a, long expectedVersion) {
    return jdbc.sql("""
      UPDATE appointments SET status=:status, entry_authorized_at=:entry, cancellation_type=:cancelType,
        cancellation_subreason=:cancelSubreason, updated_at=:updated, version=:nextVersion
      WHERE appointment_id=:id AND version=:expectedVersion
      """)
      .param("status", a.status().name()).param("entry", Timestamps.toTimestamp(a.entryAuthorizedAt()))
      .param("cancelType", a.cancellationType() == null ? null : a.cancellationType().name())
      .param("cancelSubreason", a.cancellationSubreason() == null ? null : a.cancellationSubreason().name())
      .param("updated", Timestamps.toTimestamp(a.updatedAt())).param("nextVersion", a.version()).param("id", a.appointmentId()).param("expectedVersion", expectedVersion).update() == 1;
  }
  @Override public long countActiveForStudent(UUID student, LocalDate date) {
    return jdbc.sql("SELECT count(*) FROM appointments a JOIN appointment_time_slots s ON s.appointment_time_slot_id=a.appointment_time_slot_id WHERE a.student_account_id=:student AND s.service_date=:date AND a.status IN ('SCHEDULED','IN_PROGRESS')")
        .param("student", student).param("date", date).query(Long.class).single();
  }
  @Override public long countCompletedForStudent(UUID student, LocalDate date) {
    return jdbc.sql("SELECT count(*) FROM appointments a JOIN appointment_time_slots s ON s.appointment_time_slot_id=a.appointment_time_slot_id WHERE a.student_account_id=:student AND s.service_date=:date AND a.status='COMPLETED'")
        .param("student", student).param("date", date).query(Long.class).single();
  }
  @Override public List<Appointment> findForSlot(UUID slotId, boolean lock) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM appointments WHERE appointment_time_slot_id=:slot ORDER BY created_at DESC, appointment_id DESC" + (lock ? " FOR UPDATE" : ""))
        .param("slot", slotId).query(JdbcAppointmentRepository::map).list();
  }
  @Override public List<Appointment> findForStudentAndDate(UUID student, LocalDate date) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM appointments a JOIN appointment_time_slots s ON s.appointment_time_slot_id=a.appointment_time_slot_id WHERE a.student_account_id=:student AND s.service_date=:date ORDER BY a.created_at")
        .param("student", student).param("date", date).query(JdbcAppointmentRepository::map).list();
  }
  @Override public Optional<Appointment> findActiveByExceptionalAuthorizationForUpdate(UUID authorizationId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM appointments WHERE exceptional_authorization_id=:authorization AND NOT (status='CANCELLED' AND cancellation_type='CLINIC') FOR UPDATE")
        .param("authorization", authorizationId).query(JdbcAppointmentRepository::map).optional();
  }
  @Override public List<Appointment> findByExceptionalAuthorization(UUID authorizationId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM appointments WHERE exceptional_authorization_id=:authorization ORDER BY created_at")
        .param("authorization", authorizationId).query(JdbcAppointmentRepository::map).list();
  }
  @Override public List<Appointment> findByStatus(AppointmentStatus status) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM appointments WHERE status=:status ORDER BY created_at").param("status", status.name()).query(JdbcAppointmentRepository::map).list();
  }
}
