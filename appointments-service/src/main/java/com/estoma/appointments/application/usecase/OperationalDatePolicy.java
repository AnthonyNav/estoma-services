package com.estoma.appointments.application.usecase;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Wash dates are evaluated in the operating calendar zone, never in the host JVM zone. */
final class OperationalDatePolicy {
  private static final ZoneId WASH_TIMEZONE = ZoneId.of("America/Mexico_City");

  private OperationalDatePolicy() {}

  static boolean hasConcluded(LocalDate serviceDate, Instant now) {
    return serviceDate.isBefore(now.atZone(WASH_TIMEZONE).toLocalDate());
  }
}
