package com.estoma.washadministration.domain.schedule;

import com.estoma.washadministration.domain.shared.DomainErrorCode;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import com.estoma.washadministration.domain.shared.TimeWindow;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ServiceSchedule {

  private final UUID id;
  private final UUID serviceCalendarId;
  private final int dayOfWeek;
  private final TimeWindow timeWindow;
  private final ServiceScheduleStatus status;
  private final int version;
  private final UUID createdByAccountId;
  private final UUID updatedByAccountId;
  private final Instant createdAt;
  private final Instant updatedAt;

  private ServiceSchedule(
      UUID id,
      UUID serviceCalendarId,
      int dayOfWeek,
      TimeWindow timeWindow,
      ServiceScheduleStatus status,
      int version,
      UUID createdByAccountId,
      UUID updatedByAccountId,
      Instant createdAt,
      Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.serviceCalendarId = Objects.requireNonNull(serviceCalendarId, "serviceCalendarId");
    if (dayOfWeek < 1 || dayOfWeek > 7) {
      throw new DomainRuleViolationException(
          DomainErrorCode.SERVICE_SCHEDULE_INVALID_DAY, "dayOfWeek must be between 1 and 7");
    }
    this.dayOfWeek = dayOfWeek;
    this.timeWindow = Objects.requireNonNull(timeWindow, "timeWindow");
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

  public static ServiceSchedule createNew(
      UUID id,
      UUID serviceCalendarId,
      int dayOfWeek,
      TimeWindow timeWindow,
      UUID createdByAccountId,
      Instant now) {
    return new ServiceSchedule(
        id,
        serviceCalendarId,
        dayOfWeek,
        timeWindow,
        ServiceScheduleStatus.ACTIVE,
        1,
        createdByAccountId,
        createdByAccountId,
        now,
        now);
  }

  public static ServiceSchedule reconstitute(
      UUID id,
      UUID serviceCalendarId,
      int dayOfWeek,
      TimeWindow timeWindow,
      ServiceScheduleStatus status,
      int version,
      UUID createdByAccountId,
      UUID updatedByAccountId,
      Instant createdAt,
      Instant updatedAt) {
    return new ServiceSchedule(
        id,
        serviceCalendarId,
        dayOfWeek,
        timeWindow,
        status,
        version,
        createdByAccountId,
        updatedByAccountId,
        createdAt,
        updatedAt);
  }

  public ServiceSchedule withUpdatedWindow(
      int newDayOfWeek, TimeWindow newWindow, UUID updatedByAccountId, Instant now) {
    return new ServiceSchedule(
        id,
        serviceCalendarId,
        newDayOfWeek,
        newWindow,
        status,
        version + 1,
        createdByAccountId,
        updatedByAccountId,
        createdAt,
        now);
  }

  public ServiceSchedule deactivate(UUID updatedByAccountId, Instant now) {
    if (status != ServiceScheduleStatus.ACTIVE) {
      throw invalidState("only an active schedule can be deactivated");
    }
    return new ServiceSchedule(
        id,
        serviceCalendarId,
        dayOfWeek,
        timeWindow,
        ServiceScheduleStatus.INACTIVE,
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
  public UUID serviceCalendarId() { return serviceCalendarId; }
  public int dayOfWeek() { return dayOfWeek; }
  public TimeWindow timeWindow() { return timeWindow; }
  public ServiceScheduleStatus status() { return status; }
  public int version() { return version; }
  public UUID createdByAccountId() { return createdByAccountId; }
  public UUID updatedByAccountId() { return updatedByAccountId; }
  public Instant createdAt() { return createdAt; }
  public Instant updatedAt() { return updatedAt; }
}
