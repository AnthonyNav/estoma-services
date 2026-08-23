package com.estoma.appointments.domain.service;

import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentType;
import java.util.Collection;

/**
 * Deterministic feasibility policy for agenda capacity.
 *
 * <p>NORMAL and JOURNEY share the combined NORMAL/JOURNEY pool. Preference affects which physical
 * pool is selected later, not whether the combined demand can fit. IMMUNOCOMPROMISED uses its own
 * exclusive pool and therefore does not block NORMAL/JOURNEY demand merely by existing in the slot.
 */
public final class AppointmentCapacityMatcher {
  private AppointmentCapacityMatcher() {}

  public static boolean canFit(Collection<Appointment> existing, AppointmentType candidate,
      int normalCapacity, int journeyCapacity, int immunocompromisedCapacity) {
    if (normalCapacity < 0 || journeyCapacity < 0 || immunocompromisedCapacity < 0) {
      throw new IllegalArgumentException("capacity cannot be negative");
    }

    int sharedDemand = 0;
    int immunocompromisedDemand = 0;
    for (Appointment appointment : existing) {
      if (!appointment.status().reservesCapacity()) continue;
      if (appointment.appointmentType() == AppointmentType.IMMUNOCOMPROMISED) {
        immunocompromisedDemand++;
      } else {
        sharedDemand++;
      }
    }

    if (candidate == AppointmentType.IMMUNOCOMPROMISED) {
      return immunocompromisedDemand + 1 <= immunocompromisedCapacity;
    }
    return sharedDemand + 1 <= normalCapacity + journeyCapacity;
  }

  public static boolean hasCapacity(Collection<Appointment> existing, Appointment candidate,
      int normalCapacity, int journeyCapacity, int immunocompromisedCapacity) {
    return canFit(existing, candidate.appointmentType(), normalCapacity, journeyCapacity,
        immunocompromisedCapacity);
  }
}
