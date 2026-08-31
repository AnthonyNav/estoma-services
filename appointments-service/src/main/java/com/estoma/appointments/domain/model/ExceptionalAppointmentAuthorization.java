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
  private final UUID supersededByAuthorizationId;
  private final Instant supersededAt;
  private final long version;

  private ExceptionalAppointmentAuthorization(UUID id, UUID studentId, LocalDate date,
      UUID authorizedByAccountId, String reason, Instant grantedAt, Instant expiresAt,
      UUID cancelledByAccountId, String cancellationReason, Instant cancelledAt,
      UUID supersededByAuthorizationId, Instant supersededAt, long version) {
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
    boolean supersessionComplete = supersededByAuthorizationId != null && supersededAt != null;
    boolean supersessionEmpty = supersededByAuthorizationId == null && supersededAt == null;
    if (!supersessionComplete && !supersessionEmpty) {
      throw new DomainViolationException("authorization supersession metadata must be complete");
    }
    if (id.equals(supersededByAuthorizationId)) {
      throw new DomainViolationException("authorization cannot supersede itself");
    }
    this.supersededByAuthorizationId = supersededByAuthorizationId;
    this.supersededAt = supersededAt;
    if (version <= 0) throw new DomainViolationException("authorization version must be positive");
    this.version = version;
  }

  public static ExceptionalAppointmentAuthorization grant(UUID id, UUID studentId, LocalDate serviceDate,
      UUID authorizedByAccountId, String reason, Instant grantedAt, Instant expiresAt) {
    return new ExceptionalAppointmentAuthorization(id, studentId, serviceDate, authorizedByAccountId,
        reason, grantedAt, expiresAt, null, null, null, null, null, 1);
  }

  public static ExceptionalAppointmentAuthorization reconstitute(UUID id, UUID studentId,
      LocalDate serviceDate, UUID authorizedByAccountId, String reason, Instant grantedAt,
      Instant expiresAt, UUID cancelledByAccountId, String cancellationReason, Instant cancelledAt,
      UUID supersededByAuthorizationId, Instant supersededAt, long version) {
    return new ExceptionalAppointmentAuthorization(id, studentId, serviceDate, authorizedByAccountId,
        reason, grantedAt, expiresAt, cancelledByAccountId, cancellationReason, cancelledAt,
        supersededByAuthorizationId, supersededAt, version);
  }

  public ExceptionalAppointmentAuthorization cancel(UUID cancelledByAccountId,
      String cancellationReason, Instant cancelledAt) {
    if (isCancelled()) return this;
    return new ExceptionalAppointmentAuthorization(exceptionalAuthorizationId, studentAccountId,
        serviceDate, authorizedByAccountId, reason, grantedAt, expiresAt,
        Objects.requireNonNull(cancelledByAccountId, "cancelledByAccountId"),
        cancellationReason, Objects.requireNonNull(cancelledAt, "cancelledAt"),
        supersededByAuthorizationId, supersededAt, version + 1);
  }

  /**
   * A later supervisor decision permanently prevents this historical authorization from becoming
   * actionable again if its former appointment is later cancelled by the clinic.
   */
  public ExceptionalAppointmentAuthorization supersede(UUID successorAuthorizationId, Instant at) {
    if (isSuperseded()) return this;
    return new ExceptionalAppointmentAuthorization(exceptionalAuthorizationId, studentAccountId,
        serviceDate, authorizedByAccountId, reason, grantedAt, expiresAt, cancelledByAccountId,
        cancellationReason, cancelledAt,
        Objects.requireNonNull(successorAuthorizationId, "successorAuthorizationId"),
        Objects.requireNonNull(at, "supersededAt"), version + 1);
  }

  public boolean isAvailableOn(LocalDate date, Instant now) {
    // New owner writes persist null. Existing values retain their former effect only so that the
    // migration does not reactivate an authorization that had already expired before V14.
    return !isCancelled() && !isSuperseded() && serviceDate.equals(date)
        && (expiresAt == null || !now.isAfter(expiresAt));
  }

  public boolean isUsableAt(LocalDate date, Instant now) { return isAvailableOn(date, now); }
  public boolean isCancelled() { return cancelledAt != null; }
  public boolean isSuperseded() { return supersededAt != null; }
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
  public UUID supersededByAuthorizationId() { return supersededByAuthorizationId; }
  public Instant supersededAt() { return supersededAt; }
  public long version() { return version; }
}
