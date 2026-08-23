package com.estoma.appointments.application.port.out;

import com.estoma.appointments.domain.model.ExceptionalAppointmentAuthorization;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ExceptionalAppointmentAuthorizationRepository {
  ExceptionalAppointmentAuthorization save(ExceptionalAppointmentAuthorization authorization);
  Optional<ExceptionalAppointmentAuthorization> findById(UUID id);
  Optional<ExceptionalAppointmentAuthorization> findByIdForUpdate(UUID id);
  Optional<ExceptionalAppointmentAuthorization> findByStudentAndDate(UUID studentAccountId, LocalDate serviceDate);
  boolean updateIfVersion(ExceptionalAppointmentAuthorization authorization, long expectedVersion);
}
