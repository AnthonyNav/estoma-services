package com.estoma.appointments.domain.model;

import com.estoma.appointments.domain.error.DomainViolationException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** Materialized reservable block owned by Appointments. */
public final class AppointmentTimeSlot {
  private final UUID appointmentTimeSlotId;
  private final UUID sourceScheduleId;
  private final LocalDate serviceDate;
  private final Instant startsAt;
  private final Instant endsAt;
  private final ZoneId timezone;
  private final Instant bookingDeadlineAt;
  private final Instant cancellationDeadlineAt;
  private final Instant arrivalToleranceUntil;
  private final AppointmentTimeSlotStatus status;
  private final long version;

  private AppointmentTimeSlot(UUID id, UUID sourceScheduleId, LocalDate serviceDate, Instant startsAt,
      Instant endsAt, ZoneId timezone, Instant bookingDeadlineAt, Instant cancellationDeadlineAt,
      Instant arrivalToleranceUntil, AppointmentTimeSlotStatus status, long version) {
    this.appointmentTimeSlotId = Objects.requireNonNull(id, "appointmentTimeSlotId");
    this.sourceScheduleId = Objects.requireNonNull(sourceScheduleId, "sourceScheduleId");
    this.serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
    this.startsAt = Objects.requireNonNull(startsAt, "startsAt");
    this.endsAt = Objects.requireNonNull(endsAt, "endsAt");
    if (!endsAt.isAfter(startsAt)) throw new DomainViolationException("slot endsAt must be after startsAt");
    this.timezone = Objects.requireNonNull(timezone, "timezone");
    this.bookingDeadlineAt = Objects.requireNonNull(bookingDeadlineAt, "bookingDeadlineAt");
    this.cancellationDeadlineAt = Objects.requireNonNull(cancellationDeadlineAt, "cancellationDeadlineAt");
    this.arrivalToleranceUntil = Objects.requireNonNull(arrivalToleranceUntil, "arrivalToleranceUntil");
    if (bookingDeadlineAt.isAfter(startsAt)) throw new DomainViolationException("booking deadline cannot be after slot start");
    if (cancellationDeadlineAt.isAfter(bookingDeadlineAt)) throw new DomainViolationException("cancellation deadline cannot be after booking deadline");
    if (arrivalToleranceUntil.isBefore(startsAt)) throw new DomainViolationException("arrival tolerance cannot end before slot start");
    if (!serviceDate.equals(startsAt.atZone(timezone).toLocalDate())) throw new DomainViolationException("service date does not match slot timezone");
    if (version <= 0) throw new DomainViolationException("slot version must be positive");
    this.status = Objects.requireNonNull(status, "status");
    this.version = version;
  }

  public static AppointmentTimeSlot create(UUID id, UUID sourceScheduleId, LocalDate serviceDate,
      Instant startsAt, Instant endsAt, ZoneId timezone, Instant bookingDeadlineAt,
      Instant cancellationDeadlineAt, Instant arrivalToleranceUntil) {
    return new AppointmentTimeSlot(id, sourceScheduleId, serviceDate, startsAt, endsAt, timezone,
        bookingDeadlineAt, cancellationDeadlineAt, arrivalToleranceUntil, AppointmentTimeSlotStatus.ACTIVE, 1);
  }

  /** Materializes the V1 default deadlines (booking -10m, cancellation -20m, arrival +10m). */
  public static AppointmentTimeSlot materialize(UUID id, UUID sourceScheduleId, LocalDate serviceDate,
      Instant startsAt, Instant endsAt, ZoneId timezone) {
    return create(id, sourceScheduleId, serviceDate, startsAt, endsAt, timezone,
        startsAt.minusSeconds(10 * 60), startsAt.minusSeconds(20 * 60), startsAt.plusSeconds(10 * 60));
  }

  public static AppointmentTimeSlot reconstitute(UUID id, UUID sourceScheduleId, LocalDate serviceDate,
      Instant startsAt, Instant endsAt, ZoneId timezone, Instant bookingDeadlineAt,
      Instant cancellationDeadlineAt, Instant arrivalToleranceUntil, AppointmentTimeSlotStatus status,
      long version) {
    return new AppointmentTimeSlot(id, sourceScheduleId, serviceDate, startsAt, endsAt, timezone,
        bookingDeadlineAt, cancellationDeadlineAt, arrivalToleranceUntil, status, version);
  }

  public AppointmentTimeSlot cancel() {
    if (status == AppointmentTimeSlotStatus.CANCELLED) return this;
    return new AppointmentTimeSlot(appointmentTimeSlotId, sourceScheduleId, serviceDate, startsAt, endsAt,
        timezone, bookingDeadlineAt, cancellationDeadlineAt, arrivalToleranceUntil,
        AppointmentTimeSlotStatus.CANCELLED, version + 1);
  }

  public TimeInterval interval() { return new TimeInterval(startsAt, endsAt); }
  public boolean isBookableAt(Instant now) {
    return status == AppointmentTimeSlotStatus.ACTIVE && now != null && !now.isAfter(bookingDeadlineAt);
  }
  public boolean isActive() { return status == AppointmentTimeSlotStatus.ACTIVE; }
  public boolean isCancelled() { return status == AppointmentTimeSlotStatus.CANCELLED; }
  public UUID appointmentTimeSlotId() { return appointmentTimeSlotId; }
  public UUID sourceScheduleId() { return sourceScheduleId; }
  public LocalDate serviceDate() { return serviceDate; }
  public Instant startsAt() { return startsAt; }
  public Instant endsAt() { return endsAt; }
  public ZoneId timezone() { return timezone; }
  public Instant bookingDeadlineAt() { return bookingDeadlineAt; }
  public Instant cancellationDeadlineAt() { return cancellationDeadlineAt; }
  public Instant arrivalToleranceUntil() { return arrivalToleranceUntil; }
  public AppointmentTimeSlotStatus status() { return status; }
  public long version() { return version; }
}
