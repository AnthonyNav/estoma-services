package com.estoma.washadministration.domain.shared;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Objects;

public record TimeWindow(LocalTime opening, LocalTime closing, int slotDurationMinutes) {

  public TimeWindow {
    Objects.requireNonNull(opening, "opening");
    Objects.requireNonNull(closing, "closing");
    if (!opening.isBefore(closing)) {
      throw new DomainRuleViolationException(
          DomainErrorCode.SERVICE_SCHEDULE_INVALID_INTERVAL, "openingTime must precede closingTime");
    }
    if (opening.getSecond() != 0 || opening.getNano() != 0 || closing.getSecond() != 0 || closing.getNano() != 0) {
      throw new DomainRuleViolationException(
          DomainErrorCode.SERVICE_SCHEDULE_INVALID_INTERVAL, "schedule times must have minute precision");
    }
    if (slotDurationMinutes <= 0) {
      throw new DomainRuleViolationException(
          DomainErrorCode.SERVICE_SCHEDULE_INVALID_SLOT_DURATION, "slot duration must be positive");
    }
    long duration = Duration.between(opening, closing).toMinutes();
    if (duration % slotDurationMinutes != 0) {
      throw new DomainRuleViolationException(
          DomainErrorCode.SERVICE_SCHEDULE_DOES_NOT_PRODUCE_COMPLETE_SLOTS,
          "schedule interval must produce complete slots");
    }
  }
}
