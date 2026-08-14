package com.estoma.authorization.domain.model;

import com.estoma.authorization.domain.exception.DomainErrorCode;
import com.estoma.authorization.domain.exception.DomainRuleViolationException;
import java.util.Locale;

public enum SystemCode {
  LAVADO_ULTRASONICO,
  PRACTICAS_PROFESIONALES;

  public static SystemCode parse(String value) {
    if (value == null) {
      throw invalid(value);
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw invalid(value);
    }
  }

  private static DomainRuleViolationException invalid(String value) {
    return new DomainRuleViolationException(DomainErrorCode.INVALID_CODE, "unknown system code: " + value);
  }
}
