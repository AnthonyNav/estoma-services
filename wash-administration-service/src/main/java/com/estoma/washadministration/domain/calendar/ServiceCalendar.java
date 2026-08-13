package com.estoma.washadministration.domain.calendar;

import com.estoma.washadministration.domain.shared.DomainErrorCode;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import com.estoma.washadministration.domain.shared.EffectivePeriod;
import com.estoma.washadministration.domain.shared.ResourceCode;
import com.estoma.washadministration.domain.shared.DisplayName;
import com.estoma.washadministration.domain.shared.TimeZoneId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ServiceCalendar {

  private final UUID id;
  private final ResourceCode code;
  private final DisplayName name;
  private final TimeZoneId timezone;
  private final EffectivePeriod effectivePeriod;
  private final ServiceCalendarStatus status;
  private final int version;
  private final UUID createdByAccountId;
  private final UUID updatedByAccountId;
  private final Instant createdAt;
  private final Instant updatedAt;

  private ServiceCalendar(
      UUID id,
      ResourceCode code,
      DisplayName name,
      TimeZoneId timezone,
      EffectivePeriod effectivePeriod,
      ServiceCalendarStatus status,
      int version,
      UUID createdByAccountId,
      UUID updatedByAccountId,
      Instant createdAt,
      Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.code = Objects.requireNonNull(code, "code");
    this.name = Objects.requireNonNull(name, "name");
    this.timezone = Objects.requireNonNull(timezone, "timezone");
    this.effectivePeriod = Objects.requireNonNull(effectivePeriod, "effectivePeriod");
    this.status = Objects.requireNonNull(status, "status");
    if (version <= 0) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "version must be positive");
    }
    this.version = version;
    this.createdByAccountId = Objects.requireNonNull(createdByAccountId, "createdByAccountId");
    this.updatedByAccountId = Objects.requireNonNull(updatedByAccountId, "updatedByAccountId");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "updatedAt must not precede createdAt");
    }
  }

  public static ServiceCalendar createNew(
      UUID id,
      ResourceCode code,
      DisplayName name,
      TimeZoneId timezone,
      EffectivePeriod period,
      UUID createdByAccountId,
      Instant now) {
    return new ServiceCalendar(
        id,
        code,
        name,
        timezone,
        period,
        ServiceCalendarStatus.DRAFT,
        1,
        createdByAccountId,
        createdByAccountId,
        now,
        now);
  }

  public static ServiceCalendar reconstitute(
      UUID id,
      ResourceCode code,
      DisplayName name,
      TimeZoneId timezone,
      EffectivePeriod period,
      ServiceCalendarStatus status,
      int version,
      UUID createdByAccountId,
      UUID updatedByAccountId,
      Instant createdAt,
      Instant updatedAt) {
    return new ServiceCalendar(
        id,
        code,
        name,
        timezone,
        period,
        status,
        version,
        createdByAccountId,
        updatedByAccountId,
        createdAt,
        updatedAt);
  }

  public ServiceCalendar withUpdatedDetails(
      DisplayName newName,
      TimeZoneId newTimezone,
      EffectivePeriod newPeriod,
      UUID updatedByAccountId,
      Instant now) {
    return copy(newName, newTimezone, newPeriod, status, updatedByAccountId, now);
  }

  public ServiceCalendar activate(UUID updatedByAccountId, Instant now) {
    if (status == ServiceCalendarStatus.ACTIVE) {
      throw invalidState("calendar is already active");
    }
    return copy(name, timezone, effectivePeriod, ServiceCalendarStatus.ACTIVE, updatedByAccountId, now);
  }

  public ServiceCalendar deactivate(UUID updatedByAccountId, Instant now) {
    if (status != ServiceCalendarStatus.ACTIVE) {
      throw invalidState("only an active calendar can be deactivated");
    }
    return copy(name, timezone, effectivePeriod, ServiceCalendarStatus.INACTIVE, updatedByAccountId, now);
  }

  private ServiceCalendar copy(
      DisplayName newName,
      TimeZoneId newTimezone,
      EffectivePeriod period,
      ServiceCalendarStatus newStatus,
      UUID updatedByAccountId,
      Instant now) {
    return new ServiceCalendar(
        id,
        code,
        newName,
        newTimezone,
        period,
        newStatus,
        version + 1,
        createdByAccountId,
        updatedByAccountId,
        createdAt,
        now);
  }

  private static DomainRuleViolationException invalidState(String message) {
    return new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, message);
  }

  public UUID id() { return id; }
  public ResourceCode code() { return code; }
  public DisplayName name() { return name; }
  public TimeZoneId timezone() { return timezone; }
  public EffectivePeriod effectivePeriod() { return effectivePeriod; }
  public ServiceCalendarStatus status() { return status; }
  public int version() { return version; }
  public UUID createdByAccountId() { return createdByAccountId; }
  public UUID updatedByAccountId() { return updatedByAccountId; }
  public Instant createdAt() { return createdAt; }
  public Instant updatedAt() { return updatedAt; }
}
