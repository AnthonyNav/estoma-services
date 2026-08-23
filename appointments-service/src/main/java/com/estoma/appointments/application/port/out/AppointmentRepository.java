package com.estoma.appointments.application.port.out;

import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository {
  Appointment save(Appointment appointment);
  Optional<Appointment> findById(UUID id);
  Optional<Appointment> findByIdForUpdate(UUID id);
  boolean updateIfVersion(Appointment appointment, long expectedVersion);
  long countActiveForStudent(UUID studentAccountId, LocalDate serviceDate);
  long countCompletedForStudent(UUID studentAccountId, LocalDate serviceDate);
  List<Appointment> findForSlot(UUID slotId, boolean forUpdate);
  List<Appointment> findForStudentAndDate(UUID studentAccountId, LocalDate serviceDate);
  Optional<Appointment> findActiveByExceptionalAuthorizationForUpdate(UUID authorizationId);
  List<Appointment> findByExceptionalAuthorization(UUID authorizationId);
  List<Appointment> findByStatus(AppointmentStatus status);
  List<Appointment> findMissedCandidates(Instant toleranceEndedBefore, int limit);
  List<Appointment> findForServiceDatePage(LocalDate serviceDate, UUID afterAppointmentId, int limit);

  /** Active appointments across slots on a service date, used by symmetric IMMUNO checks. */
  default List<Appointment> findActiveOnDate(LocalDate serviceDate) { return List.of(); }
}
