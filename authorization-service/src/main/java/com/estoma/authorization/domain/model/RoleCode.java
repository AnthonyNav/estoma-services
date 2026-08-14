package com.estoma.authorization.domain.model;

import com.estoma.authorization.domain.exception.DomainErrorCode;
import com.estoma.authorization.domain.exception.DomainRuleViolationException;
import java.util.Locale;

public enum RoleCode {
  ALUMNO,
  SUPERVISOR_LAVADO,
  ADMINISTRADOR_LAVADO,
  ADMINISTRADOR_PRACTICAS,
  SUPERADMIN;

  public static RoleCode parse(String value) {
    if (value == null) {
      throw invalid(value);
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw invalid(value);
    }
  }

  public boolean isCompatibleWith(LoginType loginType) {
    return loginType != null && (loginType == LoginType.MATRICULA ? this == ALUMNO : this != ALUMNO);
  }

  private static DomainRuleViolationException invalid(String value) {
    return new DomainRuleViolationException(DomainErrorCode.INVALID_CODE, "unknown role code: " + value);
  }
}
