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
    return availableCapacity(existing, candidate, normalCapacity, journeyCapacity,
        immunocompromisedCapacity) > 0;
  }

  /**
   * Returns how many additional appointments of {@code candidate} fit under the same matching
   * rules used by the authoritative scheduling command.
   *
   * <p>NORMAL and JOURNEY intentionally expose the shared compatible capacity. They are not
   * independent buckets and their values must never be added by a consumer.
   */
  public static int availableCapacity(Collection<Appointment> existing, AppointmentType candidate,
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
      return Math.max(0, immunocompromisedCapacity - immunocompromisedDemand);
    }
    return Math.max(0, normalCapacity + journeyCapacity - sharedDemand);
  }

  public static boolean hasCapacity(Collection<Appointment> existing, Appointment candidate,
      int normalCapacity, int journeyCapacity, int immunocompromisedCapacity) {
    return canFit(existing, candidate.appointmentType(), normalCapacity, journeyCapacity,
        immunocompromisedCapacity);
  }
}
