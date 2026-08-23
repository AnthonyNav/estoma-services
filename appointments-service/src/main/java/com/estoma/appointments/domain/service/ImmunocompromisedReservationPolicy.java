package com.estoma.appointments.domain.service;

import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.TimeInterval;
import java.time.Duration;
import java.util.Objects;

/** Symmetric preventive reservation policy for IMMUNOCOMPROMISED appointments. */
public final class ImmunocompromisedReservationPolicy {
  private static final Duration CLEANING = Duration.ofHours(3);
  private ImmunocompromisedReservationPolicy() {}

  public static TimeInterval reservation(AppointmentTimeSlot slot, AppointmentType type) {
    Objects.requireNonNull(slot, "slot");
    Objects.requireNonNull(type, "type");
    return new TimeInterval(slot.startsAt(), slot.endsAt().plus(CLEANING));
  }

  public static TimeInterval reservation(Appointment appointment, AppointmentTimeSlot slot) {
    return reservation(slot, appointment.appointmentType());
  }

  /**
   * Checks both directions. A preceding appointment whose expected clean-up
   * reaches the IMMUNO block conflicts just as a following appointment does.
   */
  public static boolean conflicts(AppointmentTimeSlot firstSlot, AppointmentType firstType,
      AppointmentTimeSlot secondSlot, AppointmentType secondType) {
    Objects.requireNonNull(firstSlot, "firstSlot");
    Objects.requireNonNull(secondSlot, "secondSlot");
    if (firstType != AppointmentType.IMMUNOCOMPROMISED
        && secondType != AppointmentType.IMMUNOCOMPROMISED) return false;
    return reservation(firstSlot, firstType).overlaps(reservation(secondSlot, secondType));
  }

  public static boolean conflicts(Appointment first, AppointmentTimeSlot firstSlot,
      Appointment second, AppointmentTimeSlot secondSlot) {
    return conflicts(firstSlot, first.appointmentType(), secondSlot, second.appointmentType());
  }
}
