package com.estoma.appointments.domain.model;

public enum AppointmentStatus {
  SCHEDULED,
  CANCELLED,
  MISSED,
  ENTRY_REJECTED,
  IN_PROGRESS,
  COMPLETED;

  public boolean isTerminal() {
    return this == CANCELLED || this == MISSED || this == ENTRY_REJECTED || this == COMPLETED;
  }

  public boolean reservesCapacity() { return this == SCHEDULED || this == IN_PROGRESS; }
}
