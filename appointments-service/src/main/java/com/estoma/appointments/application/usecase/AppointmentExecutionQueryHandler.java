package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.port.in.AppointmentExecutionQueryUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentExecutionQueryHandler implements AppointmentExecutionQueryUseCase {
  private final AppointmentRepository appointments;
  private final AppointmentTimeSlotRepository slots;

  public AppointmentExecutionQueryHandler(AppointmentRepository appointments,
      AppointmentTimeSlotRepository slots) {
    this.appointments = appointments;
    this.slots = slots;
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public EntityResult get(UUID appointmentId, Instant snapshotGeneratedAt) {
    var appointment = appointments.findById(appointmentId).orElse(null);
    if (appointment == null) return EntityResult.notFound("APPOINTMENT_NOT_FOUND");
    var slot = slots.findById(appointment.appointmentTimeSlotId()).orElse(null);
    if (slot == null) return EntityResult.notFound("APPOINTMENT_TIME_SLOT_NOT_FOUND");
    return EntityResult.found(new ExecutionContext(appointment, slot));
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResult list(LocalDate serviceDate, UUID afterAppointmentId, int pageSize,
      Instant snapshotGeneratedAt) {
    var rows = appointments.findForServiceDatePage(serviceDate, afterAppointmentId, pageSize + 1);
    boolean hasMore = rows.size() > pageSize;
    var contexts = new ArrayList<ExecutionContext>(Math.min(rows.size(), pageSize));
    for (int index = 0; index < Math.min(rows.size(), pageSize); index++) {
      var appointment = rows.get(index);
      var slot = slots.findById(appointment.appointmentTimeSlotId())
          .orElseThrow(() -> new IllegalStateException(
              "Appointment references missing AppointmentTimeSlot "
                  + appointment.appointmentTimeSlotId()));
      contexts.add(new ExecutionContext(appointment, slot));
    }
    UUID next = hasMore && !contexts.isEmpty()
        ? contexts.getLast().appointment().appointmentId() : null;
    return new PageResult(snapshotGeneratedAt, serviceDate, contexts, next);
  }
}
