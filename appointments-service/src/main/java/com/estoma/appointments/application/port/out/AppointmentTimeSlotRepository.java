package com.estoma.appointments.application.port.out;

import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentTimeSlotRepository {
  AppointmentTimeSlot save(AppointmentTimeSlot slot);
  Optional<AppointmentTimeSlot> findById(UUID id);
  Optional<AppointmentTimeSlot> findByIdForUpdate(UUID id);
  boolean updateIfVersion(AppointmentTimeSlot slot, long expectedVersion);
  boolean cancelIfVersion(UUID id, long expectedVersion);
}
