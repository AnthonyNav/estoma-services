package com.estoma.appointments.domain.model;

import com.estoma.appointments.domain.error.DomainViolationException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Authorization for one additional appointment on a service date. */
public final class ExceptionalAppointmentAuthorization {
  private final UUID exceptionalAuthorizationId;
  private final UUID studentAccountId;
  private final LocalDate serviceDate;
  private final Instant grantedAt;
  private final Instant expiresAt;
  private final boolean cancelled;
  private final long version;

  private ExceptionalAppointmentAuthorization(UUID id, UUID studentId, LocalDate date, Instant grantedAt,
      Instant expiresAt, boolean cancelled, long version) {
    exceptionalAuthorizationId = Objects.requireNonNull(id, "exceptionalAuthorizationId");
    studentAccountId = Objects.requireNonNull(studentId, "studentAccountId");
    serviceDate = Objects.requireNonNull(date, "serviceDate");
    this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
    this.expiresAt = expiresAt;
    if (expiresAt != null && expiresAt.isBefore(grantedAt)) {
      throw new DomainViolationException("authorization expires before it is granted");
    }
    this.cancelled = cancelled;
    if (version <= 0) throw new DomainViolationException("authorization version must be positive");
    this.version = version;
  }

  public static ExceptionalAppointmentAuthorization grant(UUID id, UUID studentId, LocalDate serviceDate,
      Instant grantedAt, Instant expiresAt) {
    return new ExceptionalAppointmentAuthorization(id, studentId, serviceDate, grantedAt, expiresAt, false, 1);
  }

  public static ExceptionalAppointmentAuthorization create(UUID id, UUID studentId, LocalDate serviceDate,
      Instant grantedAt, Instant expiresAt) { return grant(id, studentId, serviceDate, grantedAt, expiresAt); }

  public static ExceptionalAppointmentAuthorization reconstitute(UUID id, UUID studentId, LocalDate serviceDate,
      Instant grantedAt, Instant expiresAt, boolean cancelled, long version) {
    return new ExceptionalAppointmentAuthorization(id, studentId, serviceDate, grantedAt, expiresAt, cancelled, version);
  }

  public ExceptionalAppointmentAuthorization cancel() {
    if (cancelled) return this;
    return new ExceptionalAppointmentAuthorization(exceptionalAuthorizationId, studentAccountId, serviceDate,
        grantedAt, expiresAt, true, version + 1);
  }

  public boolean isAvailableOn(LocalDate date, Instant now) {
    return !cancelled && serviceDate.equals(date) && (expiresAt == null || !now.isAfter(expiresAt));
  }
  public LocalDate authorizationDate() { return serviceDate; }
  public boolean isUsableAt(LocalDate date, Instant now) { return isAvailableOn(date, now); }
  public boolean isCancelled() { return cancelled; }
  public UUID exceptionalAuthorizationId() { return exceptionalAuthorizationId; }
  public UUID studentAccountId() { return studentAccountId; }
  public LocalDate serviceDate() { return serviceDate; }
  public Instant grantedAt() { return grantedAt; }
  public Instant expiresAt() { return expiresAt; }
  public long version() { return version; }
}
