package com.estoma.appointments.application.port.in;

import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Authoritative Appointments reads exposed only to the Wash Execution recovery adapter. */
public interface AppointmentExecutionQueryUseCase {
  EntityResult get(UUID appointmentId, Instant snapshotGeneratedAt);
  PageResult list(LocalDate serviceDate, UUID afterAppointmentId, int pageSize,
      Instant snapshotGeneratedAt);

  record ExecutionContext(Appointment appointment, AppointmentTimeSlot timeSlot) {}

  record EntityResult(Status status, String errorCode, ExecutionContext context) {
    public static EntityResult found(ExecutionContext context) {
      return new EntityResult(Status.FOUND, null, context);
    }
    public static EntityResult notFound(String errorCode) {
      return new EntityResult(Status.NOT_FOUND, errorCode, null);
    }
  }

  record PageResult(Instant snapshotGeneratedAt, LocalDate serviceDate,
      List<ExecutionContext> contexts, UUID nextAfterAppointmentId) {
    public PageResult {
      contexts = List.copyOf(contexts);
    }
  }

  enum Status { FOUND, NOT_FOUND }
}
