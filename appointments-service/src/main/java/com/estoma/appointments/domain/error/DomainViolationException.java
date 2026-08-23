package com.estoma.appointments.domain.error;

/** A deterministic functional rejection raised by a domain invariant. */
public class DomainViolationException extends IllegalArgumentException {
  public DomainViolationException(String message) { super(message); }
  public DomainViolationException(String message, Throwable cause) { super(message, cause); }
}
