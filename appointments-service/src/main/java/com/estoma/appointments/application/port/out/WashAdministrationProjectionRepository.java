package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WashAdministrationProjectionRepository {
  void upsertServiceCalendar(ServiceCalendarProjection projection);
  void upsertServiceSchedule(ServiceScheduleProjection projection);
  void upsertAdministrativeClosure(AdministrativeClosureProjection projection);
  void upsertCabin(CabinProjection projection);
  void upsertTank(TankProjection projection);
  void upsertResourcePriority(ResourcePriorityProjection projection);

  Optional<ServiceCalendarProjection> findCalendar(UUID calendarId);
  List<ServiceCalendarProjection> findCalendars();
  Optional<ServiceScheduleProjection> findSchedule(UUID scheduleId);
  Optional<AdministrativeClosureProjection> findClosure(UUID closureId);
  List<AdministrativeClosureProjection> findClosuresOverlapping(UUID calendarId, Instant startsAt, Instant endsAt);
  List<CabinProjection> findCabins();
  List<TankProjection> findTanks();
  List<ResourcePriorityProjection> findActivePriorities();
  boolean reliable();

  default Optional<CabinProjection> findCabin(UUID cabinId) {
    return findCabins().stream().filter(value -> value.cabinId().equals(cabinId)).findFirst();
  }
  default Optional<TankProjection> findTank(UUID tankId) {
    return findTanks().stream().filter(value -> value.tankId().equals(tankId)).findFirst();
  }

  record ServiceCalendarProjection(UUID calendarId, String code, String timezone,
      LocalDate effectiveFrom, LocalDate effectiveUntil, String status,
      long sourceVersion, Instant updatedAt) {}

  record ServiceScheduleProjection(UUID scheduleId, UUID calendarId, int dayOfWeek,
      LocalTime openingTime, LocalTime closingTime, int slotDurationMinutes, String status,
      long sourceVersion, Instant updatedAt) {}

  record AdministrativeClosureProjection(UUID closureId, UUID calendarId, String scope,
      UUID cabinId, UUID tankId, Instant startsAt, Instant endsAt, String status,
      long sourceVersion, Instant updatedAt) {}

  record CabinProjection(UUID cabinId, String cabinType, String administrativeStatus,
      long sourceVersion, Instant updatedAt) {}

  record TankProjection(UUID tankId, UUID cabinId, int configuredCapacity,
      String administrativeStatus, long sourceVersion, Instant updatedAt) {}

  record ResourcePriorityProjection(UUID resourcePriorityId, String appointmentType,
      String cabinType, String usageMode, int priorityOrder, String status,
      long sourceVersion, Instant updatedAt) {}
}
