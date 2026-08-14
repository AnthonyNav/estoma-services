package com.estoma.washadministration.application.command;

import com.estoma.washadministration.application.authorization.AuthorizationEvaluator;
import com.estoma.washadministration.application.port.AdministrativeClosureRepository;
import com.estoma.washadministration.application.port.CabinRepository;
import com.estoma.washadministration.application.port.ServiceCalendarRepository;
import com.estoma.washadministration.application.port.ServiceScheduleRepository;
import com.estoma.washadministration.application.port.TankRepository;
import com.estoma.washadministration.domain.calendar.ServiceCalendar;
import com.estoma.washadministration.domain.closure.AdministrativeClosure;
import com.estoma.washadministration.domain.closure.AdministrativeClosureScope;
import com.estoma.washadministration.domain.resource.Cabin;
import com.estoma.washadministration.domain.resource.CabinType;
import com.estoma.washadministration.domain.resource.ResourceStatus;
import com.estoma.washadministration.domain.resource.Tank;
import com.estoma.washadministration.domain.schedule.ServiceSchedule;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Executes the complete V1 business behavior without depending on a transport adapter. */
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
    CommandMetadata metadata = request.metadata();
    authorization.require(metadata.actor(), metadata.operationType());
    try {
      return switch (metadata.operationType()) {
        case "CreateServiceCalendar" -> createCalendar(request.payload(), metadata.actor(), now);
        case "UpdateServiceCalendar" -> updateCalendar(request.payload(), metadata.actor(), now);
        case "ActivateServiceCalendar" -> activateCalendar(request.payload(), metadata.actor(), now);
        case "DeactivateServiceCalendar" -> deactivateCalendar(request.payload(), metadata.actor(), now);
        case "ConfigureServiceSchedule" -> configureSchedule(request.payload(), metadata.actor(), now);
        case "UpdateServiceSchedule" -> updateSchedule(request.payload(), metadata.actor(), now);
        case "DeactivateServiceSchedule" -> deactivateSchedule(request.payload(), metadata.actor(), now);
        case "ScheduleAdministrativeClosure" -> scheduleClosure(request.payload(), metadata.actor(), now);
        case "CancelAdministrativeClosure" -> cancelClosure(request.payload(), metadata.actor(), now);
        case "RegisterCabin" -> registerCabin(request.payload(), metadata.actor(), now);
        case "UpdateCabin" -> updateCabin(request.payload(), metadata.actor(), now);
        case "ActivateCabin" -> activateCabin(request.payload(), metadata.actor(), now);
        case "DeactivateCabin" -> deactivateCabin(request.payload(), metadata.actor(), now);
        case "RetireCabin" -> retireCabin(request.payload(), metadata.actor(), now);
        case "RegisterTank" -> registerTank(request.payload(), metadata.actor(), now);
        case "UpdateTank" -> updateTank(request.payload(), metadata.actor(), now);
        case "ActivateTank" -> activateTank(request.payload(), metadata.actor(), now);
        case "DeactivateTank" -> deactivateTank(request.payload(), metadata.actor(), now);
        case "RetireTank" -> retireTank(request.payload(), metadata.actor(), now);
        default -> throw rejected("INVALID_MESSAGE");
      };
    } catch (CommandRejectedException exception) {
      throw exception;
    } catch (DomainRuleViolationException exception) {
      throw rejected(domainCode(exception));
    } catch (DataIntegrityViolationException exception) {
      throw rejected(duplicateCode(metadata.operationType(), exception));
    } catch (IllegalArgumentException exception) {
      throw rejected("INVALID_MESSAGE", exception);
    }
  }

  private Change createCalendar(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "calendarId");
    String code = text(p, "code");
    EffectivePeriod period = period(p, "effectiveFrom", "effectiveUntil");
    ServiceCalendar calendar = ServiceCalendar.createNew(
        id,
        new ResourceCode(code),
        new DisplayName(text(p, "name")),
        new TimeZoneId(text(p, "timezone")),
        period,
        account(actor),
        now);
    if (calendars.findByCode(code).isPresent()) {
      throw rejected("SERVICE_CALENDAR_CODE_ALREADY_EXISTS", id);
    }
    calendars.insert(calendar);
    return change(calendar.id(), calendar.version(), "ServiceCalendarCreated", calendarPayload(calendar, now));
  }

  private Change updateCalendar(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "calendarId");
    ServiceCalendar current = calendars.findByIdForUpdate(id).orElseThrow(() -> rejected("SERVICE_CALENDAR_NOT_FOUND", id));
    expected(p, current.version());
    EffectivePeriod period = period(p, "effectiveFrom", "effectiveUntil");
    ServiceCalendar updated = current.withUpdatedDetails(
        new DisplayName(text(p, "name")),
        new TimeZoneId(text(p, "timezone")),
        period,
        account(actor),
        now);
    ensureCalendarDoesNotOverlap(updated, id);
    ensureClosuresWithinCalendar(updated);
    updateExactlyOne(calendars.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "ServiceCalendarUpdated", calendarPayload(updated, now));
  }

  private Change activateCalendar(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "calendarId");
    ServiceCalendar current = calendars.findByIdForUpdate(id).orElseThrow(() -> rejected("SERVICE_CALENDAR_NOT_FOUND", id));
    expected(p, current.version());
    if (schedules.countActiveByCalendarId(id) == 0) {
      throw rejected("SERVICE_CALENDAR_HAS_NO_ACTIVE_SCHEDULES", id);
    }
    ServiceCalendar updated = current.activate(account(actor), now);
    ensureCalendarDoesNotOverlap(updated, id);
    updateExactlyOne(calendars.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "ServiceCalendarActivated", calendarPayload(updated, now));
  }

  private Change deactivateCalendar(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "calendarId");
    ClosureReason deactivationReason = reason(p);
    ServiceCalendar current = calendars.findByIdForUpdate(id).orElseThrow(() -> rejected("SERVICE_CALENDAR_NOT_FOUND", id));
    expected(p, current.version());
    ServiceCalendar updated = current.deactivate(account(actor), now);
    updateExactlyOne(calendars.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "ServiceCalendarDeactivated", calendarPayload(updated, now), deactivationReason.value());
  }

  private Change configureSchedule(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "scheduleId");
    UUID calendarId = uuid(p, "calendarId");
    requireCalendarForUpdate(calendarId);
    int day = integer(p, "dayOfWeek");
    TimeWindow window = window(p);
    if (!schedules.findActiveOverlapping(calendarId, day, window.opening(), window.closing(), id).isEmpty()) {
      throw rejected("SERVICE_SCHEDULE_OVERLAP", id);
    }
    ServiceSchedule schedule = ServiceSchedule.createNew(
        id, calendarId, day, window, account(actor), now);
    schedules.insert(schedule);
    return change(id, schedule.version(), "ServiceScheduleConfigured", schedulePayload(schedule, now));
  }

  private Change updateSchedule(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "scheduleId");
    ServiceSchedule current = schedules.findById(id).orElseThrow(() -> rejected("SERVICE_SCHEDULE_NOT_FOUND", id));
    requireCalendarForUpdate(current.serviceCalendarId());
    current = schedules.findByIdForUpdate(id).orElseThrow(() -> rejected("SERVICE_SCHEDULE_NOT_FOUND", id));
    expected(p, current.version());
    TimeWindow window = window(p);
    int day = integer(p, "dayOfWeek");
    if (current.status() == com.estoma.washadministration.domain.schedule.ServiceScheduleStatus.ACTIVE
        && !schedules.findActiveOverlapping(current.serviceCalendarId(), day, window.opening(), window.closing(), id).isEmpty()) {
      throw rejected("SERVICE_SCHEDULE_OVERLAP", id);
    }
    ServiceSchedule updated = current.withUpdatedWindow(day, window, account(actor), now);
    updateExactlyOne(schedules.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "ServiceScheduleUpdated", schedulePayload(updated, now));
  }

  private Change deactivateSchedule(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "scheduleId");
    ClosureReason deactivationReason = reason(p);
    ServiceSchedule current = schedules.findById(id).orElseThrow(() -> rejected("SERVICE_SCHEDULE_NOT_FOUND", id));
    requireCalendarForUpdate(current.serviceCalendarId());
    current = schedules.findByIdForUpdate(id).orElseThrow(() -> rejected("SERVICE_SCHEDULE_NOT_FOUND", id));
    expected(p, current.version());
    if (current.status() == com.estoma.washadministration.domain.schedule.ServiceScheduleStatus.ACTIVE
        && schedules.countActiveByCalendarId(current.serviceCalendarId()) <= 1
        && calendars.findByIdForUpdate(current.serviceCalendarId()).map(calendar -> calendar.status()
            == com.estoma.washadministration.domain.calendar.ServiceCalendarStatus.ACTIVE).orElse(false)) {
      throw rejected("SERVICE_CALENDAR_HAS_NO_ACTIVE_SCHEDULES", current.serviceCalendarId());
    }
    ServiceSchedule updated = current.deactivate(account(actor), now);
    updateExactlyOne(schedules.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "ServiceScheduleDeactivated", schedulePayload(updated, now), deactivationReason.value());
  }

  private Change scheduleClosure(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "closureId");
    UUID calendarId = uuid(p, "calendarId");
    ServiceCalendar calendar = calendars.findByIdForUpdate(calendarId)
        .orElseThrow(() -> rejected("SERVICE_CALENDAR_NOT_FOUND", calendarId));
    AdministrativeClosureScope scope = enumValue(p, "scope", AdministrativeClosureScope.class);
    UUID cabinId = optionalUuid(p, "cabinId");
    UUID tankId = optionalUuid(p, "tankId");
    ensureClosureResource(scope, cabinId, tankId);
    Instant startsAt = instant(p, "startsAt");
    Instant endsAt = instant(p, "endsAt");
    ClosureReason closureReason = reason(p);
    if (closures.findExact(calendarId, scope, cabinId, tankId, startsAt, endsAt, closureReason.value()).isPresent()) {
      throw rejected("ADMINISTRATIVE_CLOSURE_DUPLICATED", id);
    }
    AdministrativeClosure closure = AdministrativeClosure.schedule(
        id,
        calendarId,
        scope,
        cabinId,
        tankId,
        startsAt,
        endsAt,
        closureReason,
        calendar.effectivePeriod(),
        ZoneId.of(calendar.timezone().value()),
        account(actor),
        now);
    closures.insert(closure);
    return change(id, closure.version(), "AdministrativeClosureScheduled", closurePayload(closure, now), closureReason.value());
  }

  private Change cancelClosure(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "closureId");
    AdministrativeClosure current = closures.findByIdForUpdate(id)
        .orElseThrow(() -> rejected("ADMINISTRATIVE_CLOSURE_NOT_FOUND", id));
    expected(p, current.version());
    ClosureReason cancellationReason = reason(p);
    AdministrativeClosure updated = current.cancel(account(actor), now, cancellationReason);
    updateExactlyOne(closures.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "AdministrativeClosureCancelled", closurePayload(updated, now), cancellationReason.value());
  }

  private Change registerCabin(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "cabinId");
    String code = text(p, "code");
    if (cabins.findByCode(code).isPresent()) {
      throw rejected("CABIN_CODE_ALREADY_EXISTS", id);
    }
    Cabin cabin = Cabin.createNew(
        id,
        new ResourceCode(code),
        new DisplayName(text(p, "name")),
        enumValue(p, "cabinType", CabinType.class),
        account(actor),
        now);
    cabins.insert(cabin);
    return change(id, cabin.version(), "CabinRegistered", cabinPayload(cabin, now));
  }

  private Change updateCabin(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "cabinId");
    Cabin current = cabins.findById(id).orElseThrow(() -> rejected("CABIN_NOT_FOUND", id));
    expected(p, current.version());
    Cabin updated = current.update(
        new DisplayName(text(p, "name")),
        enumValue(p, "cabinType", CabinType.class),
        account(actor),
        now);
    updateExactlyOne(cabins.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "CabinUpdated", cabinPayload(updated, now));
  }

  private Change activateCabin(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "cabinId");
    Cabin current = cabins.findById(id).orElseThrow(() -> rejected("CABIN_NOT_FOUND", id));
    expected(p, current.version());
    Cabin updated = current.activate(account(actor), now);
    updateExactlyOne(cabins.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "CabinActivated", cabinPayload(updated, now));
  }

  private Change deactivateCabin(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "cabinId");
    ClosureReason deactivationReason = reason(p);
    Cabin current = cabins.findById(id).orElseThrow(() -> rejected("CABIN_NOT_FOUND", id));
    expected(p, current.version());
    Cabin updated = current.deactivate(account(actor), now);
    updateExactlyOne(cabins.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "CabinDeactivated", cabinPayload(updated, now), deactivationReason.value());
  }

  private Change retireCabin(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "cabinId");
    ClosureReason retirementReason = reason(p);
    Cabin current = cabins.findByIdForUpdate(id).orElseThrow(() -> rejected("CABIN_NOT_FOUND", id));
    expected(p, current.version());
    Cabin updated = current.retire(account(actor), tanks.countNonRetiredByCabinId(id) == 0, now);
    updateExactlyOne(cabins.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "CabinRetired", cabinPayload(updated, now), retirementReason.value());
  }

  private Change registerTank(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "tankId");
    UUID cabinId = uuid(p, "cabinId");
    Cabin cabin = cabins.findByIdForUpdate(cabinId).orElseThrow(() -> rejected("CABIN_NOT_FOUND", cabinId));
    if (cabin.status() == ResourceStatus.RETIRED) {
      throw rejected("CABIN_RETIRED", cabinId);
    }
    String code = text(p, "code");
    if (tanks.findByCabinIdAndCode(cabinId, code).isPresent()) {
      throw rejected("TANK_CODE_ALREADY_EXISTS_IN_CABIN", id);
    }
    Tank tank = Tank.createNew(
        id,
        cabinId,
        new ResourceCode(code),
        new DisplayName(text(p, "name")),
        new PositiveCapacity(integer(p, "configuredCapacity")),
        account(actor),
        now);
    tanks.insert(tank);
    return change(id, tank.version(), "TankRegistered", tankPayload(tank, now));
  }

  private Change updateTank(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "tankId");
    Tank current = tanks.findById(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    UUID cabinId = current.cabinId();
    cabins.findByIdForUpdate(cabinId).orElseThrow(() -> rejected("CABIN_NOT_FOUND", cabinId));
    current = tanks.findByIdForUpdate(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    expected(p, current.version());
    Tank updated = current.update(
        new DisplayName(text(p, "name")),
        new PositiveCapacity(integer(p, "configuredCapacity")),
        account(actor),
        now);
    updateExactlyOne(tanks.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "TankUpdated", tankPayload(updated, now));
  }

  private Change activateTank(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "tankId");
    Tank current = tanks.findById(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    UUID cabinId = current.cabinId();
    cabins.findByIdForUpdate(cabinId).orElseThrow(() -> rejected("CABIN_NOT_FOUND", cabinId));
    current = tanks.findByIdForUpdate(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    expected(p, current.version());
    Cabin cabin = cabins.findByIdForUpdate(cabinId).orElseThrow(() -> rejected("CABIN_NOT_FOUND", cabinId));
    if (cabin.status() == ResourceStatus.RETIRED) {
      throw rejected("CABIN_RETIRED", cabin.id());
    }
    Tank updated = current.activate(account(actor), now);
    updateExactlyOne(tanks.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "TankActivated", tankPayload(updated, now));
  }

  private Change deactivateTank(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "tankId");
    ClosureReason deactivationReason = reason(p);
    Tank current = tanks.findById(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    UUID cabinId = current.cabinId();
    cabins.findByIdForUpdate(cabinId).orElseThrow(() -> rejected("CABIN_NOT_FOUND", cabinId));
    current = tanks.findByIdForUpdate(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    expected(p, current.version());
    Tank updated = current.deactivate(account(actor), now);
    updateExactlyOne(tanks.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "TankDeactivated", tankPayload(updated, now), deactivationReason.value());
  }

  private Change retireTank(JsonNode p, ActorContext actor, Instant now) {
    UUID id = uuid(p, "tankId");
    ClosureReason retirementReason = reason(p);
    Tank current = tanks.findById(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    UUID cabinId = current.cabinId();
    cabins.findByIdForUpdate(cabinId).orElseThrow(() -> rejected("CABIN_NOT_FOUND", cabinId));
    current = tanks.findByIdForUpdate(id).orElseThrow(() -> rejected("TANK_NOT_FOUND", id));
    expected(p, current.version());
    Tank updated = current.retire(account(actor), now);
    updateExactlyOne(tanks.update(updated, current.version()), "VERSION_CONFLICT");
    return change(id, updated.version(), "TankRetired", tankPayload(updated, now), retirementReason.value());
  }

  private void ensureCalendarDoesNotOverlap(ServiceCalendar calendar, UUID excludedId) {
    if (calendar.status() == com.estoma.washadministration.domain.calendar.ServiceCalendarStatus.ACTIVE
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
        if (cabinId != null || tankId != null) throw rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE");
      }
      case CABIN -> {
        if (cabinId == null || tankId != null) throw rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE");
        Cabin cabin = cabins.findByIdForUpdate(cabinId).orElseThrow(() -> rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE"));
        if (cabin.status() == ResourceStatus.RETIRED) throw rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE");
      }
      case TANK -> {
        if (cabinId != null || tankId == null) throw rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE");
        Tank tank = tanks.findByIdForUpdate(tankId).orElseThrow(() -> rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE"));
        if (tank.status() == ResourceStatus.RETIRED) throw rejected("ADMINISTRATIVE_CLOSURE_INVALID_SCOPE");
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
    if (actor == null || actor.accountId() == null) throw rejected("ACTOR_NOT_AUTHORIZED");
    return actor.accountId();
  }

  private static void expected(JsonNode p, int actual) {
    if (!p.hasNonNull("expectedVersion") || !p.path("expectedVersion").isIntegralNumber()) {
      throw rejected("INVALID_MESSAGE");
    }
    long expectedVersion = p.path("expectedVersion").longValue();
    if (expectedVersion < Integer.MIN_VALUE || expectedVersion > Integer.MAX_VALUE
        || (int) expectedVersion != actual) {
      throw rejected("VERSION_CONFLICT");
    }
  }

  private static void updateExactlyOne(int updated, String errorCode) {
    if (updated != 1) throw rejected(errorCode);
  }

  private static EffectivePeriod period(JsonNode p, String fromField, String untilField) {
    try {
      return new EffectivePeriod(
          LocalDate.parse(text(p, fromField)),
          optionalText(p, untilField) == null ? null : LocalDate.parse(optionalText(p, untilField)));
    } catch (DateTimeException exception) {
      throw rejected("SERVICE_CALENDAR_INVALID_PERIOD", exception);
    }
  }

  private static TimeWindow window(JsonNode p) {
    try {
      return new TimeWindow(
          LocalTime.parse(text(p, "openingTime")),
          LocalTime.parse(text(p, "closingTime")),
          integer(p, "slotDurationMinutes"));
    } catch (DateTimeException exception) {
      throw rejected("SERVICE_SCHEDULE_INVALID_INTERVAL", exception);
    }
  }

  private static ClosureReason reason(JsonNode p) {
    return new ClosureReason(text(p, "reason"));
  }

  private static Instant instant(JsonNode p, String field) {
    try {
      return Instant.parse(text(p, field));
    } catch (DateTimeException exception) {
      throw rejected("ADMINISTRATIVE_CLOSURE_INVALID_PERIOD", exception);
    }
  }

  private static String text(JsonNode p, String field) {
    String value = optionalText(p, field);
    if (value == null || value.isBlank()) throw rejected("INVALID_MESSAGE");
    return value;
  }

  private static String optionalText(JsonNode p, String field) {
    if (!p.has(field) || p.path(field).isNull()) {
      return null;
    }
    JsonNode value = p.path(field);
    if (!value.isTextual()) {
      throw rejected("INVALID_MESSAGE");
    }
    return value.asString();
  }

  private static int integer(JsonNode p, String field) {
    if (!p.hasNonNull(field) || !p.path(field).isIntegralNumber()) throw rejected("INVALID_MESSAGE");
    long value = p.path(field).longValue();
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw rejected("INVALID_MESSAGE");
    return (int) value;
  }

  private static UUID uuid(JsonNode p, String field) {
    String value = text(p, field);
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw rejected("INVALID_MESSAGE", exception);
    }
  }

  private static UUID optionalUuid(JsonNode p, String field) {
    String value = optionalText(p, field);
    if (value == null) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw rejected("INVALID_MESSAGE", exception);
    }
  }

  private static <T extends Enum<T>> T enumValue(JsonNode p, String field, Class<T> type) {
    try {
      return Enum.valueOf(type, text(p, field));
    } catch (IllegalArgumentException exception) {
      throw rejected("INVALID_MESSAGE", exception);
    }
  }

  private static String domainCode(DomainRuleViolationException exception) {
    return switch (exception.code().name()) {
      case "INVALID_CODE", "INVALID_NAME", "INVALID_PERIOD", "INVALID_REASON", "INVALID_CAPACITY" -> "INVALID_MESSAGE";
      default -> exception.code().name();
    };
  }

  private static String duplicateCode(String operationType, DataIntegrityViolationException exception) {
    String message = exception.getMostSpecificCause() == null
        ? exception.getMessage()
        : exception.getMostSpecificCause().getMessage();
    if (message != null && message.contains("ux_service_calendars_code")) return "SERVICE_CALENDAR_CODE_ALREADY_EXISTS";
    if (message != null && message.contains("ux_cabins_code")) return "CABIN_CODE_ALREADY_EXISTS";
    if (message != null && message.contains("ux_tanks_cabin_code")) return "TANK_CODE_ALREADY_EXISTS_IN_CABIN";
    if (message != null && message.contains("ux_administrative_closures_exact")) return "ADMINISTRATIVE_CLOSURE_DUPLICATED";
    if (message != null && message.contains("ex_service_calendars_active_no_overlap")) return "SERVICE_CALENDAR_OVERLAP";
    if (message != null && message.contains("ex_service_schedules_active_no_overlap")) return "SERVICE_SCHEDULE_OVERLAP";
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
    return calendars.findByIdForUpdate(id).orElseThrow(() -> rejected("SERVICE_CALENDAR_NOT_FOUND", id));
  }

  public record Change(UUID aggregateId, int aggregateVersion, List<Event> events, String auditReason) {}

  public record Event(String type, UUID aggregateId, int aggregateVersion, Map<String, Object> payload) {}

}
