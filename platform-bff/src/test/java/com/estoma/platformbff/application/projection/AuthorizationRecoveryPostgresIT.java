package com.estoma.platformbff.application.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "platform.outbox.enabled=false",
    "platform.messaging.listeners-enabled=false",
    "platform.messaging.topology-declare=false"
})
@Tag("integration")
class AuthorizationRecoveryPostgresIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("bff_db").withUsername("bff").withPassword("bff");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired AuthorizationRecoveryService recovery;
  @Autowired ProjectionEventRuntime runtime;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clear() {
    jdbc.sql("""
        TRUNCATE TABLE projection_recovery_messages, projection_recovery_jobs,
          projection_status, inbox_messages, authorization_role_projection,
          authorization_access_projection CASCADE
        """).update();
  }

  @Test
  void entityRecoveryAppliesSnapshotAtomicallyWithoutClientOperation() {
    UUID accountId = UUID.randomUUID();
    UUID systemId = UUID.randomUUID();
    UUID accessId = UUID.randomUUID();
    UUID jobId = recovery.startEntity(accountId, "LAVADO_ULTRASONICO");
    var job = recoveryJob(jobId);

    assertThat(recovery.applyResult(result(
        "GetAccountAuthorizationSnapshotResult", job, "FOUND",
        Map.of("accountId", accountId.toString(),
            "role", Map.of("roleCode", "ADMIN", "roleVersion", 4),
            "systemAccesses", List.of(Map.of("systemAccessId", accessId.toString(),
                "systemId", systemId.toString(), "systemCode", "LAVADO_ULTRASONICO",
                "status", "ACTIVE", "accessVersion", 9)))))).isTrue();

    assertThat(jdbc.sql("SELECT role_code FROM authorization_role_projection WHERE account_id = :id")
        .param("id", accountId).query(String.class).single()).isEqualTo("ADMIN");
    assertThat(jdbc.sql("SELECT access_source_version FROM authorization_access_projection WHERE account_id = :id")
        .param("id", accountId).query(Long.class).single()).isEqualTo(9L);
    assertThat(jdbc.sql("SELECT count(*) FROM client_operations").query(Long.class).single()).isZero();
    assertThat(recoveryJob(jobId).status()).isEqualTo("COMPLETED");
  }

  @Test
  void notFoundNeverDeletesAnAuthorizationEventThatArrivedConcurrently() {
    UUID accountId = UUID.randomUUID();
    runtime.dispatch(new ProjectionEvent(UUID.randomUUID(), "AccountRoleAssigned", 1, "authorization",
        Instant.parse(NOW), UUID.randomUUID(), UUID.randomUUID(), accountId, 2,
        Map.of("accountId", accountId.toString(), "roleCode", "ADMIN", "assignedAt", NOW)));
    UUID jobId = recovery.startEntity(accountId, null);
    var job = recoveryJob(jobId);

    assertThat(recovery.applyResult(result("GetAccountAuthorizationSnapshotResult", job, "NOT_FOUND", null))).isTrue();
    assertThat(jdbc.sql("SELECT role_code FROM authorization_role_projection WHERE account_id = :id")
        .param("id", accountId).query(String.class).single()).isEqualTo("ADMIN");
    assertThat(jdbc.sql("SELECT status FROM projection_status WHERE projection_name = 'authorization'")
        .query(String.class).single()).isEqualTo("DEGRADED");
  }

  @Test
  void fullRecoveryUsesCursorAndOldGenerationResultCannotAdvanceNewGeneration() {
    UUID oldJobId = recovery.startFull();
    var oldJob = recoveryJob(oldJobId);
    UUID newJobId = recovery.startFull();
    var newJob = recoveryJob(newJobId);
    assertThat(newJob.generation()).isGreaterThan(oldJob.generation());

    assertThat(recovery.applyResult(result("ListAccountAuthorizationSnapshotsPageResult", oldJob, "SUCCEEDED",
        Map.of("items", List.of(), "hasMore", false, "snapshotGeneratedAt", NOW)))).isTrue();
    assertThat(recoveryJob(oldJobId).status()).isEqualTo("RUNNING");
    assertThat(jdbc.sql("SELECT generation FROM projection_status WHERE projection_name = 'authorization'")
        .query(Long.class).single()).isEqualTo(newJob.generation());

    UUID accountId = UUID.randomUUID();
    UUID firstQuery = newJob.currentQueryMessageId();
    assertThat(recovery.applyResult(result("ListAccountAuthorizationSnapshotsPageResult", newJob, "SUCCEEDED",
        Map.of("items", List.of(Map.of("accountId", accountId.toString(), "systemAccesses", List.of())),
            "hasMore", true, "nextCursor", UUID.randomUUID().toString(), "snapshotGeneratedAt", NOW)))).isTrue();
    var paged = recoveryJob(newJobId);
    assertThat(paged.cursor()).isNotBlank();
    assertThat(paged.currentQueryMessageId()).isNotEqualTo(firstQuery);
    assertThat(jdbc.sql("SELECT count(*) FROM projection_recovery_messages WHERE recovery_job_id = :id")
        .param("id", newJobId).query(Long.class).single()).isEqualTo(2L);

    assertThat(recovery.applyResult(result("ListAccountAuthorizationSnapshotsPageResult", paged, "SUCCEEDED",
        Map.of("items", List.of(), "hasMore", false, "snapshotGeneratedAt", NOW)))).isTrue();
    assertThat(recoveryJob(newJobId).status()).isEqualTo("COMPLETED");
    assertThat(jdbc.sql("SELECT status FROM projection_status WHERE projection_name = 'authorization'")
        .query(String.class).single()).isEqualTo("READY");
  }

  private com.estoma.platformbff.domain.projection.RecoveryJob recoveryJob(UUID id) {
    return jdbc.sql("SELECT * FROM projection_recovery_jobs WHERE recovery_job_id = :id")
        .param("id", id).query((rs, row) -> new com.estoma.platformbff.domain.projection.RecoveryJob(
            rs.getObject("recovery_job_id", UUID.class), rs.getString("projection_name"), rs.getString("mode"),
            rs.getObject("entity_id", UUID.class), rs.getLong("generation"), rs.getString("owner_logical_component_id"),
            rs.getString("query_type"), rs.getObject("operation_id", UUID.class), rs.getString("cursor_value"),
            rs.getString("status"), rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getString("last_error_code"), rs.getObject("current_query_message_id", UUID.class))).single();
  }

  private static IncomingResult result(String type, com.estoma.platformbff.domain.projection.RecoveryJob job,
      String status, Map<String, Object> data) {
    return new IncomingResult(UUID.randomUUID(), type, 1, MessageKind.RESULT, "authorization",
        Instant.parse(NOW), job.recoveryJobId(), job.currentQueryMessageId(), job.operationId(),
        null, null, status, null, null, null, data);
  }

  private static final String NOW = "2026-08-23T20:00:00Z";
}
