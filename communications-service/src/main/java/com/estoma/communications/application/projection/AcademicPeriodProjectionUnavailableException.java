package com.estoma.communications.application.projection;

/** The Wash retention policy cannot be applied until Academic's local projection is authoritative. */
public final class AcademicPeriodProjectionUnavailableException extends RuntimeException {

  public AcademicPeriodProjectionUnavailableException(String readiness) {
    super("academic period projection is not usable for Wash retention; readiness=" + readiness);
  }
}
