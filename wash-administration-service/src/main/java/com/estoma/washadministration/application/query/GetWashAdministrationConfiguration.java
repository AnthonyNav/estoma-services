package com.estoma.washadministration.application.query;

import com.estoma.washadministration.application.port.AdministrativeClosureRepository;
import com.estoma.washadministration.application.port.CabinRepository;
import com.estoma.washadministration.application.port.ResourcePriorityRepository;
import com.estoma.washadministration.application.port.ServiceCalendarRepository;
import com.estoma.washadministration.application.port.ServiceScheduleRepository;
import com.estoma.washadministration.application.port.TankRepository;
import com.estoma.washadministration.domain.calendar.ServiceCalendar;
import com.estoma.washadministration.domain.closure.AdministrativeClosure;
import com.estoma.washadministration.domain.priority.ResourcePriority;
import com.estoma.washadministration.domain.resource.Cabin;
import com.estoma.washadministration.domain.resource.Tank;
import com.estoma.washadministration.domain.schedule.ServiceSchedule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Authoritative, side-effect-free configuration snapshot for service consumers. */
@Component
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GetWashAdministrationConfiguration {

  private static final List<String> ALLOWED_REQUESTERS = List.of("appointments", "wash-execution");

  private final ServiceCalendarRepository calendars;
  private final ServiceScheduleRepository schedules;
  private final AdministrativeClosureRepository closures;
  private final CabinRepository cabins;
  private final TankRepository tanks;
  private final ResourcePriorityRepository priorities;

  public GetWashAdministrationConfiguration(
      ServiceCalendarRepository calendars,
      ServiceScheduleRepository schedules,
      AdministrativeClosureRepository closures,
      CabinRepository cabins,
      TankRepository tanks,
      ResourcePriorityRepository priorities) {
    this.calendars = calendars;
    this.schedules = schedules;
    this.closures = closures;
    this.cabins = cabins;
    this.tanks = tanks;
    this.priorities = priorities;
  }

  public Snapshot execute(String requester, LocalDate referenceDate) {
    if (!ALLOWED_REQUESTERS.contains(requester)) {
      throw new QueryRejectedException("QUERY_PRODUCER_NOT_ALLOWED");
    }
    if (referenceDate == null) {
      return snapshot(calendars.findAll(), schedules.findAll(), closures.findAll(), cabins.findAll(), tanks.findAll(), priorities.findAll());
    }
    if (referenceDate.isBefore(LocalDate.MIN) || referenceDate.isAfter(LocalDate.MAX)) {
      throw new QueryRejectedException("INVALID_REFERENCE_DATE");
    }
    List<ServiceCalendar> selectedCalendars = calendars.findAll().stream()
        .filter(calendar -> contains(calendar, referenceDate))
        .toList();
    Map<java.util.UUID, ServiceCalendar> calendarById = selectedCalendars.stream()
        .collect(java.util.stream.Collectors.toMap(ServiceCalendar::id, calendar -> calendar));
    java.util.Set<java.util.UUID> calendarIds = calendarById.keySet();
    List<ServiceSchedule> selectedSchedules = schedules.findAll().stream()
        .filter(schedule -> calendarIds.contains(schedule.serviceCalendarId()))
        .toList();
    List<AdministrativeClosure> selectedClosures = closures.findAll().stream()
        .filter(closure -> {
          ServiceCalendar calendar = calendarById.get(closure.serviceCalendarId());
          return calendar != null && overlapsDate(closure, calendar, referenceDate);
        })
        .toList();
    return snapshot(selectedCalendars, selectedSchedules, selectedClosures, cabins.findAll(), tanks.findAll(), priorities.findAll());
  }

  public Snapshot execute(String requester) {
    return execute(requester, null);
  }

  private static boolean contains(ServiceCalendar calendar, LocalDate date) {
    return !date.isBefore(calendar.effectivePeriod().from())
        && (calendar.effectivePeriod().until() == null || !date.isAfter(calendar.effectivePeriod().until()));
  }

  private static boolean overlapsDate(AdministrativeClosure closure, ServiceCalendar calendar, LocalDate date) {
    try {
      ZoneId zone = ZoneId.of(calendar.timezone().value());
      Instant dayStart = date.atStartOfDay(zone).toInstant();
      Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
      return closure.startsAt().isBefore(dayEnd) && closure.endsAt().isAfter(dayStart);
    } catch (RuntimeException exception) {
      throw new QueryRejectedException("INVALID_REFERENCE_DATE");
    }
  }

  private static Snapshot snapshot(
      List<ServiceCalendar> calendars,
      List<ServiceSchedule> schedules,
      List<AdministrativeClosure> closures,
      List<Cabin> cabins,
      List<Tank> tanks,
      List<ResourcePriority> priorities) {
    return new Snapshot(
        Instant.now(),
        calendars.stream().map(CalendarView::from).toList(),
        schedules.stream().map(ScheduleView::from).toList(),
        closures.stream().map(ClosureView::from).toList(),
        cabins.stream().map(CabinView::from).toList(),
        tanks.stream().map(TankView::from).toList(),
        priorities.stream().map(PriorityView::from).toList());
  }

  public record Snapshot(
      Instant snapshotGeneratedAt,
      List<CalendarView> serviceCalendars,
      List<ScheduleView> serviceSchedules,
      List<ClosureView> administrativeClosures,
      List<CabinView> cabins,
      List<TankView> tanks,
      List<PriorityView> resourcePriorities) {}

  public record CalendarView(
      java.util.UUID calendarId, String code, String name, String timezone,
      LocalDate effectiveFrom, LocalDate effectiveUntil, String status, int version) {
    static CalendarView from(ServiceCalendar value) {
      return new CalendarView(value.id(), value.code().value(), value.name().value(), value.timezone().value(),
          value.effectivePeriod().from(), value.effectivePeriod().until(), value.status().name(), value.version());
    }
  }

  public record ScheduleView(
      java.util.UUID scheduleId, java.util.UUID calendarId, int dayOfWeek,
      java.time.LocalTime openingTime, java.time.LocalTime closingTime,
      int slotDurationMinutes, String status, int version) {
    static ScheduleView from(ServiceSchedule value) {
      return new ScheduleView(value.id(), value.serviceCalendarId(), value.dayOfWeek(), value.timeWindow().opening(),
          value.timeWindow().closing(), value.timeWindow().slotDurationMinutes(), value.status().name(), value.version());
    }
  }

  public record ClosureView(
      java.util.UUID closureId, java.util.UUID calendarId, String scope,
      java.util.UUID cabinId, java.util.UUID tankId, Instant startsAt, Instant endsAt,
      String status, int version) {
    static ClosureView from(AdministrativeClosure value) {
      return new ClosureView(value.id(), value.serviceCalendarId(), value.scope().name(), value.cabinId(), value.tankId(),
          value.startsAt(), value.endsAt(), value.status().name(), value.version());
    }
  }

  public record CabinView(
      java.util.UUID cabinId, String code, String name, String cabinType, String status, int version) {
    static CabinView from(Cabin value) {
      return new CabinView(value.id(), value.code().value(), value.name().value(), value.cabinType().name(), value.status().name(), value.version());
    }
  }

  public record TankView(
      java.util.UUID tankId, java.util.UUID cabinId, String code, String name,
      int configuredCapacity, String status, int version) {
    static TankView from(Tank value) {
      return new TankView(value.id(), value.cabinId(), value.code().value(), value.name().value(), value.configuredCapacity().value(), value.status().name(), value.version());
    }
  }

  public record PriorityView(
      java.util.UUID resourcePriorityId, String appointmentType, String cabinType,
      String usageMode, int priorityOrder, String status, int version) {
    static PriorityView from(ResourcePriority value) {
      return new PriorityView(value.id(), value.appointmentType().name(), value.cabinType().name(), value.usageMode().name(), value.priorityOrder(), value.status().name(), value.version());
    }
  }

  public static final class QueryRejectedException extends IllegalArgumentException {
    public QueryRejectedException(String code) {
      super(code);
    }

    public String code() {
      return getMessage();
    }
  }
}
