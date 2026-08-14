package com.estoma.washadministration.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washadministration.testsupport.AbstractPostgresIT;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class FlywayMigrationIT extends AbstractPostgresIT {

  @Autowired private Flyway flyway;
  @Autowired private JdbcClient jdbc;

  @Test
  void allMigrationsApplyAndSecondMigrateIsANoOp() {
    MigrationInfo[] applied = flyway.info().applied();

    assertThat(applied).hasSize(4);
    assertThat(applied)
        .extracting(MigrationInfo::getVersion)
        .extracting(Object::toString)
        .containsExactly("1", "2", "3", "4");
    assertThat(applied).allSatisfy(info -> assertThat(info.getState().isFailed()).isFalse());
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }

  @Test
  void requiredTablesExist() {
    assertThat(jdbc.sql(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name IN ('service_calendars', 'service_schedules', 'administrative_closures', "
                + "'cabins', 'tanks', 'resource_priorities', 'authorization_role_projection', "
                + "'authorization_access_projection', 'processed_messages', 'command_idempotency_records', "
                + "'outbox_messages', 'projection_recovery_jobs', 'administrative_audit_records')")
        .query(String.class)
        .list())
        .containsExactlyInAnyOrder(
            "service_calendars",
            "service_schedules",
            "administrative_closures",
            "cabins",
            "tanks",
            "resource_priorities",
            "authorization_role_projection",
            "authorization_access_projection",
            "processed_messages",
            "command_idempotency_records",
            "outbox_messages",
            "projection_recovery_jobs",
            "administrative_audit_records");
  }

  @Test
  void constraintsRejectInvalidCapacityVersionScopeAndPriority() {
    UUID cabinId = UUID.randomUUID();
    insertCabin(cabinId, "C-1");
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO tanks (tank_id, cabin_id, code, name, configured_capacity, status, version, created_by_account_id, updated_by_account_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)")
                    .params(
                        UUID.randomUUID(),
                        cabinId,
                        "T-1",
                        "Tank",
                        0,
                        1,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        OffsetDateTime.now(),
                        OffsetDateTime.now())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_tanks_capacity_positive");

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO service_calendars (service_calendar_id, code, name, timezone, effective_from, effective_until, status, version, created_by_account_id, updated_by_account_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)")
                    .params(
                        UUID.randomUUID(),
                        "BAD",
                        "Calendar",
                        "UTC",
                        LocalDate.of(2026, 2, 1),
                        LocalDate.of(2026, 1, 1),
                        0,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        OffsetDateTime.now(),
                        OffsetDateTime.now())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO administrative_closures (administrative_closure_id, service_calendar_id, scope, starts_at, ends_at, reason, status, version, created_by_account_id, created_at, updated_at) "
                            + "VALUES (?, ?, 'CABIN', ?, ?, ?, 'SCHEDULED', 1, ?, ?, ?)")
                    .params(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        OffsetDateTime.now(),
                        OffsetDateTime.now().plusHours(1),
                        "reason",
                        UUID.randomUUID(),
                        OffsetDateTime.now(),
                        OffsetDateTime.now())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_administrative_closures_scope_refs");

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO resource_priorities (resource_priority_id, appointment_type, cabin_type, usage_mode, priority_order, status, version, created_at, updated_at) "
                            + "VALUES (?, 'NORMAL', 'IMMUNOCOMPROMISED', 'PREFERRED', 1, 'ACTIVE', 1, ?, ?)")
                    .params(UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_resource_priorities_fixed_matrix");
  }

  @Test
  void tankCodeAndCabinCodeAreUnique() {
    UUID cabinId = UUID.randomUUID();
    insertCabin(cabinId, "C-1");
    insertTank(cabinId, "T-1");

    assertThatThrownBy(() -> insertTank(cabinId, "T-1"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ux_tanks_cabin_code");
    assertThatThrownBy(() -> insertCabin(UUID.randomUUID(), "C-1"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ux_cabins_code");
  }

  @Test
  void outboxRequiresAnActorAndIdempotencySupportsAllTerminalStatuses() {
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO outbox_messages (message_id, message_type, message_version, message_kind, producer, routing_key, occurred_at, correlation_id, payload, status, attempts, available_at) "
                            + "VALUES (?, 'Event', 1, 'EVENT', 'wash-administration', 'wash-administration.test.event', ?, ?, '{}'::jsonb, 'PENDING', 0, ?)")
                    .params(UUID.randomUUID(), OffsetDateTime.now(), UUID.randomUUID(), OffsetDateTime.now())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(
            jdbc.sql(
                    "SELECT enumlabel FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid "
                        + "WHERE t.typname = 'idempotency_status' ORDER BY e.enumsortorder")
                .query(String.class)
                .list())
        .containsExactly("IN_PROGRESS", "SUCCEEDED", "REJECTED", "FAILED", "EXPIRED");
  }

  @Test
  void recoveryJobSchemaEnforcesDurableMachineShape() {
    UUID activeFull = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO projection_recovery_jobs (
              recovery_job_id, projection_type, scope, status, query_attempt, attempts, created_at, updated_at
            ) VALUES (?, 'AUTHORIZATION', 'FULL', 'PENDING', 0, 0, ?, ?)
            """)
        .params(activeFull, OffsetDateTime.now(), OffsetDateTime.now())
        .update();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO projection_recovery_jobs (
                          recovery_job_id, projection_type, scope, status, query_attempt, attempts, created_at, updated_at
                        ) VALUES (?, 'AUTHORIZATION', 'FULL', 'PENDING', 0, 0, ?, ?)
                        """)
                    .params(UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ux_projection_recovery_full_active");

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO projection_recovery_jobs (
                          recovery_job_id, projection_type, scope, entity_key, status,
                          query_attempt, attempts, created_at, updated_at
                        ) VALUES (?, 'AUTHORIZATION', 'FULL', 'account:bad', 'PENDING', 0, 0, ?, ?)
                        """)
                    .params(UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_projection_recovery_entity_key");

    jdbc.sql("DELETE FROM projection_recovery_jobs WHERE recovery_job_id = :id")
        .param("id", activeFull)
        .update();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO projection_recovery_jobs (
                          recovery_job_id, projection_type, scope, status,
                          query_attempt, attempts, created_at, updated_at
                        ) VALUES (?, 'AUTHORIZATION', 'FULL', 'WAITING_RESULT', 1, 1, ?, ?)
                        """)
                    .params(UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_projection_recovery_state_shape");
  }

  private void insertCabin(UUID id, String code) {
    jdbc.sql(
            "INSERT INTO cabins (cabin_id, code, name, cabin_type, status, version, created_by_account_id, updated_by_account_id, created_at, updated_at) "
                + "VALUES (?, ?, 'Cabin', 'NORMAL', 'ACTIVE', 1, ?, ?, ?, ?)")
        .params(id, code, UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now())
        .update();
  }

  private void insertTank(UUID cabinId, String code) {
    jdbc.sql(
            "INSERT INTO tanks (tank_id, cabin_id, code, name, configured_capacity, status, version, created_by_account_id, updated_by_account_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'Tank', 1, 'ACTIVE', 1, ?, ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            cabinId,
            code,
            UUID.randomUUID(),
            UUID.randomUUID(),
            OffsetDateTime.now(),
            OffsetDateTime.now())
        .update();
  }
}
