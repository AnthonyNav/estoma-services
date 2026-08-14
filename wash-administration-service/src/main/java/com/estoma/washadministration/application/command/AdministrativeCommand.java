package com.estoma.washadministration.application.command;

import com.estoma.washadministration.domain.closure.AdministrativeClosureScope;
import com.estoma.washadministration.domain.resource.CabinType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

/**
 * Typed, broker-agnostic command models used by Application.
 *
 * <p>PR3 is responsible for mapping wire payloads into these records.
 */
public sealed interface AdministrativeCommand
    permits AdministrativeCommand.CreateServiceCalendar,
        AdministrativeCommand.UpdateServiceCalendar,
        AdministrativeCommand.ActivateServiceCalendar,
        AdministrativeCommand.DeactivateServiceCalendar,
        AdministrativeCommand.ConfigureServiceSchedule,
        AdministrativeCommand.UpdateServiceSchedule,
        AdministrativeCommand.DeactivateServiceSchedule,
        AdministrativeCommand.ScheduleAdministrativeClosure,
        AdministrativeCommand.CancelAdministrativeClosure,
        AdministrativeCommand.RegisterCabin,
        AdministrativeCommand.UpdateCabin,
        AdministrativeCommand.ActivateCabin,
        AdministrativeCommand.DeactivateCabin,
        AdministrativeCommand.RetireCabin,
        AdministrativeCommand.RegisterTank,
        AdministrativeCommand.UpdateTank,
        AdministrativeCommand.ActivateTank,
        AdministrativeCommand.DeactivateTank,
        AdministrativeCommand.RetireTank {

  Set<String> SUPPORTED_OPERATION_TYPES =
      Set.of(
          "CreateServiceCalendar",
          "UpdateServiceCalendar",
          "ActivateServiceCalendar",
          "DeactivateServiceCalendar",
          "ConfigureServiceSchedule",
          "UpdateServiceSchedule",
          "DeactivateServiceSchedule",
          "ScheduleAdministrativeClosure",
          "CancelAdministrativeClosure",
          "RegisterCabin",
          "UpdateCabin",
          "ActivateCabin",
          "DeactivateCabin",
          "RetireCabin",
          "RegisterTank",
          "UpdateTank",
          "ActivateTank",
          "DeactivateTank",
          "RetireTank");

  default String operationType() {
    return getClass().getSimpleName();
  }

  record CreateServiceCalendar(
      UUID calendarId,
      String code,
      String name,
      String timezone,
      LocalDate effectiveFrom,
      LocalDate effectiveUntil)
      implements AdministrativeCommand {}

  record UpdateServiceCalendar(
      UUID calendarId,
      String name,
      String timezone,
      LocalDate effectiveFrom,
      LocalDate effectiveUntil,
      int expectedVersion)
      implements AdministrativeCommand {}

  record ActivateServiceCalendar(UUID calendarId, int expectedVersion)
      implements AdministrativeCommand {}

  record DeactivateServiceCalendar(UUID calendarId, String reason, int expectedVersion)
      implements AdministrativeCommand {}

  record ConfigureServiceSchedule(
      UUID scheduleId,
      UUID calendarId,
      int dayOfWeek,
      LocalTime openingTime,
      LocalTime closingTime,
      int slotDurationMinutes)
      implements AdministrativeCommand {}

  record UpdateServiceSchedule(
      UUID scheduleId,
      int dayOfWeek,
      LocalTime openingTime,
      LocalTime closingTime,
      int slotDurationMinutes,
      int expectedVersion)
      implements AdministrativeCommand {}

  record DeactivateServiceSchedule(UUID scheduleId, String reason, int expectedVersion)
      implements AdministrativeCommand {}

  record ScheduleAdministrativeClosure(
      UUID closureId,
      UUID calendarId,
      AdministrativeClosureScope scope,
      UUID cabinId,
      UUID tankId,
      Instant startsAt,
      Instant endsAt,
      String reason)
      implements AdministrativeCommand {}

  record CancelAdministrativeClosure(UUID closureId, String reason, int expectedVersion)
      implements AdministrativeCommand {}

  record RegisterCabin(UUID cabinId, String code, String name, CabinType cabinType)
      implements AdministrativeCommand {}

  record UpdateCabin(UUID cabinId, String name, CabinType cabinType, int expectedVersion)
      implements AdministrativeCommand {}

  record ActivateCabin(UUID cabinId, int expectedVersion) implements AdministrativeCommand {}

  record DeactivateCabin(UUID cabinId, String reason, int expectedVersion)
      implements AdministrativeCommand {}

  record RetireCabin(UUID cabinId, String reason, int expectedVersion)
      implements AdministrativeCommand {}

  record RegisterTank(
      UUID tankId,
      UUID cabinId,
      String code,
      String name,
      int configuredCapacity)
      implements AdministrativeCommand {}

  record UpdateTank(
      UUID tankId,
      String name,
      int configuredCapacity,
      int expectedVersion)
      implements AdministrativeCommand {}

  record ActivateTank(UUID tankId, int expectedVersion) implements AdministrativeCommand {}

  record DeactivateTank(UUID tankId, String reason, int expectedVersion)
      implements AdministrativeCommand {}

  record RetireTank(UUID tankId, String reason, int expectedVersion)
      implements AdministrativeCommand {}
}
