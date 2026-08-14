package com.estoma.authorization.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authorization.testsupport.AbstractPostgresIT;
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
  void migrationIsAppliedAndRerunningIsANoOp() {
    MigrationInfo[] applied = flyway.info().applied();

    assertThat(applied).hasSize(1);
    assertThat(applied[0].getVersion().toString()).isEqualTo("1");
    assertThat(applied[0].getState().isFailed()).isFalse();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }

  @Test
  void seedContainsTheV1CatalogMatrix() {
    assertThat(jdbc.sql("SELECT code FROM systems ORDER BY code").query(String.class).list())
        .containsExactly("LAVADO_ULTRASONICO", "PRACTICAS_PROFESIONALES");
    assertThat(jdbc.sql("SELECT code FROM roles ORDER BY code").query(String.class).list())
        .containsExactlyInAnyOrder(
            "ADMINISTRADOR_LAVADO",
            "ADMINISTRADOR_PRACTICAS",
            "ALUMNO",
            "SUPERVISOR_LAVADO",
            "SUPERADMIN");
    assertThat(jdbc.sql("SELECT count(*) FROM permissions").query(Long.class).single()).isEqualTo(33);
    assertThat(jdbc.sql("SELECT count(*) FROM role_systems").query(Long.class).single()).isEqualTo(7);
    assertThat(jdbc.sql("SELECT count(*) FROM role_permissions").query(Long.class).single()).isEqualTo(33);
  }

  @Test
  void uniquenessAndStateConstraintsProtectFoundationInvariants() {
    UUID accountId = UUID.randomUUID();
    UUID systemId = UUID.fromString("00000000-0000-0000-0000-000000000101");
    UUID accessId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();

    insertAccess(accessId, accountId, systemId, "ACTIVE", null, null, now);
    assertThatThrownBy(
            () -> insertAccess(UUID.randomUUID(), accountId, systemId, "ACTIVE", null, null, now))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_system_accesses_account_system");

    assertThatThrownBy(
            () -> insertAccess(UUID.randomUUID(), UUID.randomUUID(), systemId, "SUSPENDED", null, null, now))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_system_accesses_reason");

    assertThatThrownBy(
            () -> insertAccess(UUID.randomUUID(), UUID.randomUUID(), systemId, "SUSPENDED", "OTHER", null, now))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_system_accesses_reason_detail");
  }

  @Test
  void nullableActorFieldsCannotBypassActorXorConstraints() {
    OffsetDateTime now = OffsetDateTime.now();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO account_roles "
                            + "(account_id, role_id, version, assigned_by_actor_type, assigned_at, updated_at) "
                            + "VALUES (:accountId, :roleId, 1, 'SYSTEM', :now, :now)")
                    .param("accountId", UUID.randomUUID())
                    .param("roleId", UUID.fromString("00000000-0000-0000-0000-000000000201"))
                    .param("now", now)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_account_roles_assigned_actor");

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO authorization_audit_entries "
                            + "(audit_id, source_message_id, source_producer, aggregate_type, action, actor_type, "
                            + "correlation_id, result, occurred_at) "
                            + "VALUES (:auditId, :messageId, 'users', 'Person', 'PERSON_CREATED', 'SYSTEM', "
                            + ":correlationId, 'SUCCEEDED', :now)")
                    .param("auditId", UUID.randomUUID())
                    .param("messageId", UUID.randomUUID())
                    .param("correlationId", UUID.randomUUID())
                    .param("now", now)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_audit_actor");
  }

  @Test
  void recoveryIndexesEnforceOneActiveJobPerScope() {
    OffsetDateTime now = OffsetDateTime.now();
    UUID first = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO projection_recovery_jobs "
                + "(recovery_job_id, projection_type, scope, status, query_attempt, attempts, created_at, updated_at) "
                + "VALUES (:id, 'PERSON_STATUS', 'FULL', 'PENDING', 0, 0, :now, :now)")
        .param("id", first)
        .param("now", now)
        .update();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO projection_recovery_jobs "
                            + "(recovery_job_id, projection_type, scope, status, query_attempt, attempts, created_at, updated_at) "
                            + "VALUES (:id, 'PERSON_STATUS', 'FULL', 'PENDING', 0, 0, :now, :now)")
                    .param("id", UUID.randomUUID())
                    .param("now", now)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ux_recovery_full_active");
  }

  @Test
  void runtimeRoleCannotDeleteOrModifyStaticCatalogsAndAuditIsAppendOnly() {
    assertThat(
            jdbc.sql(
                    "SELECT privilege_type FROM information_schema.role_table_grants "
                        + "WHERE grantee = current_user AND table_name = 'authorization_audit_entries'")
                .query(String.class)
                .list())
        .contains("INSERT", "SELECT")
        .doesNotContain("UPDATE", "DELETE");
    assertThat(
            jdbc.sql(
                    "SELECT privilege_type FROM information_schema.role_table_grants "
                        + "WHERE grantee = current_user AND table_name = 'systems'")
                .query(String.class)
                .list())
        .containsOnly("SELECT");
  }

  @Test
  void outboxAndAcademicDeferredMetadataAreConstrained() {
    OffsetDateTime now = OffsetDateTime.now();
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO command_idempotency_records "
                            + "(producer, operation_type, idempotency_key, request_fingerprint, status, created_at, updated_at) "
                            + "VALUES ('academic', 'AssignAccountRole', 'missing-metadata', 'fingerprint', 'DEFERRED', :now, :now)")
                    .param("now", now)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_idempotency_academic_deferred");

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO outbox_messages "
                            + "(message_id, message_type, message_version, message_kind, producer, routing_key, "
                            + "correlation_id, payload, status, attempts, occurred_at, available_at) "
                            + "VALUES (:messageId, 'AccountRoleAssigned', 1, 'EVENT', 'authorization', "
                            + "'authorization.account-role.assigned', :correlationId, '{}'::jsonb, 'PENDING', 0, :now, :now)")
                    .param("messageId", UUID.randomUUID())
                    .param("correlationId", UUID.randomUUID())
                    .param("now", now)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_outbox_kind_metadata");

    assertThat(
            jdbc.sql(
                    "INSERT INTO outbox_messages "
                        + "(message_id, message_type, message_version, message_kind, producer, routing_key, "
                        + "operation_id, causation_id, actor, correlation_id, payload, status, attempts, occurred_at, available_at) "
                        + "VALUES (:messageId, 'GetAccountAuthorizationSnapshotResult', 1, 'RESULT', 'authorization', "
                        + "'authorization.query-result', :operationId, :causationId, '{}'::jsonb, :correlationId, '{}'::jsonb, "
                        + "'PENDING', 0, :now, :now)")
                .param("messageId", UUID.randomUUID())
                .param("operationId", UUID.randomUUID())
                .param("causationId", UUID.randomUUID())
                .param("correlationId", UUID.randomUUID())
                .param("now", now)
                .update())
        .isOne();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO outbox_messages "
                            + "(message_id, message_type, message_version, message_kind, producer, routing_key, "
                            + "operation_id, causation_id, actor, correlation_id, payload, status, attempts, occurred_at, available_at) "
                            + "VALUES (:messageId, 'AssignAccountRoleResult', 1, 'RESULT', 'authorization', "
                            + "'authorization.result', :operationId, :causationId, '{}'::jsonb, :correlationId, '{}'::jsonb, "
                            + "'PENDING', 0, :now, :now)")
                    .param("messageId", UUID.randomUUID())
                    .param("operationId", UUID.randomUUID())
                    .param("causationId", UUID.randomUUID())
                    .param("correlationId", UUID.randomUUID())
                    .param("now", now)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_outbox_command_result_idempotency");

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO command_idempotency_records "
                            + "(producer, operation_type, idempotency_key, request_fingerprint, status, result_status, completed_at, created_at, updated_at) "
                            + "VALUES ('bff', 'AssignAccountRole', 'incomplete', 'fingerprint', 'COMPLETED', 'SUCCEEDED', :now, :now, :now)")
                    .param("now", now)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_idempotency_state_shape");
  }

  private void insertAccess(
      UUID accessId,
      UUID accountId,
      UUID systemId,
      String status,
      String reasonCode,
      String reasonDetail,
      OffsetDateTime now) {
    jdbc.sql(
            """
            INSERT INTO system_accesses (
              system_access_id, account_id, system_id, status, reason_code, reason_detail, version,
              granted_by_account_id, granted_by_actor_type, granted_at, updated_at
            ) VALUES (
              :accessId, :accountId, :systemId, CAST(:status AS system_access_status), :reasonCode, :reasonDetail, 1,
              :actorAccountId, 'ACCOUNT', :now, :now
            )
            """)
        .param("accessId", accessId)
        .param("accountId", accountId)
        .param("systemId", systemId)
        .param("status", status)
        .param("reasonCode", reasonCode)
        .param("reasonDetail", reasonDetail)
        .param("actorAccountId", UUID.randomUUID())
        .param("now", now)
        .update();
  }
}
