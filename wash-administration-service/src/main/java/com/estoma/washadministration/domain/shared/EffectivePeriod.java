package com.estoma.washadministration.domain.shared;

import java.time.LocalDate;
import java.util.Objects;

public record EffectivePeriod(LocalDate from, LocalDate until) {

  public EffectivePeriod {
    Objects.requireNonNull(from, "from");
    if (until != null && until.isBefore(from)) {
      throw new DomainRuleViolationException(
          DomainErrorCode.SERVICE_CALENDAR_INVALID_PERIOD,
          "effectiveUntil must not precede effectiveFrom");
    }
  }

  public boolean overlaps(EffectivePeriod other) {
    Objects.requireNonNull(other, "other");
    return !from.isAfter(other.endInclusive()) && !other.from.isAfter(endInclusive());
  }

  private LocalDate endInclusive() {
    return until == null ? LocalDate.MAX : until;
  }
}
