package com.estoma.appointments.domain.service;

import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentType;
import java.util.Collection;

/** Small deterministic matching policy; capacity is counted per appointment, never per instrument. */
public final class AppointmentCapacityMatcher {
  private AppointmentCapacityMatcher() {}

  public static boolean canFit(Collection<Appointment> existing, AppointmentType candidate,
      int normalCapacity, int journeyCapacity, int immunocompromisedCapacity) {
    int normal = 0, journey = 0, immuno = 0;
    for (Appointment appointment : existing) {
      if (!appointment.status().reservesCapacity()) continue;
      switch (appointment.appointmentType()) {
        case NORMAL -> normal++;
        case JOURNEY -> journey++;
        case IMMUNOCOMPROMISED -> immuno++;
      }
    }
    if (candidate == AppointmentType.IMMUNOCOMPROMISED) return immuno < immunocompromisedCapacity;
    if (immuno > 0) return false;
    if (candidate == AppointmentType.NORMAL) return normal < normalCapacity || journey < journeyCapacity;
    return journey < journeyCapacity || normal < normalCapacity;
  }

  public static boolean hasCapacity(Collection<Appointment> existing, Appointment candidate,
      int normalCapacity, int journeyCapacity, int immunocompromisedCapacity) {
    return canFit(existing, candidate.appointmentType(), normalCapacity, journeyCapacity, immunocompromisedCapacity);
  }
}
