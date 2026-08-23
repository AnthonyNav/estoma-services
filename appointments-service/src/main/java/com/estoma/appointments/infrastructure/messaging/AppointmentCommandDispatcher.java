package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.CancelAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.in.CancelAppointmentUseCase;
import com.estoma.appointments.application.port.in.CancelExceptionalAppointmentAuthorizationUseCase;
import com.estoma.appointments.application.port.in.CreateAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.in.GrantExceptionalAppointmentAuthorizationUseCase;
import com.estoma.appointments.application.port.in.ScheduleAppointmentUseCase;
import org.springframework.stereotype.Component;

@Component
public final class AppointmentCommandDispatcher {
  private final ScheduleAppointmentUseCase schedule;
  private final CancelAppointmentUseCase cancel;
  private final GrantExceptionalAppointmentAuthorizationUseCase grant;
  private final CancelExceptionalAppointmentAuthorizationUseCase cancelAuthorization;
  private final CreateAppointmentTimeSlotUseCase createSlot;
  private final CancelAppointmentTimeSlotUseCase cancelSlot;

  public AppointmentCommandDispatcher(ScheduleAppointmentUseCase schedule, CancelAppointmentUseCase cancel,
      GrantExceptionalAppointmentAuthorizationUseCase grant,
      CancelExceptionalAppointmentAuthorizationUseCase cancelAuthorization,
      CreateAppointmentTimeSlotUseCase createSlot, CancelAppointmentTimeSlotUseCase cancelSlot) {
    this.schedule = schedule; this.cancel = cancel; this.grant = grant; this.cancelAuthorization = cancelAuthorization;
    this.createSlot = createSlot; this.cancelSlot = cancelSlot;
  }

  public ApplicationResult dispatch(CommandMessageMapper.MappedCommand mapped) {
    return switch (mapped.command()) {
      case ScheduleAppointmentUseCase.Command command -> schedule.schedule(command);
      case CancelAppointmentUseCase.Command command -> cancel.cancel(command);
      case GrantExceptionalAppointmentAuthorizationUseCase.Command command -> grant.grant(command);
      case CancelExceptionalAppointmentAuthorizationUseCase.Command command -> cancelAuthorization.cancel(command);
      case CreateAppointmentTimeSlotUseCase.Command command -> createSlot.create(command);
      case CancelAppointmentTimeSlotUseCase.Command command -> cancelSlot.cancel(command);
      default -> throw new InvalidMessageException("unsupported mapped command");
    };
  }
}
