package com.estoma.washadministration.application.command;

import com.estoma.washadministration.application.authorization.AuthorizationEvaluator;
import com.estoma.washadministration.application.port.AdministrativeClosureRepository;
import com.estoma.washadministration.application.port.CabinRepository;
import com.estoma.washadministration.application.port.ServiceCalendarRepository;
import com.estoma.washadministration.application.port.ServiceScheduleRepository;
import com.estoma.washadministration.application.port.TankRepository;
import com.estoma.washadministration.domain.calendar.ServiceCalendar;
import com.estoma.washadministration.domain.calendar.ServiceCalendarStatus;
import com.estoma.washadministration.domain.closure.AdministrativeClosure;
import com.estoma.washadministration.domain.closure.AdministrativeClosureScope;
import com.estoma.washadministration.domain.resource.Cabin;
import com.estoma.washadministration.domain.resource.ResourceStatus;
import com.estoma.washadministration.domain.resource.Tank;
import com.estoma.washadministration.domain.schedule.ServiceSchedule;
import com.estoma.washadministration.domain.schedule.ServiceScheduleStatus;
import com.estoma.washadministration.domain.shared.ClosureReason;
import com.estoma.washadministration.domain.shared.DisplayName;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import com.estoma.washadministration.domain.shared.EffectivePeriod;
import com.estoma.washadministration.domain.shared.PositiveCapacity;
import com.estoma.washadministration.domain.shared.ResourceCode;
import com.estoma.washadministration.domain.shared.TimeWindow;
import com.estoma.washadministration.domain.shared.TimeZoneId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** Executes the complete V1 business behavior from typed Application commands. */
@Component
public final class WashAdministrationCommandHandler {

  private final ServiceCalendarRepository calendars;
  private final ServiceScheduleRepository schedules;
  private final AdministrativeClosureRepository closures;
  private final CabinRepository cabins;
  private final TankRepository tanks;
  private final AuthorizationEvaluator authorization;

  public WashAdministrationCommandHandler(
      ServiceCalendarRepository calendars,
      ServiceScheduleRepository schedules,
      AdministrativeClosureRepository closures,
      CabinRepository cabins,
      TankRepository tanks,
      AuthorizationEvaluator authorization) {
    this.calendars = calendars;
    this.schedules = schedules;
    this.closures = closures;
    this.cabins = cabins;
    this.tanks = tanks;
    this.authorization = authorization;
  }

  public Change handle(CommandRequest request, Instant now) {
    AdministrativeCommand command = request.payload();
    authorization.require(request.metadata().actor(), command.operationType());
    try {
      return switch (command) {
        case AdministrativeCommand.CreateServiceCalendar value ->
            createCalendar(value, request.metadata().actor(), now);
        case AdministrativeCommand.UpdateServiceCalendar value ->
            updateCalendar(value, request.metadata().actor(), now);
        case AdministrativeCommand.ActivateServiceCalendar value ->
            activateCalendar(value, request.metadata().actor(), now);
        case AdministrativeCommand.DeactivateServiceCalendar value ->
            deactivateCalendar(value, request.metadata().actor(), now);
        case AdministrativeCommand.ConfigureServiceSchedule value ->
            configureSchedule(value, request.metadata().actor(), now);
        case AdministrativeCommand.UpdateServiceSchedule value ->
            updateSchedule(value, request.metadata().actor(), now);
        case AdministrativeCommand.DeactivateServiceSchedule value ->
            deactivateSchedule(value, request.metadata().actor(), now);
        case AdministrativeCommand.ScheduleAdministrativeClosure value ->
            scheduleClosure(value, request.metadata().actor(), now);
        case AdministrativeCommand.CancelAdministrativeClosure value ->
            cancelClosure(value, request.metadata().actor(), now);
        case AdministrativeCommand.RegisterCabin value ->
            registerCabin(value, request.metadata().actor(), now);
        case AdministrativeCommand.UpdateCabin value ->
            updateCabin(value, request.metadata().actor(), now);
        case AdministrativeCommand.ActivateCabin value ->
            activateCabin(value, request.metadata().actor(), now);
        case AdministrativeCommand.DeactivateCabin value ->
            deactivateCabin(value, request.metadata().actor(), now);
        case AdministrativeCommand.RetireCabin value ->
            retireCabin(value, request.metadata().actor(), now);
        case AdministrativeCommand.RegisterTank value ->
            registerTank(value, request.metadata().actor(), now);
        case AdministrativeCommand.UpdateTank value ->
            updateTank(value, request.metadata().actor(), now);
        case AdministrativeCommand.ActivateTank value ->
            activateTank(value, request.metadata().actor(), now);
        case AdministrativeCommand.DeactivateTank value ->
            deactivateTank(value, request.metadata().actor(), now);
        case AdministrativeCommand.RetireTank value ->
            retireTank(value, request.metadata().actor(), now);
      };
    } catch (CommandRejectedException exception) {
      throw exception;
    } catch (DomainRuleViolationException exception) {
      throw rejected(domainCode(exception));
    } catch (DataIntegrityViolationException exception) {
      throw rejected(duplicateCode(command.operationType(), exception));
    } catch (IllegalArgumentException exception) {
      throw rejected("INVALID_MESSAGE", exception);
    }
  }

  private Change createCalendar(
      AdministrativeCommand.CreateServiceCalendar command, ActorContext actor, Instant now) {
    UUID id = required(command.calendarId());
    String code = requiredText(command.code());
    ServiceCalendar calendar =
        ServiceCalendar.createNew(
            id,
            new ResourceCode(code),
            new DisplayName(requiredText(command.name())),
            new TimeZoneId(requiredText(command.timezone())),
            new EffectivePeriod(required(command.effectiveFrom()), command.effectiveUntil()),
            account(actor),
            now);
    if (calendars.findByCode(code).isPresent()) {
      throw rejected("SERVICE_CALENDAR_CODE_ALREADY_EXISTS", id);
    }
    calendars.insert(calendar);
    return change(
        calendar.id(),
        calendar.version(),
        "ServiceCalendarCreated",
        calendarPayload(calendar, now));
  }

  private Change updateCalendar(
      AdministrativeCommand.UpdateServiceCalendar command, ActorContext actor, Instant now) {
    UUID id = required(command.calendarId());
    ServiceCalendar current =
        calendars
            .findByIdForUpdate(id)
            .orElseThrow(() -> rejected("SERVICE_CALENDAR_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    ServiceCalendar updated =
        current.withUpdatedDetails(
            new DisplayName(requiredText(command.name())),
            new TimeZoneId(requiredText(command.timezone())),
            new EffectivePeriod(required(command.effectiveFrom()), command.effectiveUntil()),
            account(actor),
            now);
    ensureCalendarDoesNotOverlap(updated, id);
    ensureClosuresWithinCalendar(updated);
    updateExactlyOne(calendars.update(updated, current.version()), "VERSION_CONFLICT");
    return change(
        id,
        updated.version(),
        "ServiceCalendarUpdated",
        calendarPayload(updated, now));
  }

  private Change activateCalendar(
      AdministrativeCommand.ActivateServiceCalendar command, ActorContext actor, Instant now) {
    UUID id = required(command.calendarId());
    ServiceCalendar current =
        calendars
            .findByIdForUpdate(id)
            .orElseThrow(() -> rejected("SERVICE_CALENDAR_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    if (schedules.countActiveByCalendarId(id) == 0) {
      throw rejected("SERVICE_CALENDAR_HAS_NO_ACTIVE_SCHEDULES", id);
    }
    ServiceCalendar updated = current.activate(account(actor), now);
    ensureCalendarDoesNotOverlap(updated, id);
    updateExactlyOne(calendars.update(updated, current.version()), "VERSION_CONFLICT");
    return change(
        id,
        updated.version(),
        "ServiceCalendarActivated",
        calendarPayload(updated, now));
  }

  private Change deactivateCalendar(
      AdministrativeCommand.DeactivateServiceCalendar command, ActorContext actor, Instant now) {
    UUID id = required(command.calendarId());
    ClosureReason deactivationReason = reason(command.reason());
    ServiceCalendar current =
        calendars
            .findByIdForUpdate(id)
            .orElseThrow(() -> rejected("SERVICE_CALENDAR_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    ServiceCalendar updated = current.deactivate(account(actor), now);
    updateExactlyOne(calendars.update(updated, current.version()), "VERSION_CONFLICT");
    return change(
        id,
        updated.version(),
        "ServiceCalendarDeactivated",
        calendarPayload(updated, now),
        deactivationReason.value());
  }

  private Change configureSchedule(
      AdministrativeCommand.ConfigureServiceSchedule command, ActorContext actor, Instant now) {
    UUID id = required(command.scheduleId());
    UUID calendarId = required(command.calendarId());
    requireCalendarForUpdate(calendarId);
    TimeWindow window =
        new TimeWindow(
            required(command.openingTime()),
            required(command.closingTime()),
            command.slotDurationMinutes());
    if (!schedules
        .findActiveOverlapping(
            calendarId,
            command.dayOfWeek(),
            window.opening(),
            window.closing(),
            id)
        .isEmpty()) {
      throw rejected("SERVICE_SCHEDULE_OVERLAP", id);
    }
    ServiceSchedule schedule =
        ServiceSchedule.createNew(
            id, calendarId, command.dayOfWeek(), window, account(actor), now);
    schedules.insert(schedule);
    return change(
        id,
        schedule.version(),
        "ServiceScheduleConfigured",
        schedulePayload(schedule, now));
  }

  private Change updateSchedule(
      AdministrativeCommand.UpdateServiceSchedule command, ActorContext actor, Instant now) {
    UUID id = required(command.scheduleId());
    ServiceSchedule current =
        schedules
            .findById(id)
            .orElseThrow(() -> rejected("SERVICE_SCHEDULE_NOT_FOUND", id));
    requireCalendarForUpdate(current.serviceCalendarId());
    current =
        schedules
            .findByIdForUpdate(id)
            .orElseThrow(() -> rejected("SERVICE_SCHEDULE_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    TimeWindow window =
        new TimeWindow(
            required(command.openingTime()),
            required(command.closingTime()),
            command.slotDurationMinutes());
    if (current.status() == ServiceScheduleStatus.ACTIVE
        && !schedules
            .findActiveOverlapping(
                current.serviceCalendarId(),
                command.dayOfWeek(),
                window.opening(),
                window.closing(),
                id)
            .isEmpty()) {
      throw rejected("SERVICE_SCHEDULE_OVERLAP", id);
    }
    ServiceSchedule updated =
        current.withUpdatedWindow(command.dayOfWeek(), window, account(actor), now);
    updateExactlyOne(schedules.update(updated, current.version()), "VERSION_CONFLICT");
    return change(
        id,
        updated.version(),
        "ServiceScheduleUpdated",
        schedulePayload(updated, now));
  }

  private Change deactivateSchedule(
      AdministrativeCommand.DeactivateServiceSchedule command, ActorContext actor, Instant now) {
    UUID id = required(command.scheduleId());
    ClosureReason deactivationReason = reason(command.reason());
    ServiceSchedule current =
        schedules
            .findById(id)
            .orElseThrow(() -> rejected("SERVICE_SCHEDULE_NOT_FOUND", id));
    requireCalendarForUpdate(current.serviceCalendarId());
    current =
        schedules
            .findByIdForUpdate(id)
            .orElseThrow(() -> rejected("SERVICE_SCHEDULE_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    if (current.status() == ServiceScheduleStatus.ACTIVE
        && schedules.countActiveByCalendarId(current.serviceCalendarId()) <= 1
        && calendars
            .findByIdForUpdate(current.serviceCalendarId())
            .map(calendar -> calendar.status() == ServiceCalendarStatus.ACTIVE)
            .orElse(false)) {
      throw rejected(
          "SERVICE_CALENDAR_HAS_NO_ACTIVE_SCHEDULES", current.serviceCalendarId());
    }
    ServiceSchedule updated = current.deactivate(account(actor), now);
    updateExactlyOne(schedules.update(updated, current.version()), "VERSION_CONFLICT");
    return change(
        id,
        updated.version(),
        "ServiceScheduleDeactivated",
        schedulePayload(updated, now),
        deactivationReason.value());
  }

  private Change scheduleClosure(
      AdministrativeCommand.ScheduleAdministrativeClosure command,
      ActorContext actor,
      Instant now) {
    UUID id = required(command.closureId());
    UUID calendarId = required(command.calendarId());
    ServiceCalendar calendar =
        calendars
            .findByIdForUpdate(calendarId)
            .orElseThrow(() -> rejected("SERVICE_CALENDAR_NOT_FOUND", calendarId));
    AdministrativeClosureScope scope = required(command.scope());
    UUID cabinId = command.cabinId();
    UUID tankId = command.tankId();
    ensureClosureResource(scope, cabinId, tankId);
    ClosureReason closureReason = reason(command.reason());
    if (closures
        .findExact(
            calendarId,
            scope,
            cabinId,
            tankId,
            required(command.startsAt()),
            required(command.endsAt()),
            closureReason.value())
        .isPresent()) {
      throw rejected("ADMINISTRATIVE_CLOSURE_DUPLICATED", id);
    }
    AdministrativeClosure closure =
        AdministrativeClosure.schedule(
            id,
            calendarId,
            scope,
            cabinId,
            tankId,
            required(command.startsAt()),
            required(command.endsAt()),
            closureReason,
            calendar.effectivePeriod(),
            ZoneId.of(calendar.timezone().value()),
            account(actor),
            now);
    closures.insert(closure);
    return change(
        id,
        closure.version(),
        "AdministrativeClosureScheduled",
        closurePayload(closure, now),
        closureReason.value());
  }

  private Change cancelClosure(
      AdministrativeCommand.CancelAdministrativeClosure command,
      ActorContext actor,
      Instant now) {
    UUID id = required(command.closureId());
    AdministrativeClosure current =
        closures
            .findByIdForUpdate(id)
            .orElseThrow(() -> rejected("ADMINISTRATIVE_CLOSURE_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    ClosureReason cancellationReason = reason(command.reason());
    AdministrativeClosure updated = current.cancel(account(actor), now, cancellationReason);
    updateExactlyOne(closures.update(updated, current.version()), "VERSION_CONFLICT");
    return change(
        id,
        updated.version(),
        "AdministrativeClosureCancelled",
        closurePayload(updated, now),
        cancellationReason.value());
  }

  private Change registerCabin(
      AdministrativeCommand.RegisterCabin command, ActorContext actor, Instant now) {
    UUID id = required(command.cabinId());
    String code = requiredText(command.code());
    if (cabins.findByCode(code).isPresent()) {
      throw rejected("CABIN_CODE_ALREADY_EXISTS", id);
    }
    Cabin cabin =
        Cabin.createNew(
            id,
            new ResourceCode(code),
            new DisplayName(requiredText(command.name())),
            required(command.cabinType()),
            account(actor),
            now);
    cabins.insert(cabin);
    return change(id, cabin.version(), "CabinRegistered", cabinPayload(cabin, now));
  }

  private Change updateCabin(
      AdministrativeCommand.UpdateCabin command, ActorContext actor, Instant now) {
    UUID id = required(command.cabinId());
    Cabin current = cabins.findById(id).orElseThrow(() -> rejected("CABIN_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    Cabin updated =
        current.update(
            new DisplayName(requiredText(command.name())),
            required(command.cabinType()),
            account(actor),
            now);
    updateExactlyOne(cabins.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "CabinUpdated", cabinPayload(updated, now));
  }

  private Change activateCabin(
      AdministrativeCommand.ActivateCabin command, ActorContext actor, Instant now) {
    UUID id = required(command.cabinId());
    Cabin current = cabins.findById(id).orElseThrow(() -> rejected("CABIN_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    Cabin updated = current.activate(account(actor), now);
    updateExactlyOne(cabins.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "CabinActivated", cabinPayload(updated, now));
  }

  private Change deactivateCabin(
      AdministrativeCommand.DeactivateCabin command, ActorContext actor, Instant now) {
    UUID id = required(command.cabinId());
    ClosureReason deactivationReason = reason(command.reason());
    Cabin current = cabins.findById(id).orElseThrow(() -> rejected("CABIN_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    Cabin updated = current.deactivate(account(actor), now);
    updateExactlyOne(cabins.update(updated, current.version()), "VERSION_CONFLICT");
    return change(
        id,
        updated.version(),
        "CabinDeactivated",
        cabinPayload(updated, now),
        deactivationReason.value());
  }

  private Change retireCabin(
      AdministrativeCommand.RetireCabin command, ActorContext actor, Instant now) {
    UUID id = required(command.cabinId());
    ClosureReason retirementReason = reason(command.reason());
    Cabin current =
        cabins.findByIdForUpdate(id).orElseThrow(() -> rejected("CABIN_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    Cabin updated =
        current.retire(account(actor), tanks.countNonRetiredByCabinId(id) == 0, now);
    updateExactlyOne(cabins.update(updated, current.version()), "VERSION_CONFLICT");
    return change(
        id,
        updated.version(),
        "CabinRetired",
        cabinPayload(updated, now),
        retirementReason.value());
  }

  private Change registerTank(
      AdministrativeCommand.RegisterTank command, ActorContext actor, Instant now) {
    UUID id = required(command.tankId());
    UUID cabinId = required(command.cabinId());
    Cabin cabin =
        cabins
            .findByIdForUpdate(cabinId)
            .orElseThrow(() -> rejected("CABIN_NOT_FOUND", cabinId));
    if (cabin.status() == ResourceStatus.RETIRED) {
      throw rejected("CABIN_RETIRED", cabinId);
    }
    String code = requiredText(command.code());
    if (tanks.findByCabinIdAndCode(cabinId, code).isPresent()) {
      throw rejected("TANK_CODE_ALREADY_EXISTS_IN_CABIN", id);
    }
    Tank tank =
        Tank.createNew(
            id,
            cabinId,
            new ResourceCode(code),
            new DisplayName(requiredText(command.name())),
            new PositiveCapacity(command.configuredCapacity()),
            account(actor),
            now);
    tanks.insert(tank);
    return change(id, tank.version(), "TankRegistered", tankPayload(tank, now));
  }

  private Change updateTank(
      AdministrativeCommand.UpdateTank command, ActorContext actor, Instant now) {
    UUID id = required(command.tankId());
    Tank current = tanks.findById(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    UUID cabinId = current.cabinId();
    cabins
        .findByIdForUpdate(cabinId)
        .orElseThrow(() -> rejected("CABIN_NOT_FOUND", cabinId));
    current =
        tanks.findByIdForUpdate(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    Tank updated =
        current.update(
            new DisplayName(requiredText(command.name())),
            new PositiveCapacity(command.configuredCapacity()),
            account(actor),
            now);
    updateExactlyOne(tanks.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "TankUpdated", tankPayload(updated, now));
  }

  private Change activateTank(
      AdministrativeCommand.ActivateTank command, ActorContext actor, Instant now) {
    UUID id = required(command.tankId());
    Tank current = tanks.findById(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    UUID cabinId = current.cabinId();
    Cabin cabin =
        cabins
            .findByIdForUpdate(cabinId)
            .orElseThrow(() -> rejected("CABIN_NOT_FOUND", cabinId));
    current =
        tanks.findByIdForUpdate(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    if (cabin.status() == ResourceStatus.RETIRED) {
      throw rejected("CABIN_RETIRED", cabin.id());
    }
    Tank updated = current.activate(account(actor), now);
    updateExactlyOne(tanks.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "TankActivated", tankPayload(updated, now));
  }

  private Change deactivateTank(
      AdministrativeCommand.DeactivateTank command, ActorContext actor, Instant now) {
    UUID id = required(command.tankId());
    ClosureReason deactivationReason = reason(command.reason());
    Tank current = tanks.findById(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    UUID cabinId = current.cabinId();
    cabins
        .findByIdForUpdate(cabinId)
        .orElseThrow(() -> rejected("CABIN_NOT_FOUND", cabinId));
    current =
        tanks.findByIdForUpdate(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    Tank updated = current.deactivate(account(actor), now);
    updateExactlyOne(tanks.update(updated, current.version()), "VERSION_CONFLICT");
    return change(
        id,
        updated.version(),
        "TankDeactivated",
        tankPayload(updated, now),
        deactivationReason.value());
  }

  private Change retireTank(
      AdministrativeCommand.RetireTank command, ActorContext actor, Instant now) {
    UUID id = required(command.tankId());
    ClosureReason retirementReason = reason(command.reason());
    Tank current = tanks.findById(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    UUID cabinId = current.cabinId();
    cabins
        .findByIdForUpdate(cabinId)
        .orElseThrow(() -> rejected("CABIN_NOT_FOUND", cabinId));
    current =
        tanks.findByIdForUpdate(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    expected(command.expectedVersion(), current.version());
    Tank updated = current.retire(account(actor), now);
    updateExactlyOne(tanks.update(updated, current.version()), "VERSION_CONFLICT");
    return change(
        id,
        updated.version(),
        "TankRetired",
        tankPayload(updated, now),
        retirementReason.value());
  }

  private void ensureCalendarDoesNotOverlap(ServiceCalendar calendar, UUID excludedId) {
    if (calendar.status() == ServiceCalendarStatus.ACTIVE
        && !calendars.findActiveOverlapping(calendar.effectivePeriod(), excludedId).isEmpty()) {
      throw rejected("SERVICE_CALENDAR_OVERLAP", calendar.id());
    }
  }

  private void ensureClosuresWithinCalendar(ServiceCalendar calendar) {
    ZoneId zone = ZoneId.of(calendar.timezone().value());
    for (AdministrativeClosure closure : closures.findAll()) {
      if (!calendar.id().equals(closure.serviceCalendarId())) {
        continue;
      }
      LocalDate start = closure.startsAt().atZone(zone).toLocalDate();
      LocalDate end = closure.endsAt().minusNanos(1).atZone(zone).toLocalDate();
      if (start.isBefore(calendar.effectivePeriod().from())
          || (calendar.effectivePeriod().until() != null
              && end.isAfter(calendar.effectivePeriod().until()))) {
        throw rejected("ADMINISTRATIVE_CLOSURE_INVALID_PERIOD", closure.id());
      }
    }
  }

  private void ensureClosureResource(AdministrativeClosureScope scope, UUID cabinId, UUID tankId) {
    switch (scope) {
      case SERVICE -> {
        if (cabinId != null || tankId != null) {
          throw rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE");
        }
      }
      case CABIN -> {
        if (cabinId == null || tankId != null) {
          throw rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE");
        }
        Cabin cabin =
            cabins
                .findByIdForUpdate(cabinId)
                .orElseThrow(() -> rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE"));
        if (cabin.status() == ResourceStatus.RETIRED) {
          throw rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE");
        }
      }
      case TANK -> {
        if (cabinId != null || tankId == null) {
          throw rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE");
        }
        Tank tank =
            tanks
                .findByIdForUpdate(tankId)
                .orElseThrow(() -> rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE"));
        if (tank.status() == ResourceStatus.RETIRED) {
          throw rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE");
        }
      }
    }
  }

  private static Map<String, Object> calendarPayload(ServiceCalendar value, Instant now) {
    return map(
        "calendarId", value.id(), "code", value.code().value(), "name", value.name().value(),
        "timezone", value.timezone().value(), "effectiveFrom", value.effectivePeriod().from(),
        "effectiveUntil", value.effectivePeriod().until(), "status", value.status(),
        "version", value.version(), "occurredAt", now);
  }

  private static Map<String, Object> schedulePayload(ServiceSchedule value, Instant now) {
    return map(
        "scheduleId", value.id(), "calendarId", value.serviceCalendarId(), "dayOfWeek", value.dayOfWeek(),
        "openingTime", value.timeWindow().opening(), "closingTime", value.timeWindow().closing(),
        "slotDurationMinutes", value.timeWindow().slotDurationMinutes(), "status", value.status(),
        "version", value.version(), "occurredAt", now);
  }

  private static Map<String, Object> closurePayload(AdministrativeClosure value, Instant now) {
    return map(
        "closureId", value.id(), "calendarId", value.serviceCalendarId(), "scope", value.scope(),
        "cabinId", value.cabinId(), "tankId", value.tankId(), "startsAt", value.startsAt(),
        "endsAt", value.endsAt(), "reason", value.reason().value(), "status", value.status(),
        "version", value.version(), "cancelledAt", value.cancelledAt(), "occurredAt", now);
  }

  private static Map<String, Object> cabinPayload(Cabin value, Instant now) {
    return map(
        "cabinId", value.id(), "code", value.code().value(), "name", value.name().value(),
        "cabinType", value.cabinType(), "status", value.status(), "version", value.version(),
        "occurredAt", now);
  }

  private static Map<String, Object> tankPayload(Tank value, Instant now) {
    return map(
        "tankId", value.id(), "cabinId", value.cabinId(), "code", value.code().value(),
        "name", value.name().value(), "configuredCapacity", value.configuredCapacity().value(),
        "status", value.status(), "version", value.version(), "occurredAt", now);
  }

  private static Change change(UUID id, int version, String type, Map<String, Object> payload) {
    return new Change(id, version, List.of(new Event(type, id, version, payload)), null);
  }

  private static Change change(
      UUID id, int version, String type, Map<String, Object> payload, String auditReason) {
    return new Change(id, version, List.of(new Event(type, id, version, payload)), auditReason);
  }

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      result.put((String) values[index], values[index + 1]);
    }
    return result;
  }

  private static UUID account(ActorContext actor) {
    if (actor == null || actor.accountId() == null) {
      throw rejected("ACTOR_NOT_AUTHORIZED");
    }
    return actor.accountId();
  }

  private static void expected(int supplied, int actual) {
    if (supplied != actual) {
      throw rejected("VERSION_CONFLICT");
    }
  }

  private static void updateExactlyOne(int updated, String errorCode) {
    if (updated != 1) {
      throw rejected(errorCode);
    }
  }

  private static ClosureReason reason(String reason) {
    return new ClosureReason(requiredText(reason));
  }

  private static String requiredText(String value) {
    if (value == null || value.isBlank()) {
      throw rejected("INVALID_MESSAGE");
    }
    return value;
  }

  private static <T> T required(T value) {
    if (value == null) {
      throw rejected("INVALID_MESSAGE");
    }
    return value;
  }

  private static String domainCode(DomainRuleViolationException exception) {
    return switch (exception.code().name()) {
      case "INVALID_CODE", "INVALID_NAME", "INVALID_PERIOD", "INVALID_REASON", "INVALID_CAPACITY" -> "INVALID_MESSAGE";
      default -> exception.code().name();
    };
  }

  private static String duplicateCode(String operationType, DataIntegrityViolationException exception) {
    String message =
        exception.getMostSpecificCause() == null
            ? exception.getMessage()
            : exception.getMostSpecificCause().getMessage();
    if (message != null && message.contains("ux_service_calendars_code")) {
      return "SERVICE_CALENDAR_CODE_ALREADY_EXISTS";
    }
    if (message != null && message.contains("ux_cabins_code")) {
      return "CABIN_CODE_ALREADY_EXISTS";
    }
    if (message != null && message.contains("ux_tanks_cabin_code")) {
      return "TANK_CODE_ALREADY_EXISTS_IN_CABIN";
    }
    if (message != null && message.contains("ux_administrative_closures_exact")) {
      return "ADMINISTRATIVE_CLOSURE_DUPLICATED";
    }
    if (message != null && message.contains("ex_service_calendars_active_no_overlap")) {
      return "SERVICE_CALENDAR_OVERLAP";
    }
    if (message != null && message.contains("ex_service_schedules_active_no_overlap")) {
      return "SERVICE_SCHEDULE_OVERLAP";
    }
    return switch (operationType) {
      case "CreateServiceCalendar" -> "SERVICE_CALENDAR_CODE_ALREADY_EXISTS";
      case "RegisterCabin" -> "CABIN_CODE_ALREADY_EXISTS";
      case "RegisterTank" -> "TANK_CODE_ALREADY_EXISTS_IN_CABIN";
      case "ScheduleAdministrativeClosure" -> "ADMINISTRATIVE_CLOSURE_DUPLICATED";
      default -> "VERSION_CONFLICT";
    };
  }

  private static CommandRejectedException rejected(String code) {
    return new CommandRejectedException(code);
  }

  private static CommandRejectedException rejected(String code, UUID aggregateId) {
    return new CommandRejectedException(code, aggregateId);
  }

  private static CommandRejectedException rejected(String code, Throwable cause) {
    return new CommandRejectedException(code, cause);
  }

  private ServiceCalendar requireCalendarForUpdate(UUID id) {
    return calendars
        .findByIdForUpdate(id)
        .orElseThrow(() -> rejected("SERVICE_CALENDAR_NOT_FOUND", id));
  }

  public record Change(UUID aggregateId, int aggregateVersion, List<Event> events, String auditReason) {}

  public record Event(String type, UUID aggregateId, int aggregateVersion, Map<String, Object> payload) {}
}
