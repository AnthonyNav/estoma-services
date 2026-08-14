package com.estoma.washadministration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washadministration.application.authorization.AuthorizationProjectionUpdater;
import com.estoma.washadministration.application.command.ActorContext;
import com.estoma.washadministration.application.command.AdministrativeCommand;
import com.estoma.washadministration.application.command.CommandMetadata;
import com.estoma.washadministration.application.command.CommandOutcome;
import com.estoma.washadministration.application.command.CommandOutcome.Status;
import com.estoma.washadministration.application.command.CommandRequest;
import com.estoma.washadministration.application.command.WashAdministrationCommandDispatcher;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent.RoleAssigned;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent.RoleChanged;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent.SystemAccessGranted;
import com.estoma.washadministration.application.port.AuthorizationProjectionEvent.SystemAccessSuspended;
import com.estoma.washadministration.application.priority.ResourcePriorityConfigurator;
import com.estoma.washadministration.application.query.GetWashAdministrationConfiguration;
import com.estoma.washadministration.domain.closure.AdministrativeClosureScope;
import com.estoma.washadministration.domain.resource.CabinType;
import com.estoma.washadministration.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class WashAdministrationApplicationIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private JdbcClient jdbc;
  @Autowired private WashAdministrationCommandDispatcher dispatcher;
  @Autowired private AuthorizationProjectionUpdater projectionUpdater;
  @Autowired private ResourcePriorityConfigurator priorities;
  @Autowired private GetWashAdministrationConfiguration query;

  private UUID admin;

  @BeforeEach
  void seedAuthorization() {
    admin = UUID.randomUUID();
    projectionUpdater.apply(
        new RoleAssigned(
            UUID.randomUUID(), admin, "ADMINISTRADOR_LAVADO", 1, NOW));
    projectionUpdater.apply(
        new SystemAccessGranted(
            UUID.randomUUID(), admin, "LAVADO_ULTRASONICO", 1, NOW));
  }

  @Test
  void createCalendarScheduleActivateAndQuerySnapshotAreTransactional() {
    UUID calendarId = UUID.randomUUID();
    UUID scheduleId = UUID.randomUUID();

    CommandOutcome created =
        dispatch(
            new AdministrativeCommand.CreateServiceCalendar(
                calendarId,
                "CAL-1",
                "Calendar 1",
                "America/Mexico_City",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31)));
    assertThat(created.status()).isEqualTo(Status.SUCCEEDED);

    assertThat(
            dispatch(
                    new AdministrativeCommand.ConfigureServiceSchedule(
                        scheduleId,
                        calendarId,
                        1,
                        LocalTime.of(8, 0),
                        LocalTime.of(12, 0),
                        60))
                .status())
        .isEqualTo(Status.SUCCEEDED);

    assertThat(
            dispatch(
                    new AdministrativeCommand.ActivateServiceCalendar(
                        calendarId, 1))
                .status())
        .isEqualTo(Status.SUCCEEDED);

    GetWashAdministrationConfiguration.Snapshot snapshot =
        query.execute("appointments");
    assertThat(snapshot.serviceCalendars())
        .extracting(GetWashAdministrationConfiguration.CalendarView::calendarId)
        .contains(calendarId);
    assertThat(snapshot.serviceSchedules())
        .extracting(GetWashAdministrationConfiguration.ScheduleView::scheduleId)
        .contains(scheduleId);
    assertThat(
            jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'EVENT'")
                .query(Long.class)
                .single())
        .isEqualTo(3);
    assertThat(
            jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'RESULT'")
                .query(Long.class)
                .single())
        .isEqualTo(3);
    assertThat(
            jdbc.sql("SELECT routing_key FROM outbox_messages WHERE message_type = 'ServiceCalendarCreated'")
                .query(String.class)
                .single())
        .isEqualTo("ServiceCalendarCreated");
    assertThat(
            jdbc.sql("SELECT routing_key FROM outbox_messages WHERE message_type = 'CreateServiceCalendarResult'")
                .query(String.class)
                .single())
        .isEqualTo("bff");
  }

  @Test
  void allNineteenAdministrativeCommandsHaveAWorkingApplicationPath() {
    UUID calendarId = UUID.randomUUID();
    UUID scheduleId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID tankId = UUID.randomUUID();
    UUID closureId = UUID.randomUUID();

    assertSucceeded(
        new AdministrativeCommand.CreateServiceCalendar(
            calendarId, "ALL-COMMANDS", "All commands", "UTC",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
    assertSucceeded(
        new AdministrativeCommand.UpdateServiceCalendar(
            calendarId, "All commands updated", "UTC",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 1));
    assertSucceeded(
        new AdministrativeCommand.ConfigureServiceSchedule(
            scheduleId, calendarId, 1, LocalTime.of(8, 0), LocalTime.of(12, 0), 60));
    assertSucceeded(new AdministrativeCommand.ActivateServiceCalendar(calendarId, 2));
    assertSucceeded(new AdministrativeCommand.DeactivateServiceCalendar(calendarId, "temporary", 3));
    assertSucceeded(
        new AdministrativeCommand.UpdateServiceSchedule(
            scheduleId, 1, LocalTime.of(9, 0), LocalTime.of(13, 0), 60, 1));
    assertSucceeded(new AdministrativeCommand.DeactivateServiceSchedule(scheduleId, "calendar inactive", 2));

    assertSucceeded(new AdministrativeCommand.RegisterCabin(cabinId, "ALL-CABIN", "All cabin", CabinType.NORMAL));
    assertSucceeded(new AdministrativeCommand.UpdateCabin(cabinId, "All cabin updated", CabinType.NORMAL, 1));
    assertSucceeded(new AdministrativeCommand.DeactivateCabin(cabinId, "maintenance", 2));
    assertSucceeded(new AdministrativeCommand.ActivateCabin(cabinId, 3));

    assertSucceeded(new AdministrativeCommand.RegisterTank(tankId, cabinId, "ALL-TANK", "All tank", 3));
    assertSucceeded(new AdministrativeCommand.UpdateTank(tankId, "All tank updated", 4, 1));
    assertSucceeded(new AdministrativeCommand.DeactivateTank(tankId, "maintenance", 2));
    assertSucceeded(new AdministrativeCommand.ActivateTank(tankId, 3));

    assertSucceeded(
        new AdministrativeCommand.ScheduleAdministrativeClosure(
            closureId, calendarId, AdministrativeClosureScope.CABIN, cabinId, null,
            Instant.parse("2026-05-01T09:00:00Z"), Instant.parse("2026-05-01T10:00:00Z"), "inspection"));
    assertSucceeded(new AdministrativeCommand.CancelAdministrativeClosure(closureId, "rescheduled", 1));

    assertSucceeded(new AdministrativeCommand.RetireTank(tankId, "decommissioned", 4));
    assertSucceeded(new AdministrativeCommand.RetireCabin(cabinId, "decommissioned", 4));

    assertThat(jdbc.sql("SELECT count(*) FROM administrative_audit_records").query(Long.class).single())
        .isEqualTo(8);
    assertThat(jdbc.sql("SELECT reason FROM administrative_audit_records WHERE operation_type = 'RetireTank'")
        .query(String.class).single()).isEqualTo("decommissioned");
  }

  @Test
  void allNineteenAdministrativeCommandsHaveARejectionPath() {
    UUID unauthorized = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    List<AdministrativeCommand> commands = List.of(
        new AdministrativeCommand.CreateServiceCalendar(first, "REJECT-CALENDAR", "Rejected", "UTC", LocalDate.of(2026, 1, 1), null),
        new AdministrativeCommand.UpdateServiceCalendar(first, "Rejected", "UTC", LocalDate.of(2026, 1, 1), null, 1),
        new AdministrativeCommand.ActivateServiceCalendar(first, 1),
        new AdministrativeCommand.DeactivateServiceCalendar(first, "reason", 1),
        new AdministrativeCommand.ConfigureServiceSchedule(second, first, 1, LocalTime.of(8, 0), LocalTime.of(9, 0), 60),
        new AdministrativeCommand.UpdateServiceSchedule(second, 1, LocalTime.of(8, 0), LocalTime.of(9, 0), 60, 1),
        new AdministrativeCommand.DeactivateServiceSchedule(second, "reason", 1),
        new AdministrativeCommand.ScheduleAdministrativeClosure(second, first, AdministrativeClosureScope.SERVICE, null, null, Instant.parse("2026-02-01T08:00:00Z"), Instant.parse("2026-02-01T09:00:00Z"), "reason"),
        new AdministrativeCommand.CancelAdministrativeClosure(second, "reason", 1),
        new AdministrativeCommand.RegisterCabin(first, "REJECT-CABIN", "Rejected", CabinType.NORMAL),
        new AdministrativeCommand.UpdateCabin(first, "Rejected", CabinType.NORMAL, 1),
        new AdministrativeCommand.ActivateCabin(first, 1),
        new AdministrativeCommand.DeactivateCabin(first, "reason", 1),
        new AdministrativeCommand.RetireCabin(first, "reason", 1),
        new AdministrativeCommand.RegisterTank(second, first, "REJECT-TANK", "Rejected", 1),
        new AdministrativeCommand.UpdateTank(second, "Rejected", 1, 1),
        new AdministrativeCommand.ActivateTank(second, 1),
        new AdministrativeCommand.DeactivateTank(second, "reason", 1),
        new AdministrativeCommand.RetireTank(second, "reason", 1));

    assertThat(commands).hasSize(19);
    for (AdministrativeCommand command : commands) {
      CommandOutcome outcome = dispatchAs(unauthorized, UUID.randomUUID(), UUID.randomUUID().toString(), command);
      assertThat(outcome.status()).as(command.operationType()).isEqualTo(Status.REJECTED);
      assertThat(outcome.errorCode()).as(command.operationType()).isEqualTo("ACTOR_NOT_AUTHORIZED");
    }
  }

  @Test
  void calendarUpdateCannotLeaveAnExistingClosureOutsideItsValidity() {
    UUID calendarId = UUID.randomUUID();
    UUID closureId = UUID.randomUUID();
    assertSucceeded(new AdministrativeCommand.CreateServiceCalendar(calendarId, "CLOSURE-CALENDAR", "Closure calendar", "UTC", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
    assertSucceeded(new AdministrativeCommand.ScheduleAdministrativeClosure(closureId, calendarId, AdministrativeClosureScope.SERVICE, null, null, Instant.parse("2026-05-01T09:00:00Z"), Instant.parse("2026-05-01T10:00:00Z"), "inspection"));

    CommandOutcome rejected = dispatch(new AdministrativeCommand.UpdateServiceCalendar(calendarId, "Closure calendar", "UTC", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 1));
    assertThat(rejected.status()).isEqualTo(Status.REJECTED);
    assertThat(rejected.errorCode()).isEqualTo("ADMINISTRATIVE_CLOSURE_INVALID_PERIOD");
    assertThat(jdbc.sql("SELECT effective_until FROM service_calendars WHERE service_calendar_id = :id")
        .param("id", calendarId).query(LocalDate.class).single()).isEqualTo(LocalDate.of(2026, 12, 31));
  }

  @Test
  void wrongExpectedVersionIsRejectedWithoutMutation() {
    UUID calendarId = UUID.randomUUID();
    assertSucceeded(new AdministrativeCommand.CreateServiceCalendar(calendarId, "STRICT-VERSION", "Strict version", "UTC", LocalDate.of(2026, 1, 1), null));
    CommandOutcome rejected = dispatch(new AdministrativeCommand.UpdateServiceCalendar(calendarId, "Strict version updated", "UTC", LocalDate.of(2026, 1, 1), null, 99));
    assertThat(rejected.status()).isEqualTo(Status.REJECTED);
    assertThat(rejected.errorCode()).isEqualTo("VERSION_CONFLICT");
    assertThat(jdbc.sql("SELECT version FROM service_calendars WHERE service_calendar_id = :id")
        .param("id", calendarId).query(Integer.class).single()).isEqualTo(1);
  }

  @Test
  void sameFunctionalIntentDoesNotRepeatMutationOrEvent() {
    UUID calendarId = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    CommandOutcome first = dispatch(key, new AdministrativeCommand.CreateServiceCalendar(calendarId, "IDEMPOTENT", "Idempotent", "UTC", LocalDate.of(2026, 1, 1), null));
    CommandOutcome second = dispatch(key, new AdministrativeCommand.CreateServiceCalendar(calendarId, " IDEMPOTENT ", " Idempotent ", " UTC ", LocalDate.of(2026, 1, 1), null));
    assertThat(first.status()).isEqualTo(Status.SUCCEEDED);
    assertThat(second.status()).isEqualTo(Status.SUCCEEDED);
    assertThat(jdbc.sql("SELECT count(*) FROM service_calendars WHERE service_calendar_id = :id").param("id", calendarId).query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'EVENT'").query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'RESULT'").query(Long.class).single()).isEqualTo(2);
  }

  @Test
  void sameMessageIdDoesNotRepeatMutationResultOrEvent() {
    UUID calendarId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    AdministrativeCommand command = new AdministrativeCommand.CreateServiceCalendar(calendarId, "DELIVERY-IDEMPOTENT", "Delivery idempotent", "UTC", LocalDate.of(2026, 1, 1), null);
    CommandRequest request = request(admin, messageId, key, command);

    CommandOutcome first = dispatcher.dispatch(request);
    CommandOutcome duplicate = dispatcher.dispatch(request);
    assertThat(first.status()).isEqualTo(Status.SUCCEEDED);
    assertThat(duplicate.status()).isEqualTo(Status.DUPLICATE);
    assertThat(duplicate.duplicateDelivery()).isTrue();
    assertThat(jdbc.sql("SELECT count(*) FROM service_calendars WHERE service_calendar_id = :id").param("id", calendarId).query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'EVENT'").query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'RESULT'").query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void differentFingerprintIsRejectedAndAuthorizationFailsClosed() {
    String key = UUID.randomUUID().toString();
    dispatch(key, new AdministrativeCommand.CreateServiceCalendar(UUID.randomUUID(), "FINGERPRINT", "One", "UTC", LocalDate.of(2026, 1, 1), null));
    CommandOutcome conflict = dispatch(key, new AdministrativeCommand.CreateServiceCalendar(UUID.randomUUID(), "FINGERPRINT-2", "Two", "UTC", LocalDate.of(2026, 1, 1), null));
    assertThat(conflict.status()).isEqualTo(Status.REJECTED);
    assertThat(conflict.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST");

    jdbc.sql("DELETE FROM authorization_access_projection WHERE account_id = :accountId").param("accountId", admin).update();
    CommandOutcome unauthorized = dispatch(new AdministrativeCommand.CreateServiceCalendar(UUID.randomUUID(), "DENIED", "Denied", "UTC", LocalDate.of(2026, 1, 1), null));
    assertThat(unauthorized.status()).isEqualTo(Status.REJECTED);
    assertThat(unauthorized.errorCode()).isEqualTo("ACTOR_NOT_AUTHORIZED");
  }

  @Test
  void technicalOutboxFailureRollsBackMutationInboxAndIdempotency() {
    UUID cabinId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    CommandRequest request = request(admin, messageId, key, new AdministrativeCommand.RegisterCabin(cabinId, "ROLLBACK-CABIN", "Rollback cabin", CabinType.NORMAL));

    jdbc.sql("""
        CREATE OR REPLACE FUNCTION fail_wash_admin_outbox_insert()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'forced outbox failure';
        END;
        $$
        """).update();
    jdbc.sql("""
        CREATE TRIGGER trg_fail_wash_admin_outbox_insert
        BEFORE INSERT ON outbox_messages
        FOR EACH ROW EXECUTE FUNCTION fail_wash_admin_outbox_insert()
        """).update();
    try {
      assertThatThrownBy(() -> dispatcher.dispatch(request)).isInstanceOf(RuntimeException.class);
    } finally {
      jdbc.sql("DROP TRIGGER IF EXISTS trg_fail_wash_admin_outbox_insert ON outbox_messages").update();
      jdbc.sql("DROP FUNCTION IF EXISTS fail_wash_admin_outbox_insert()").update();
    }

    assertThat(jdbc.sql("SELECT count(*) FROM cabins WHERE cabin_id = :id").param("id", cabinId).query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM processed_messages WHERE message_id = :messageId").param("messageId", messageId).query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM command_idempotency_records WHERE idempotency_key = :key").param("key", key).query(Long.class).single()).isZero();
  }

  @Test
  void bootstrapCreatesFivePrioritiesOnlyOnceEvenWhenInvokedConcurrently() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> { start.await(); return priorities.configureAll(NOW); });
      var second = executor.submit(() -> { start.await(); return priorities.configureAll(NOW); });
      start.countDown();
      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
    }
    assertThat(jdbc.sql("SELECT count(*) FROM resource_priorities").query(Long.class).single()).isEqualTo(5);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type = 'ResourcePriorityConfigured'").query(Long.class).single()).isEqualTo(5);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type = 'ResourcePriorityConfigured' AND routing_key = 'ResourcePriorityConfigured'").query(Long.class).single()).isEqualTo(5);
  }

  @Test
  void authorizationProjectionIsMonotonicAndRoleChangedUsesNewRoleExplicitly() {
    UUID accountId = UUID.randomUUID();
    assertThat(projectionUpdater.apply(new SystemAccessGranted(UUID.randomUUID(), accountId, "PRACTICAS_PROFESIONALES", 20, NOW))).isFalse();
    assertThat(projectionUpdater.apply(new SystemAccessGranted(UUID.randomUUID(), accountId, "LAVADO_ULTRASONICO", 2, NOW))).isTrue();
    assertThat(projectionUpdater.apply(new SystemAccessSuspended(UUID.randomUUID(), accountId, "LAVADO_ULTRASONICO", 1, NOW))).isTrue();
    assertThat(jdbc.sql("SELECT access_status::text FROM authorization_access_projection WHERE account_id = :id").param("id", accountId).query(String.class).single()).isEqualTo("ACTIVE");

    projectionUpdater.apply(new RoleAssigned(UUID.randomUUID(), accountId, "ADMINISTRADOR_LAVADO", 1, NOW));
    projectionUpdater.apply(new RoleChanged(UUID.randomUUID(), accountId, "ADMINISTRADOR_LAVADO", "SUPERVISOR_LAVADO", 2, NOW.plusSeconds(1)));
    assertThat(jdbc.sql("SELECT role_code FROM authorization_role_projection WHERE account_id = :id").param("id", accountId).query(String.class).single()).isEqualTo("SUPERVISOR_LAVADO");

    CommandOutcome denied = dispatchAs(accountId, UUID.randomUUID(), UUID.randomUUID().toString(), new AdministrativeCommand.CreateServiceCalendar(UUID.randomUUID(), "ROLE-CHANGED", "Role changed", "UTC", LocalDate.of(2026, 1, 1), null));
    assertThat(denied.status()).isEqualTo(Status.REJECTED);
    assertThat(denied.errorCode()).isEqualTo("ACTOR_NOT_AUTHORIZED");
  }

  @Test
  void internalOutcomeModelIncludesAllTerminalResultStates() {
    assertThat(CommandOutcome.succeeded(UUID.randomUUID(), 1).status()).isEqualTo(Status.SUCCEEDED);
    assertThat(CommandOutcome.rejected("INVALID_STATE", UUID.randomUUID()).status()).isEqualTo(Status.REJECTED);
    assertThat(CommandOutcome.failed("TECHNICAL_FAILURE", UUID.randomUUID(), 1).status()).isEqualTo(Status.FAILED);
    assertThat(CommandOutcome.expired("COMMAND_EXPIRED", UUID.randomUUID(), 1).status()).isEqualTo(Status.EXPIRED);
  }

  private CommandOutcome dispatch(AdministrativeCommand command) {
    return dispatch(UUID.randomUUID().toString(), command);
  }

  private CommandOutcome dispatch(String key, AdministrativeCommand command) {
    return dispatchAs(admin, UUID.randomUUID(), key, command);
  }

  private CommandOutcome dispatchAs(UUID actor, UUID messageId, String key, AdministrativeCommand command) {
    return dispatcher.dispatch(request(actor, messageId, key, command));
  }

  private CommandRequest request(UUID actor, UUID messageId, String key, AdministrativeCommand command) {
    return new CommandRequest(
        new CommandMetadata(
            messageId,
            command.operationType(),
            1,
            "bff",
            NOW,
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            key,
            null,
            null,
            ActorContext.account(actor)),
        command);
  }

  private void assertSucceeded(AdministrativeCommand command) {
    assertThat(dispatch(command).status()).as(command.operationType()).isEqualTo(Status.SUCCEEDED);
  }
}
