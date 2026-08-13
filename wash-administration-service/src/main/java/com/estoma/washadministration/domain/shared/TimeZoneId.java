package com.estoma.washadministration.domain.shared;

import java.time.DateTimeException;
import java.time.ZoneId;

public record TimeZoneId(String value) {

  public TimeZoneId {
    if (value == null || value.isBlank()) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_PERIOD, "timezone must not be blank");
    }
    value = value.trim();
    try {
      ZoneId.of(value);
    } catch (DateTimeException exception) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_PERIOD, "timezone must be a valid IANA zone");
    }
  }
}
