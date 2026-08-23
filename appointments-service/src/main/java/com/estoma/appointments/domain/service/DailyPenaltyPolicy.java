package com.estoma.appointments.domain.service;

import com.estoma.appointments.domain.model.Appointment;
import java.util.Collection;

public final class DailyPenaltyPolicy {
  public static final int MAX_POINTS = 3;
  private DailyPenaltyPolicy() {}
  public static int pointsFor(Appointment appointment) { return appointment.points(); }
  public static int totalPoints(Collection<Appointment> appointments) {
    return appointments.stream().mapToInt(Appointment::points).sum();
  }
  public static boolean maySchedule(Collection<Appointment> appointments) {
    return totalPoints(appointments) < MAX_POINTS;
  }
}
