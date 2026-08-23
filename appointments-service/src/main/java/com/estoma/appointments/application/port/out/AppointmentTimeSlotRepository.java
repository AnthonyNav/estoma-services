package com.estoma.appointments.application.port.out;

import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentTimeSlotRepository {
  AppointmentTimeSlot save(AppointmentTimeSlot slot);
  Optional<AppointmentTimeSlot> findById(UUID id);
  Optional<AppointmentTimeSlot> findByIdForUpdate(UUID id);
  List<AppointmentTimeSlot> findActiveFrom(Instant from);
  List<AppointmentTimeSlot> findActiveOverlapping(Instant startsAt, Instant endsAt);
  boolean updateIfVersion(AppointmentTimeSlot slot, long expectedVersion);
  boolean cancelIfVersion(UUID id, long expectedVersion);
}
