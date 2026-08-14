package com.estoma.washadministration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washadministration.application.authorization.AuthorizationProjectionUpdater;
import com.estoma.washadministration.application.command.ActorContext;
import com.estoma.washadministration.application.command.CommandMetadata;
import com.estoma.washadministration.application.command.CommandRequest;
import com.estoma.washadministration.application.command.CommandOutcome;
import com.estoma.washadministration.application.command.WashAdministrationCommandDispatcher;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent;
import com.estoma.washadministration.application.priority.ResourcePriorityConfigurator;
import com.estoma.washadministration.application.query.GetWashAdministrationConfiguration;
import com.estoma.washadministration.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class WashAdministrationApplicationIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private WashAdministrationCommandDispatcher dispatcher;
  @Autowired private AuthorizationProjectionUpdater projectionUpdater;
  @Autowired private ResourcePriorityConfigurator priorities;
  @Autowired private GetWashAdministrationConfiguration query;

  private UUID admin;

  @BeforeEach
  void seedAuthorization() {
    admin = UUID.randomUUID();
    projectionUpdater.apply(new AuthorizationProjectionEvent(
        UUID.randomUUID(), "AccountRoleAssigned", admin, "ADMINISTRADOR_LAVADO", null, null, 1, NOW));
    projectionUpdater.apply(new AuthorizationProjectionEvent(
        UUID.randomUUID(), "SystemAccessGranted", admin, null, "LAVADO_ULTRASONICO", "ACTIVE", 1, NOW));
  }

  @Test
  void createCalendarScheduleActivateAndQuerySnapshotAreTransactional() {
    UUID calendarId = UUID.randomUUID();
    UUID scheduleId = UUID.randomUUID();
    String key = UUID.randomUUID().toString();

    CommandOutcome created = dispatch(
        "CreateServiceCalendar",
        key,
        object(
            "calendarId", calendarId,
            "code", "CAL-1",
            "name", "Calendar 1",
            "timezone", "America/Mexico_City",
            "effectiveFrom", "2026-01-01",
            "effectiveUntil", "2026-12-31"));
    assertThat(created.status()).isEqualTo("SUCCEEDED");

    assertThat(dispatch(
        "ConfigureServiceSchedule",
        UUID.randomUUID().toString(),
        object(
            "scheduleId", scheduleId,
            "calendarId", calendarId,
            "dayOfWeek", 1,
            "openingTime", "08:00",
            "closingTime", "12:00",
            "slotDurationMinutes", 60)).status()).isEqualTo("SUCCEEDED");

    assertThat(dispatch(
        "ActivateServiceCalendar",
        UUID.randomUUID().toString(),
        object("calendarId", calendarId, "expectedVersion", 1)).status()).isEqualTo("SUCCEEDED");

    GetWashAdministrationConfiguration.Snapshot snapshot = query.execute("appointments");
    assertThat(snapshot.serviceCalendars()).extracting(GetWashAdministrationConfiguration.CalendarView::calendarId)
        .contains(calendarId);
    assertThat(snapshot.serviceSchedules()).extracting(GetWashAdministrationConfiguration.ScheduleView::scheduleId)
        .contains(scheduleId);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'EVENT'")
        .query(Long.class).single()).isEqualTo(3);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'RESULT'")
        .query(Long.class).single()).isEqualTo(3);
  }

  @Test
  void allNineteenAdministrativeCommandsHaveAWorkingApplicationPath() {
    UUID calendarId = UUID.randomUUID();
    UUID scheduleId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID tankId = UUID.randomUUID();
    UUID closureId = UUID.randomUUID();

    assertSucceeded("CreateServiceCalendar", object(
        "calendarId", calendarId, "code", "ALL-COMMANDS", "name", "All commands",
        "timezone", "UTC", "effectiveFrom", "2026-01-01", "effectiveUntil", "2026-12-31"));
    assertSucceeded("UpdateServiceCalendar", object(
        "calendarId", calendarId, "name", "All commands updated", "timezone", "UTC",
        "effectiveFrom", "2026-01-01", "effectiveUntil", "2026-12-31", "expectedVersion", 1));
    assertSucceeded("ConfigureServiceSchedule", object(
        "scheduleId", scheduleId, "calendarId", calendarId, "dayOfWeek", 1,
        "openingTime", "08:00", "closingTime", "12:00", "slotDurationMinutes", 60));
    assertSucceeded("ActivateServiceCalendar", object("calendarId", calendarId, "expectedVersion", 2));
    assertSucceeded("DeactivateServiceCalendar", object(
        "calendarId", calendarId, "reason", "temporary", "expectedVersion", 3));
    assertSucceeded("UpdateServiceSchedule", object(
        "scheduleId", scheduleId, "dayOfWeek", 1, "openingTime", "09:00",
        "closingTime", "13:00", "slotDurationMinutes", 60, "expectedVersion", 1));
    assertSucceeded("DeactivateServiceSchedule", object(
        "scheduleId", scheduleId, "reason", "calendar inactive", "expectedVersion", 2));

    assertSucceeded("RegisterCabin", object(
        "cabinId", cabinId, "code", "ALL-CABIN", "name", "All cabin", "cabinType", "NORMAL"));
    assertSucceeded("UpdateCabin", object(
        "cabinId", cabinId, "name", "All cabin updated", "cabinType", "NORMAL", "expectedVersion", 1));
    assertSucceeded("DeactivateCabin", object(
        "cabinId", cabinId, "reason", "maintenance", "expectedVersion", 2));
    assertSucceeded("ActivateCabin", object("cabinId", cabinId, "expectedVersion", 3));

    assertSucceeded("RegisterTank", object(
        "tankId", tankId, "cabinId", cabinId, "code", "ALL-TANK", "name", "All tank", "configuredCapacity", 3));
    assertSucceeded("UpdateTank", object(
        "tankId", tankId, "name", "All tank updated", "configuredCapacity", 4, "expectedVersion", 1));
    assertSucceeded("DeactivateTank", object(
        "tankId", tankId, "reason", "maintenance", "expectedVersion", 2));
    assertSucceeded("ActivateTank", object("tankId", tankId, "expectedVersion", 3));

    assertSucceeded("ScheduleAdministrativeClosure", object(
        "closureId", closureId, "calendarId", calendarId, "scope", "CABIN", "cabinId", cabinId,
        "startsAt", "2026-05-01T09:00:00Z", "endsAt", "2026-05-01T10:00:00Z", "reason", "inspection"));
    assertSucceeded("CancelAdministrativeClosure", object(
        "closureId", closureId, "reason", "rescheduled", "expectedVersion", 1));

    assertSucceeded("RetireTank", object("tankId", tankId, "reason", "decommissioned", "expectedVersion", 4));
    assertSucceeded("RetireCabin", object("cabinId", cabinId, "reason", "decommissioned", "expectedVersion", 4));

    assertThat(jdbc.sql("SELECT count(*) FROM administrative_audit_records")
        .query(Long.class).single()).isEqualTo(8);
    assertThat(jdbc.sql("SELECT reason FROM administrative_audit_records WHERE operation_type = 'RetireTank'")
        .query(String.class).single()).isEqualTo("decommissioned");
  }

  @Test
  void calendarUpdateCannotLeaveAnExistingClosureOutsideItsValidity() {
    UUID calendarId = UUID.randomUUID();
    UUID closureId = UUID.randomUUID();

    assertSucceeded("CreateServiceCalendar", object(
        "calendarId", calendarId, "code", "CLOSURE-CALENDAR", "name", "Closure calendar",
        "timezone", "UTC", "effectiveFrom", "2026-01-01", "effectiveUntil", "2026-12-31"));
    assertSucceeded("ScheduleAdministrativeClosure", object(
        "closureId", closureId, "calendarId", calendarId, "scope", "SERVICE",
        "startsAt", "2026-05-01T09:00:00Z", "endsAt", "2026-05-01T10:00:00Z", "reason", "inspection"));

    CommandOutcome rejected = dispatch("UpdateServiceCalendar", UUID.randomUUID().toString(), object(
        "calendarId", calendarId, "name", "Closure calendar", "timezone", "UTC",
        "effectiveFrom", "2026-01-01", "effectiveUntil", "2026-01-31", "expectedVersion", 1));

    assertThat(rejected.status()).isEqualTo("REJECTED");
    assertThat(rejected.errorCode()).isEqualTo("ADMINISTRATIVE_CLOSURE_INVALID_PERIOD");
    assertThat(jdbc.sql("SELECT effective_until FROM service_calendars WHERE service_calendar_id = :id")
        .param("id", calendarId).query(LocalDate.class).single()).isEqualTo(LocalDate.of(2026, 12, 31));
  }

  @Test
  void malformedExpectedVersionIsRejectedInsteadOfBeingCoerced() {
    UUID calendarId = UUID.randomUUID();
    assertSucceeded("CreateServiceCalendar", object(
        "calendarId", calendarId, "code", "STRICT-VERSION", "name", "Strict version",
        "timezone", "UTC", "effectiveFrom", "2026-01-01"));

    CommandOutcome rejected = dispatch("UpdateServiceCalendar", UUID.randomUUID().toString(), object(
        "calendarId", calendarId, "name", "Strict version", "timezone", "UTC",
        "effectiveFrom", "2026-01-01", "expectedVersion", 1.9));

    assertThat(rejected.status()).isEqualTo("REJECTED");
    assertThat(rejected.errorCode()).isEqualTo("INVALID_MESSAGE");
  }

  @Test
  void sameFunctionalIntentDoesNotRepeatMutationOrEvent() {
    UUID calendarId = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    var payload = object(
        "calendarId", calendarId,
        "code", "IDEMPOTENT",
        "name", "Idempotent",
        "timezone", "UTC",
        "effectiveFrom", "2026-01-01");

    CommandOutcome first = dispatch("CreateServiceCalendar", key, payload);
    CommandOutcome second = dispatch("CreateServiceCalendar", key, object(
        "calendarId", calendarId,
        "code", " IDEMPOTENT ",
        "name", " Idempotent ",
        "timezone", " UTC ",
        "effectiveFrom", "2026-01-01",
        "effectiveUntil", null));

    assertThat(first.status()).isEqualTo("SUCCEEDED");
    assertThat(second.status()).isEqualTo("SUCCEEDED");
    assertThat(jdbc.sql("SELECT count(*) FROM service_calendars WHERE service_calendar_id = :id")
        .param("id", calendarId).query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'EVENT'")
        .query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'RESULT'")
        .query(Long.class).single()).isEqualTo(2);
  }

  @Test
  void differentFingerprintIsRejectedAndAuthorizationFailsClosed() {
    String key = UUID.randomUUID().toString();
    dispatch("CreateServiceCalendar", key, object(
        "calendarId", UUID.randomUUID(), "code", "FINGERPRINT", "name", "One", "timezone", "UTC", "effectiveFrom", "2026-01-01"));
    CommandOutcome conflict = dispatch("CreateServiceCalendar", key, object(
        "calendarId", UUID.randomUUID(), "code", "FINGERPRINT-2", "name", "Two", "timezone", "UTC", "effectiveFrom", "2026-01-01"));
    assertThat(conflict.status()).isEqualTo("REJECTED");
    assertThat(conflict.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST");

    jdbc.sql("DELETE FROM authorization_access_projection WHERE account_id = :accountId")
        .param("accountId", admin).update();
    CommandOutcome unauthorized = dispatch("CreateServiceCalendar", UUID.randomUUID().toString(), object(
        "calendarId", UUID.randomUUID(), "code", "DENIED", "name", "Denied", "timezone", "UTC", "effectiveFrom", "2026-01-01"));
    assertThat(unauthorized.status()).isEqualTo("REJECTED");
    assertThat(unauthorized.errorCode()).isEqualTo("ACTOR_NOT_AUTHORIZED");
  }

  @Test
  void bootstrapCreatesFivePrioritiesOnlyOnceEvenWhenInvokedConcurrently() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> {
        start.await();
        return priorities.configureAll(NOW);
      });
      var second = executor.submit(() -> {
        start.await();
        return priorities.configureAll(NOW);
      });
      start.countDown();
      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
    }
    assertThat(jdbc.sql("SELECT count(*) FROM resource_priorities").query(Long.class).single()).isEqualTo(5);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type = 'ResourcePriorityConfigured'")
        .query(Long.class).single()).isEqualTo(5);
  }

  @Test
  void authorizationProjectionIsMonotonicAndIgnoresOtherSystems() {
    UUID accountId = UUID.randomUUID();
    assertThat(projectionUpdater.apply(new AuthorizationProjectionEvent(
        UUID.randomUUID(), "SystemAccessGranted", accountId, null, "PRACTICAS_PROFESIONALES", "ACTIVE", 20, NOW)))
        .isFalse();
    assertThat(projectionUpdater.apply(new AuthorizationProjectionEvent(
        UUID.randomUUID(), "SystemAccessGranted", accountId, null, "LAVADO_ULTRASONICO", "ACTIVE", 2, NOW)))
        .isTrue();
    assertThat(projectionUpdater.apply(new AuthorizationProjectionEvent(
        UUID.randomUUID(), "SystemAccessSuspended", accountId, null, "LAVADO_ULTRASONICO", "SUSPENDED", 1, NOW)))
        .isTrue();
    assertThat(jdbc.sql("SELECT access_status::text FROM authorization_access_projection WHERE account_id = :id")
        .param("id", accountId).query(String.class).single()).isEqualTo("ACTIVE");
  }

  private CommandOutcome dispatch(String operation, String key, tools.jackson.databind.JsonNode payload) {
    UUID messageId = UUID.randomUUID();
    return dispatcher.dispatch(new CommandRequest(
        new CommandMetadata(
            messageId,
            operation,
            1,
            "bff",
            NOW,
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            key,
            null,
            null,
            ActorContext.account(admin)),
        payload));
  }

  private void assertSucceeded(String operation, tools.jackson.databind.JsonNode payload) {
    assertThat(dispatch(operation, UUID.randomUUID().toString(), payload).status())
        .as(operation)
        .isEqualTo("SUCCEEDED");
  }

  private tools.jackson.databind.JsonNode object(Object... values) {
    var node = mapper.createObjectNode();
    for (int index = 0; index < values.length; index += 2) {
      node.set((String) values[index], mapper.valueToTree(values[index + 1]));
    }
    return node;
  }
}
