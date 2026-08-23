package com.estoma.appointments.domain.service;

import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentStatus;
import com.estoma.appointments.domain.model.CancellationType;

public final class ExceptionalAuthorizationConsumptionPolicy {
  private ExceptionalAuthorizationConsumptionPolicy() {}
  public static boolean consumes(Appointment appointment) { return appointment.consumesExceptionalAuthorization(); }
  public static boolean restoresOnClinicCancellation(Appointment appointment) {
    return appointment.exceptionalAuthorizationId() != null
        && appointment.status() == AppointmentStatus.CANCELLED
        && appointment.cancellationType() == CancellationType.CLINIC;
  }
}
