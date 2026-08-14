package com.estoma.washadministration.application.port;

import com.estoma.washadministration.domain.calendar.ServiceCalendar;
import com.estoma.washadministration.domain.shared.EffectivePeriod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceCalendarRepository {

  Optional<ServiceCalendar> findById(UUID id);

  Optional<ServiceCalendar> findByIdForUpdate(UUID id);

  Optional<ServiceCalendar> findByCode(String code);

  List<ServiceCalendar> findAll();

  List<ServiceCalendar> findActiveOverlapping(EffectivePeriod period, UUID excludedId);

  void insert(ServiceCalendar calendar);

  int update(ServiceCalendar calendar, int expectedVersion);
}
