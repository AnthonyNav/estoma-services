package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.CancelAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.in.CancelAppointmentUseCase;
import com.estoma.appointments.application.port.in.CancelExceptionalAppointmentAuthorizationUseCase;
import com.estoma.appointments.application.port.in.CreateAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.in.GrantExceptionalAppointmentAuthorizationUseCase;
import com.estoma.appointments.application.port.in.MarkAppointmentAsMissedUseCase;
import com.estoma.appointments.application.port.in.RecalculateAppointmentCapacityForTimeSlotUseCase;
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
  private final MarkAppointmentAsMissedUseCase markMissed;
  private final RecalculateAppointmentCapacityForTimeSlotUseCase recalculateCapacity;

  public AppointmentCommandDispatcher(ScheduleAppointmentUseCase schedule, CancelAppointmentUseCase cancel,
      GrantExceptionalAppointmentAuthorizationUseCase grant,
      CancelExceptionalAppointmentAuthorizationUseCase cancelAuthorization,
      CreateAppointmentTimeSlotUseCase createSlot, CancelAppointmentTimeSlotUseCase cancelSlot,
      MarkAppointmentAsMissedUseCase markMissed,
      RecalculateAppointmentCapacityForTimeSlotUseCase recalculateCapacity) {
    this.schedule = schedule;
    this.cancel = cancel;
    this.grant = grant;
    this.cancelAuthorization = cancelAuthorization;
    this.createSlot = createSlot;
    this.cancelSlot = cancelSlot;
    this.markMissed = markMissed;
    this.recalculateCapacity = recalculateCapacity;
  }

  public ApplicationResult dispatch(CommandMessageMapper.MappedCommand mapped) {
    return switch (mapped.command()) {
      case ScheduleAppointmentUseCase.Command command -> schedule.schedule(command);
      case CancelAppointmentUseCase.Command command -> cancel.cancel(command);
      case GrantExceptionalAppointmentAuthorizationUseCase.Command command -> grant.grant(command);
      case CancelExceptionalAppointmentAuthorizationUseCase.Command command -> cancelAuthorization.cancel(command);
      case CreateAppointmentTimeSlotUseCase.Command command -> createSlot.create(command);
      case CancelAppointmentTimeSlotUseCase.Command command -> cancelSlot.cancel(command);
      case MarkAppointmentAsMissedUseCase.Command command -> markMissed.mark(command);
      case RecalculateAppointmentCapacityForTimeSlotUseCase.Command command -> recalculateCapacity.recalculate(command);
      default -> throw new InvalidMessageException("unsupported mapped command");
    };
  }
}
