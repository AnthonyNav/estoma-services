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
  private final UUID authorizedByAccountId;
  private final String reason;
  private final Instant grantedAt;
  private final Instant expiresAt;
  private final UUID cancelledByAccountId;
  private final String cancellationReason;
  private final Instant cancelledAt;
  private final long version;

  private ExceptionalAppointmentAuthorization(UUID id, UUID studentId, LocalDate date,
      UUID authorizedByAccountId, String reason, Instant grantedAt, Instant expiresAt,
      UUID cancelledByAccountId, String cancellationReason, Instant cancelledAt, long version) {
    exceptionalAuthorizationId = Objects.requireNonNull(id, "exceptionalAuthorizationId");
    studentAccountId = Objects.requireNonNull(studentId, "studentAccountId");
    serviceDate = Objects.requireNonNull(date, "serviceDate");
    this.authorizedByAccountId = Objects.requireNonNull(authorizedByAccountId, "authorizedByAccountId");
    if (reason == null || reason.isBlank()) throw new DomainViolationException("authorization reason is required");
    this.reason = reason;
    this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
    this.expiresAt = expiresAt;
    if (expiresAt != null && expiresAt.isBefore(grantedAt)) {
      throw new DomainViolationException("authorization expires before it is granted");
    }
    boolean cancellationComplete = cancelledByAccountId != null && cancellationReason != null
        && !cancellationReason.isBlank() && cancelledAt != null;
    boolean cancellationEmpty = cancelledByAccountId == null && cancellationReason == null && cancelledAt == null;
    if (!cancellationComplete && !cancellationEmpty) {
      throw new DomainViolationException("authorization cancellation metadata must be complete");
    }
    this.cancelledByAccountId = cancelledByAccountId;
    this.cancellationReason = cancellationReason;
    this.cancelledAt = cancelledAt;
    if (version <= 0) throw new DomainViolationException("authorization version must be positive");
    this.version = version;
  }

  public static ExceptionalAppointmentAuthorization grant(UUID id, UUID studentId, LocalDate serviceDate,
      UUID authorizedByAccountId, String reason, Instant grantedAt, Instant expiresAt) {
    return new ExceptionalAppointmentAuthorization(id, studentId, serviceDate, authorizedByAccountId,
        reason, grantedAt, expiresAt, null, null, null, 1);
  }

  public static ExceptionalAppointmentAuthorization reconstitute(UUID id, UUID studentId,
      LocalDate serviceDate, UUID authorizedByAccountId, String reason, Instant grantedAt,
      Instant expiresAt, UUID cancelledByAccountId, String cancellationReason, Instant cancelledAt,
      long version) {
    return new ExceptionalAppointmentAuthorization(id, studentId, serviceDate, authorizedByAccountId,
        reason, grantedAt, expiresAt, cancelledByAccountId, cancellationReason, cancelledAt, version);
  }

  public ExceptionalAppointmentAuthorization cancel(UUID cancelledByAccountId,
      String cancellationReason, Instant cancelledAt) {
    if (isCancelled()) return this;
    return new ExceptionalAppointmentAuthorization(exceptionalAuthorizationId, studentAccountId,
        serviceDate, authorizedByAccountId, reason, grantedAt, expiresAt,
        Objects.requireNonNull(cancelledByAccountId, "cancelledByAccountId"),
        cancellationReason, Objects.requireNonNull(cancelledAt, "cancelledAt"), version + 1);
  }

  public boolean isAvailableOn(LocalDate date, Instant now) {
    return !isCancelled() && serviceDate.equals(date);
  }

  public boolean isUsableAt(LocalDate date, Instant now) { return isAvailableOn(date, now); }
  public boolean isCancelled() { return cancelledAt != null; }
  public LocalDate authorizationDate() { return serviceDate; }
  public UUID exceptionalAuthorizationId() { return exceptionalAuthorizationId; }
  public UUID studentAccountId() { return studentAccountId; }
  public LocalDate serviceDate() { return serviceDate; }
  public UUID authorizedByAccountId() { return authorizedByAccountId; }
  public String reason() { return reason; }
  public Instant grantedAt() { return grantedAt; }
  public Instant expiresAt() { return expiresAt; }
  public UUID cancelledByAccountId() { return cancelledByAccountId; }
  public String cancellationReason() { return cancellationReason; }
  public Instant cancelledAt() { return cancelledAt; }
  public long version() { return version; }
}
