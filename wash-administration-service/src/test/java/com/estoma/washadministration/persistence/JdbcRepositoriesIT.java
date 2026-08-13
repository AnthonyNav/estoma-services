package com.estoma.washadministration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washadministration.application.port.AdministrativeClosureRepository;
import com.estoma.washadministration.application.port.AuthorizationAccessProjectionRepository;
import com.estoma.washadministration.application.port.AuthorizationRoleProjectionRepository;
import com.estoma.washadministration.application.port.CabinRepository;
import com.estoma.washadministration.application.port.CommandIdempotencyRepository;
import com.estoma.washadministration.application.port.OutboxRepository;
import com.estoma.washadministration.application.port.ProcessedMessageRepository;
import com.estoma.washadministration.application.port.ResourcePriorityRepository;
import com.estoma.washadministration.application.port.ServiceCalendarRepository;
import com.estoma.washadministration.application.port.ServiceScheduleRepository;
import com.estoma.washadministration.application.port.TankRepository;
import com.estoma.washadministration.domain.calendar.ServiceCalendar;
import com.estoma.washadministration.domain.closure.AdministrativeClosure;
import com.estoma.washadministration.domain.closure.AdministrativeClosureScope;
import com.estoma.washadministration.domain.closure.AdministrativeClosureStatus;
import com.estoma.washadministration.domain.priority.AppointmentType;
import com.estoma.washadministration.domain.priority.ResourcePriority;
import com.estoma.washadministration.domain.priority.UsageMode;
import com.estoma.washadministration.domain.resource.Cabin;
import com.estoma.washadministration.domain.resource.CabinType;
import com.estoma.washadministration.domain.resource.Tank;
import com.estoma.washadministration.domain.schedule.ServiceSchedule;
import com.estoma.washadministration.domain.shared.ClosureReason;
import com.estoma.washadministration.domain.shared.DisplayName;
import com.estoma.washadministration.domain.shared.EffectivePeriod;
import com.estoma.washadministration.domain.shared.PositiveCapacity;
import com.estoma.washadministration.domain.shared.ResourceCode;
import com.estoma.washadministration.domain.shared.TimeWindow;
import com.estoma.washadministration.domain.shared.TimeZoneId;
import com.estoma.washadministration.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcRepositoriesIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private ServiceCalendarRepository calendars;
  @Autowired private ServiceScheduleRepository schedules;
  @Autowired private AdministrativeClosureRepository closures;
  @Autowired private CabinRepository cabins;
  @Autowired private TankRepository tanks;
  @Autowired private ResourcePriorityRepository priorities;
  @Autowired private AuthorizationRoleProjectionRepository roles;
  @Autowired private AuthorizationAccessProjectionRepository accesses;
  @Autowired private ProcessedMessageRepository processedMessages;
  @Autowired private CommandIdempotencyRepository idempotency;
  @Autowired private OutboxRepository outbox;
  @Autowired private JdbcClient jdbc;

  @Test
  void calendarRepositoryRoundTripsAndUsesAtomicOptimisticLocking() {
    ServiceCalendar calendar = calendar();
    calendars.insert(calendar);

    assertThat(calendars.findById(calendar.id()))
        .get()
        .satisfies(found -> {
          assertThat(found.id()).isEqualTo(calendar.id());
          assertThat(found.code()).isEqualTo(calendar.code());
          assertThat(found.name()).isEqualTo(calendar.name());
          assertThat(found.effectivePeriod()).isEqualTo(calendar.effectivePeriod());
          assertThat(found.version()).isEqualTo(1);
        });

    ServiceCalendar updated =
        calendar.withUpdatedDetails(
            new DisplayName("Updated calendar"),
            new TimeZoneId("UTC"),
            calendar.effectivePeriod(),
            ACCOUNT_ID,
            NOW.plusSeconds(1));
    assertThat(calendars.update(updated, calendar.version())).isEqualTo(1);
    assertThat(calendars.update(updated, calendar.version())).isZero();
    assertThat(calendars.findById(calendar.id())).map(ServiceCalendar::version).contains(2);

    ServiceCalendar illegalJump =
        ServiceCalendar.reconstitute(
            updated.id(),
            updated.code(),
            updated.name(),
            updated.timezone(),
            updated.effectivePeriod(),
            updated.status(),
            27,
            updated.createdByAccountId(),
            updated.updatedByAccountId(),
            updated.createdAt(),
            updated.updatedAt().plusSeconds(1));
    assertThatThrownBy(() -> calendars.update(illegalJump, 2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedVersion + 1");
    assertThat(calendars.findById(calendar.id())).map(ServiceCalendar::version).contains(2);
  }

  @Test
  void scheduleRepositoryRoundTripsAndCountsActiveSchedules() {
    ServiceCalendar calendar = calendar();
    calendars.insert(calendar);
    ServiceSchedule schedule =
        ServiceSchedule.createNew(
            UUID.randomUUID(),
            calendar.id(),
            1,
            new TimeWindow(LocalTime.of(8, 0), LocalTime.of(10, 0), 60),
            ACCOUNT_ID,
            NOW);

    schedules.insert(schedule);

    assertThat(schedules.findById(schedule.id()))
        .get()
        .satisfies(found -> {
          assertThat(found.id()).isEqualTo(schedule.id());
          assertThat(found.serviceCalendarId()).isEqualTo(schedule.serviceCalendarId());
          assertThat(found.timeWindow()).isEqualTo(schedule.timeWindow());
          assertThat(found.version()).isEqualTo(1);
        });
    assertThat(schedules.countActiveByCalendarId(calendar.id())).isEqualTo(1);
    assertThat(
            schedules.findActiveOverlapping(
                calendar.id(), 1, LocalTime.of(9, 0), LocalTime.of(11, 0), null))
        .extracting(ServiceSchedule::id)
        .containsExactly(schedule.id());
  }

  @Test
  void cabinAndTankRepositoriesPreserveTankOnlyCapacity() {
    Cabin cabin = cabin();
    cabins.insert(cabin);
    Tank tank =
        Tank.createNew(
            UUID.randomUUID(),
            cabin.id(),
            new ResourceCode("T-1"),
            new DisplayName("Tank 1"),
            new PositiveCapacity(3),
            ACCOUNT_ID,
            NOW);
    tanks.insert(tank);

    assertThat(cabins.findByCode(cabin.code().value()))
        .get()
        .satisfies(found -> {
          assertThat(found.id()).isEqualTo(cabin.id());
          assertThat(found.code()).isEqualTo(cabin.code());
          assertThat(found.name()).isEqualTo(cabin.name());
          assertThat(found.cabinType()).isEqualTo(cabin.cabinType());
        });
    assertThat(tanks.findByCabinIdAndCode(cabin.id(), tank.code().value()))
        .get()
        .satisfies(found -> {
          assertThat(found.id()).isEqualTo(tank.id());
          assertThat(found.cabinId()).isEqualTo(tank.cabinId());
          assertThat(found.code()).isEqualTo(tank.code());
          assertThat(found.name()).isEqualTo(tank.name());
          assertThat(found.configuredCapacity()).isEqualTo(tank.configuredCapacity());
        });
    assertThat(tanks.countByCabinId(cabin.id())).isEqualTo(1);
    assertThat(tanks.countNonRetiredByCabinId(cabin.id())).isEqualTo(1);
    assertThat(tanks.findById(tank.id()))
        .map(Tank::configuredCapacity)
        .map(PositiveCapacity::value)
        .contains(3);
  }

  @Test
  void closureRepositoryRoundTripsCancellation() {
    ServiceCalendar calendar = calendar();
    calendars.insert(calendar);
    AdministrativeClosure closure =
        AdministrativeClosure.schedule(
            UUID.randomUUID(),
            calendar.id(),
            AdministrativeClosureScope.SERVICE,
            null,
            null,
            Instant.parse("2026-02-01T09:00:00Z"),
            Instant.parse("2026-02-01T10:00:00Z"),
            new ClosureReason("Maintenance"),
            calendar.effectivePeriod(),
            java.time.ZoneId.of("UTC"),
            ACCOUNT_ID,
            NOW);
    closures.insert(closure);

    AdministrativeClosure cancelled =
        closure.cancel(ACCOUNT_ID, NOW.plusSeconds(1), new ClosureReason("No longer needed"));
    assertThat(closures.update(cancelled, closure.version())).isEqualTo(1);
    assertThat(closures.findById(closure.id()))
        .get()
        .satisfies(found -> {
          assertThat(found.status()).isEqualTo(AdministrativeClosureStatus.CANCELLED);
          assertThat(found.cancellationReason()).isEqualTo(new ClosureReason("No longer needed"));
        });
  }

  @Test
  void priorityProjectionInboxIdempotencyAndOutboxAdaptersPersistTechnicalState() {
    ResourcePriority priority =
        ResourcePriority.createNew(
            UUID.randomUUID(),
            AppointmentType.NORMAL,
            CabinType.NORMAL,
            UsageMode.PREFERRED,
            1,
            NOW);
    priorities.insert(priority);
    assertThat(priorities.findByTypePair(AppointmentType.NORMAL, CabinType.NORMAL))
        .get()
        .satisfies(found -> {
          assertThat(found.id()).isEqualTo(priority.id());
          assertThat(found.usageMode()).isEqualTo(priority.usageMode());
          assertThat(found.priorityOrder()).isEqualTo(priority.priorityOrder());
          assertThat(found.version()).isEqualTo(1);
        });

    UUID accountId = UUID.randomUUID();
    assertThat(roles.upsert(accountId, "ADMINISTRADOR_LAVADO", 2, NOW)).isEqualTo(1);
    assertThat(roles.upsert(accountId, "SUPERADMIN", 1, NOW.plusSeconds(1))).isZero();
    assertThat(roles.findByAccountId(accountId))
        .map(AuthorizationRoleProjectionRepository.RoleProjection::roleCode)
        .contains("ADMINISTRADOR_LAVADO");
    assertThat(accesses.upsert(accountId, "LAVADO_ULTRASONICO", "ACTIVE", 3, NOW)).isEqualTo(1);
    assertThat(accesses.findByAccountId(accountId, "LAVADO_ULTRASONICO"))
        .map(AuthorizationAccessProjectionRepository.AccessProjection::accessStatus)
        .contains("ACTIVE");

    UUID messageId = UUID.randomUUID();
    assertThat(processedMessages.registerIfAbsent("authorization-events", messageId, UUID.randomUUID(), NOW))
        .isTrue();
    assertThat(processedMessages.registerIfAbsent("authorization-events", messageId, UUID.randomUUID(), NOW))
        .isFalse();

    assertThat(idempotency.insertIfAbsent("bff", "CreateServiceCalendar", "key-1", "a".repeat(64), NOW))
        .isTrue();
    assertThat(idempotency.insertIfAbsent("bff", "CreateServiceCalendar", "key-1", "b".repeat(64), NOW))
        .isFalse();
    assertThat(
            idempotency.markSucceeded(
                "bff",
                "CreateServiceCalendar",
                "key-1",
                priority.id(),
                1,
                "SUCCEEDED",
                "{}",
                NOW.plusSeconds(1)))
        .isEqualTo(1);
    assertThat(idempotency.find("bff", "CreateServiceCalendar", "key-1"))
        .map(CommandIdempotencyRepository.IdempotencyRecord::status)
        .contains("SUCCEEDED");

    assertThat(
            idempotency.markFailed(
                "bff",
                "CreateServiceCalendar",
                "key-1",
                "LATE_FAILURE",
                "FAILED",
                "{}",
                NOW.plusSeconds(2)))
        .isZero();
    assertThat(idempotency.find("bff", "CreateServiceCalendar", "key-1"))
        .map(CommandIdempotencyRepository.IdempotencyRecord::status)
        .contains("SUCCEEDED");

    assertThat(idempotency.insertIfAbsent("bff", "UpdateServiceCalendar", "key-failed", "c".repeat(64), NOW))
        .isTrue();
    assertThat(
            idempotency.markFailed(
                "bff",
                "UpdateServiceCalendar",
                "key-failed",
                "TECHNICAL_FAILURE",
                "FAILED",
                "{}",
                NOW.plusSeconds(2)))
        .isEqualTo(1);
    assertThat(idempotency.find("bff", "UpdateServiceCalendar", "key-failed"))
        .map(CommandIdempotencyRepository.IdempotencyRecord::status)
        .contains("FAILED");

    assertThat(idempotency.insertIfAbsent("bff", "ActivateServiceCalendar", "key-expired", "d".repeat(64), NOW))
        .isTrue();
    assertThat(
            idempotency.markExpired(
                "bff",
                "ActivateServiceCalendar",
                "key-expired",
                "COMMAND_EXPIRED",
                "EXPIRED",
                "{}",
                NOW.plusSeconds(2)))
        .isEqualTo(1);
    assertThat(idempotency.find("bff", "ActivateServiceCalendar", "key-expired"))
        .map(CommandIdempotencyRepository.IdempotencyRecord::status)
        .contains("EXPIRED");

    UUID correlationId = UUID.randomUUID();
    outbox.append(
        new OutboxRepository.OutboxMessage(
            UUID.randomUUID(),
            "ServiceCalendarCreated",
            1,
            "EVENT",
            "wash-administration",
            "wash-administration.calendar.created",
            NOW,
            null,
            null,
            priority.id(),
            1,
            correlationId,
            null,
            "{\"type\":\"SYSTEM\",\"service\":\"wash-administration\"}",
            "{}",
            null));
    assertThat(
            jdbc.sql("SELECT count(*) FROM outbox_messages WHERE correlation_id = :correlationId")
                .param("correlationId", correlationId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  private static ServiceCalendar calendar() {
    return ServiceCalendar.createNew(
        UUID.randomUUID(),
        new ResourceCode("CAL-" + UUID.randomUUID()),
        new DisplayName("Calendar"),
        new TimeZoneId("UTC"),
        new EffectivePeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
        ACCOUNT_ID,
        NOW);
  }

  private static Cabin cabin() {
    return Cabin.createNew(
        UUID.randomUUID(),
        new ResourceCode("CAB-" + UUID.randomUUID()),
        new DisplayName("Cabin"),
        CabinType.NORMAL,
        ACCOUNT_ID,
        NOW);
  }
}
