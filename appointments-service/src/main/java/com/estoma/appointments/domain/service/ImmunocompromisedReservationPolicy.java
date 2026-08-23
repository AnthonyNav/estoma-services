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

  /** Planned reservation exists only for IMMUNOCOMPROMISED appointments. */
  public static TimeInterval reservation(AppointmentTimeSlot slot, AppointmentType type) {
    Objects.requireNonNull(slot, "slot");
    Objects.requireNonNull(type, "type");
    if (type != AppointmentType.IMMUNOCOMPROMISED) {
      throw new IllegalArgumentException("preventive cleaning reservation applies only to IMMUNOCOMPROMISED");
    }
    return new TimeInterval(slot.startsAt(), slot.endsAt().plus(CLEANING));
  }

  public static TimeInterval reservation(Appointment appointment, AppointmentTimeSlot slot) {
    return reservation(slot, appointment.appointmentType());
  }

  /** Two planned preventive windows conflict only when both appointments are IMMUNO. */
  public static boolean conflicts(AppointmentTimeSlot firstSlot, AppointmentType firstType,
      AppointmentTimeSlot secondSlot, AppointmentType secondType) {
    Objects.requireNonNull(firstSlot, "firstSlot");
    Objects.requireNonNull(secondSlot, "secondSlot");
    Objects.requireNonNull(firstType, "firstType");
    Objects.requireNonNull(secondType, "secondType");
    if (firstType != AppointmentType.IMMUNOCOMPROMISED
        || secondType != AppointmentType.IMMUNOCOMPROMISED) return false;
    return reservation(firstSlot, firstType).overlaps(reservation(secondSlot, secondType));
  }

  public static boolean conflicts(Appointment first, AppointmentTimeSlot firstSlot,
      Appointment second, AppointmentTimeSlot secondSlot) {
    return conflicts(firstSlot, first.appointmentType(), secondSlot, second.appointmentType());
  }
}
