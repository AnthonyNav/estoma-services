package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentTimeSlotStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAppointmentTimeSlotRepository implements AppointmentTimeSlotRepository {
  private static final String COLUMNS = "appointment_time_slot_id, source_schedule_id, service_date, starts_at, ends_at, timezone, booking_deadline_at, cancellation_deadline_at, arrival_tolerance_until, status, version";
  private final JdbcClient jdbc;
  public JdbcAppointmentTimeSlotRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public AppointmentTimeSlot save(AppointmentTimeSlot slot) {
    jdbc.sql("""
        INSERT INTO appointment_time_slots (appointment_time_slot_id, source_schedule_id, service_date, starts_at, ends_at, timezone, booking_deadline_at, cancellation_deadline_at, arrival_tolerance_until, status, version, created_at, updated_at)
        VALUES (:id, :source, :date, :starts, :ends, :timezone, :booking, :cancel, :arrival, :status, :version, now(), now())
        """)
        .param("id", slot.appointmentTimeSlotId()).param("source", slot.sourceScheduleId())
        .param("date", slot.serviceDate()).param("starts", Timestamps.toTimestamp(slot.startsAt()))
        .param("ends", Timestamps.toTimestamp(slot.endsAt())).param("timezone", slot.timezone().getId())
        .param("booking", Timestamps.toTimestamp(slot.bookingDeadlineAt()))
        .param("cancel", Timestamps.toTimestamp(slot.cancellationDeadlineAt()))
        .param("arrival", Timestamps.toTimestamp(slot.arrivalToleranceUntil()))
        .param("status", slot.status().name()).param("version", slot.version()).update();
    return slot;
  }

  @Override public Optional<AppointmentTimeSlot> findById(UUID id) { return find(id, false); }
  @Override public Optional<AppointmentTimeSlot> findByIdForUpdate(UUID id) { return find(id, true); }
  private Optional<AppointmentTimeSlot> find(UUID id, boolean lock) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM appointment_time_slots WHERE appointment_time_slot_id = :id" + (lock ? " FOR UPDATE" : ""))
        .param("id", id).query((rs, row) -> AppointmentTimeSlot.reconstitute(
            rs.getObject("appointment_time_slot_id", UUID.class), rs.getObject("source_schedule_id", UUID.class),
            rs.getObject("service_date", LocalDate.class), Timestamps.toInstant(rs, "starts_at"),
            Timestamps.toInstant(rs, "ends_at"), ZoneId.of(rs.getString("timezone")),
            Timestamps.toInstant(rs, "booking_deadline_at"), Timestamps.toInstant(rs, "cancellation_deadline_at"),
            Timestamps.toInstant(rs, "arrival_tolerance_until"), AppointmentTimeSlotStatus.valueOf(rs.getString("status")), rs.getLong("version")))
        .optional();
  }

  @Override public boolean updateIfVersion(AppointmentTimeSlot slot, long expectedVersion) {
    return jdbc.sql("""
        UPDATE appointment_time_slots SET status=:status, booking_deadline_at=:booking, cancellation_deadline_at=:cancel,
          arrival_tolerance_until=:arrival, version=:nextVersion, updated_at=now()
        WHERE appointment_time_slot_id=:id AND version=:expectedVersion
        """)
        .param("status", slot.status().name()).param("booking", Timestamps.toTimestamp(slot.bookingDeadlineAt()))
        .param("cancel", Timestamps.toTimestamp(slot.cancellationDeadlineAt())).param("arrival", Timestamps.toTimestamp(slot.arrivalToleranceUntil()))
        .param("nextVersion", slot.version()).param("id", slot.appointmentTimeSlotId()).param("expectedVersion", expectedVersion).update() == 1;
  }

  @Override public boolean cancelIfVersion(UUID id, long expectedVersion) {
    return jdbc.sql("UPDATE appointment_time_slots SET status='CANCELLED', version=version+1, updated_at=now() WHERE appointment_time_slot_id=:id AND version=:version AND status='ACTIVE'")
        .param("id", id).param("version", expectedVersion).update() == 1;
  }
}
