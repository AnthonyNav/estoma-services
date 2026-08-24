package com.estoma.appointments.application.model;

import java.util.Objects;
import java.util.UUID;

/** Causal metadata carried only by CleaningPeriodStarted-derived capacity recalculations. */
public record CapacityCausalContext(UUID washExecutionId, UUID appointmentId, String cleaningTrigger) {
  public CapacityCausalContext {
    Objects.requireNonNull(washExecutionId, "washExecutionId");
    Objects.requireNonNull(appointmentId, "appointmentId");
    Objects.requireNonNull(cleaningTrigger, "cleaningTrigger");
    if (!cleaningTrigger.equals("REASSIGNMENT")
        && !cleaningTrigger.equals("NORMAL_COMPLETION")
        && !cleaningTrigger.equals("CLINIC_CANCELLATION")) {
      throw new IllegalArgumentException("unsupported cleaningTrigger " + cleaningTrigger);
    }
  }

  public boolean requiresTerminalAppointment() {
    return cleaningTrigger.equals("NORMAL_COMPLETION") || cleaningTrigger.equals("CLINIC_CANCELLATION");
  }

  public String expectedAppointmentStatus() {
    return switch (cleaningTrigger) {
      case "NORMAL_COMPLETION" -> "COMPLETED";
      case "CLINIC_CANCELLATION" -> "CANCELLED";
      default -> null;
    };
  }
}
