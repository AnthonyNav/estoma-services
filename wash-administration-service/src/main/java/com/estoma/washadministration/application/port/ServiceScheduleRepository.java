package com.estoma.washadministration.application.port;

import com.estoma.washadministration.domain.schedule.ServiceSchedule;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceScheduleRepository {

  Optional<ServiceSchedule> findById(UUID id);

  Optional<ServiceSchedule> findByIdForUpdate(UUID id);

  List<ServiceSchedule> findAll();

  List<ServiceSchedule> findActiveOverlapping(
      UUID calendarId, int dayOfWeek, LocalTime openingTime, LocalTime closingTime, UUID excludedId);

  long countActiveByCalendarId(UUID calendarId);

  void insert(ServiceSchedule schedule);

  int update(ServiceSchedule schedule, int expectedVersion);
}
