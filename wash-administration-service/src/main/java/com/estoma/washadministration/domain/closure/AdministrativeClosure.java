package com.estoma.washadministration.domain.closure;

import com.estoma.washadministration.domain.shared.ClosureReason;
import com.estoma.washadministration.domain.shared.DomainErrorCode;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import com.estoma.washadministration.domain.shared.EffectivePeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public final class AdministrativeClosure {

  private final UUID id;
  private final UUID serviceCalendarId;
  private final AdministrativeClosureScope scope;
  private final UUID cabinId;
  private final UUID tankId;
  private final Instant startsAt;
  private final Instant endsAt;
  private final ClosureReason reason;
  private final AdministrativeClosureStatus status;
  private final int version;
  private final UUID createdByAccountId;
  private final UUID cancelledByAccountId;
  private final ClosureReason cancellationReason;
  private final Instant cancelledAt;
  private final Instant createdAt;
  private final Instant updatedAt;

  private AdministrativeClosure(
      UUID id,
      UUID serviceCalendarId,
      AdministrativeClosureScope scope,
      UUID cabinId,
      UUID tankId,
      Instant startsAt,
      Instant endsAt,
      ClosureReason reason,
      AdministrativeClosureStatus status,
      int version,
      UUID createdByAccountId,
      UUID cancelledByAccountId,
      ClosureReason cancellationReason,
      Instant cancelledAt,
      Instant createdAt,
      Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.serviceCalendarId = Objects.requireNonNull(serviceCalendarId, "serviceCalendarId");
    this.scope = Objects.requireNonNull(scope, "scope");
    validateScope(scope, cabinId, tankId);
    this.cabinId = cabinId;
    this.tankId = tankId;
    this.startsAt = Objects.requireNonNull(startsAt, "startsAt");
    this.endsAt = Objects.requireNonNull(endsAt, "endsAt");
    if (!endsAt.isAfter(startsAt)) {
      throw new DomainRuleViolationException(
          DomainErrorCode.ADMINISTRATIVE_CLOSURE_INVALID_PERIOD, "endsAt must be after startsAt");
    }
    this.reason = Objects.requireNonNull(reason, "reason");
    this.status = Objects.requireNonNull(status, "status");
    if (version <= 0) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "version must be positive");
    }
    this.version = version;
    this.createdByAccountId = Objects.requireNonNull(createdByAccountId, "createdByAccountId");
    this.cancelledByAccountId = cancelledByAccountId;
    this.cancellationReason = cancellationReason;
    this.cancelledAt = cancelledAt;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "updatedAt must not precede createdAt");
    }
    if (status == AdministrativeClosureStatus.SCHEDULED
        && (cancelledByAccountId != null || cancellationReason != null || cancelledAt != null)) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "scheduled closure cannot have cancellation data");
    }
    if (status == AdministrativeClosureStatus.CANCELLED
        && (cancelledByAccountId == null || cancellationReason == null || cancelledAt == null)) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "cancelled closure requires cancellation data");
    }
  }

  public static AdministrativeClosure schedule(
      UUID id,
      UUID serviceCalendarId,
      AdministrativeClosureScope scope,
      UUID cabinId,
      UUID tankId,
      Instant startsAt,
      Instant endsAt,
      ClosureReason reason,
      EffectivePeriod calendarPeriod,
      ZoneId calendarZone,
      UUID createdByAccountId,
      Instant now) {
    validateWithinCalendar(startsAt, endsAt, calendarPeriod, calendarZone);
    return new AdministrativeClosure(
        id,
        serviceCalendarId,
        scope,
        cabinId,
        tankId,
        startsAt,
        endsAt,
        reason,
        AdministrativeClosureStatus.SCHEDULED,
        1,
        createdByAccountId,
        null,
        null,
        null,
        now,
        now);
  }

  public static AdministrativeClosure reconstitute(
      UUID id,
      UUID serviceCalendarId,
      AdministrativeClosureScope scope,
      UUID cabinId,
      UUID tankId,
      Instant startsAt,
      Instant endsAt,
      ClosureReason reason,
      AdministrativeClosureStatus status,
      int version,
      UUID createdByAccountId,
      UUID cancelledByAccountId,
      ClosureReason cancellationReason,
      Instant cancelledAt,
      Instant createdAt,
      Instant updatedAt) {
    return new AdministrativeClosure(
        id,
        serviceCalendarId,
        scope,
        cabinId,
        tankId,
        startsAt,
        endsAt,
        reason,
        status,
        version,
        createdByAccountId,
        cancelledByAccountId,
        cancellationReason,
        cancelledAt,
        createdAt,
        updatedAt);
  }

  public AdministrativeClosure cancel(UUID cancelledBy, Instant now, ClosureReason cancellationReason) {
    Objects.requireNonNull(cancelledBy, "cancelledBy");
    Objects.requireNonNull(cancellationReason, "cancellationReason");
    if (status != AdministrativeClosureStatus.SCHEDULED) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "only a scheduled closure can be cancelled");
    }
    return new AdministrativeClosure(
        id,
        serviceCalendarId,
        scope,
        cabinId,
        tankId,
        startsAt,
        endsAt,
        reason,
        AdministrativeClosureStatus.CANCELLED,
        version + 1,
        createdByAccountId,
        cancelledBy,
        cancellationReason,
        now,
        createdAt,
        now);
  }

  private static void validateScope(AdministrativeClosureScope scope, UUID cabinId, UUID tankId) {
    boolean valid = switch (scope) {
      case SERVICE -> cabinId == null && tankId == null;
      case CABIN -> cabinId != null && tankId == null;
      case TANK -> cabinId == null && tankId != null;
    };
    if (!valid) {
      throw new DomainRuleViolationException(
          DomainErrorCode.ADMINISTRATIVE_CLOSURE_INVALID_SCOPE,
          "closure references do not match its scope");
    }
  }

  private static void validateWithinCalendar(
      Instant startsAt, Instant endsAt, EffectivePeriod period, ZoneId zone) {
    Objects.requireNonNull(period, "calendarPeriod");
    Objects.requireNonNull(zone, "calendarZone");
    LocalDate start = startsAt.atZone(zone).toLocalDate();
    LocalDate end = endsAt.minusNanos(1).atZone(zone).toLocalDate();
    if (start.isBefore(period.from()) || (period.until() != null && end.isAfter(period.until()))) {
      throw new DomainRuleViolationException(
          DomainErrorCode.ADMINISTRATIVE_CLOSURE_INVALID_PERIOD,
          "closure must be inside calendar validity");
    }
  }

  public UUID id() { return id; }
  public UUID serviceCalendarId() { return serviceCalendarId; }
  public AdministrativeClosureScope scope() { return scope; }
  public UUID cabinId() { return cabinId; }
  public UUID tankId() { return tankId; }
  public Instant startsAt() { return startsAt; }
  public Instant endsAt() { return endsAt; }
  public ClosureReason reason() { return reason; }
  public AdministrativeClosureStatus status() { return status; }
  public int version() { return version; }
  public UUID createdByAccountId() { return createdByAccountId; }
  public UUID cancelledByAccountId() { return cancelledByAccountId; }
  public ClosureReason cancellationReason() { return cancellationReason; }
  public Instant cancelledAt() { return cancelledAt; }
  public Instant createdAt() { return createdAt; }
  public Instant updatedAt() { return updatedAt; }
}
