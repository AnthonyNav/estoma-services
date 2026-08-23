package com.estoma.appointments.application.port.in;

/** Marker types make the PR1 input boundary explicit; behavior is implemented in PR2. */
public final class Pr1UseCasePorts {
  private Pr1UseCasePorts() {}
  public interface ScheduleAppointment {}
  public interface CancelAppointment {}
  public interface GrantExceptionalAppointmentAuthorization {}
  public interface CancelExceptionalAppointmentAuthorization {}
  public interface CreateAppointmentTimeSlot {}
  public interface CancelAppointmentTimeSlot {}
  public interface RecalculateAppointmentCapacityForTimeSlot {}
  public interface CancelScheduledAppointmentsForCapacityLoss {}
  public interface MarkAppointmentAsMissed {}
  public interface HandleWashArrivalRegistered {}
  public interface HandleWashEntryAuthorized {}
  public interface HandleWashEntryRejected {}
  public interface HandleWashExecutionStarted {}
  public interface HandleWashExecutionCompleted {}
  public interface HandleWashExecutionCancelled {}
}
