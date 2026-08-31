package com.estoma.platformbff.application.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.domain.projection.RecoveryJob;
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
class SessionContextRecoveryPostgresIT {
  private static final String NOW = "2026-08-30T18:00:00Z";

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("bff_session_context").withUsername("bff").withPassword("bff");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired AccountPresentationRecoveryService accounts;
  @Autowired PersonPresentationRecoveryService people;
  @Autowired SystemCatalogRecoveryService systems;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clear() {
    jdbc.sql("""
        TRUNCATE TABLE projection_recovery_messages, projection_recovery_jobs, projection_status,
          inbox_messages, person_presentation_read_model, account_presentation_read_model,
          authorization_system_catalog_read_model CASCADE
        """).update();
  }

  @Test
  void fullSnapshotsRebuildTheTwoRecoverableSessionContextInputs() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    UUID systemId = UUID.randomUUID();
    UUID accountJobId = accounts.startFull();
    RecoveryJob accountJob = job(accountJobId);

    assertThat(accounts.applyResult(result("authentication", "ListAccountPresentationSnapshotsPageResult", accountJob,
        "SUCCEEDED", Map.of("items", List.of(Map.of(
            "accountId", accountId.toString(), "personId", personId.toString(), "loginType", "MATRICULA",
            "loginIdentifier", "202257019", "accountStatus", "ACTIVE", "sourceVersion", 4,
            "updatedAt", NOW)), "hasMore", false)))).isTrue();

    UUID catalogJobId = systems.startFull();
    RecoveryJob catalogJob = job(catalogJobId);
    assertThat(systems.applyResult(result("authorization", "ListSystemCatalogSnapshotsPageResult", catalogJob,
        "SUCCEEDED", Map.of("items", List.of(Map.of(
            "systemId", systemId.toString(), "systemCode", "LAVADO_ULTRASONICO", "status", "ACTIVE",
            "sourceVersion", 2)), "hasMore", false)))).isTrue();

    assertThat(jdbc.sql("SELECT login_identifier FROM account_presentation_read_model WHERE account_id = :id")
        .param("id", accountId).query(String.class).single()).isEqualTo("202257019");
    assertThat(jdbc.sql("SELECT trusted FROM authorization_system_catalog_read_model WHERE system_id = :id")
        .param("id", systemId).query(Boolean.class).single()).isTrue();
    assertThat(jdbc.sql("SELECT status FROM projection_status WHERE projection_name = 'account-presentation'")
        .query(String.class).single()).isEqualTo("READY");
    assertThat(jdbc.sql("SELECT status FROM projection_status WHERE projection_name = 'system-catalog'")
        .query(String.class).single()).isEqualTo("READY");
  }

  @Test
  void personPresentationRecoveryReplacesTheHistoricalNullEmail() {
    UUID personId = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO person_presentation_read_model
          (person_id, first_name, paternal_surname, maternal_surname, institutional_email,
           status, source_version, updated_at)
        VALUES (:personId, 'Ana', 'García', NULL, NULL, 'ACTIVE', 3, :updatedAt)
        """).param("personId", personId).param("updatedAt", java.sql.Timestamp.from(Instant.parse(NOW))).update();

    RecoveryJob job = job(people.startFull());
    assertThat(people.applyResult(result("users", "ListPersonPresentationSnapshotsPageResult", job,
        "SUCCEEDED", Map.of("items", List.of(Map.of(
            "personId", personId.toString(), "firstName", "Ana", "paternalSurname", "García",
            "institutionalEmail", "ana@example.invalid", "status", "ACTIVE", "sourceVersion", 3,
            "updatedAt", NOW)), "hasMore", false)))).isTrue();

    assertThat(jdbc.sql("SELECT institutional_email FROM person_presentation_read_model WHERE person_id = :personId")
        .param("personId", personId).query(String.class).single()).isEqualTo("ana@example.invalid");
    assertThat(jdbc.sql("SELECT status FROM projection_status WHERE projection_name = 'person'")
        .query(String.class).single()).isEqualTo("READY");
  }

  private RecoveryJob job(UUID id) {
    return jdbc.sql("SELECT * FROM projection_recovery_jobs WHERE recovery_job_id = :id")
        .param("id", id).query((rs, row) -> new RecoveryJob(
            rs.getObject("recovery_job_id", UUID.class), rs.getString("projection_name"), rs.getString("mode"),
            rs.getObject("entity_id", UUID.class), rs.getLong("generation"), rs.getString("owner_logical_component_id"),
            rs.getString("query_type"), rs.getObject("operation_id", UUID.class), rs.getString("cursor_value"),
            rs.getString("status"), rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getString("last_error_code"), rs.getObject("current_query_message_id", UUID.class))).single();
  }

  private static IncomingResult result(String producer, String type, RecoveryJob job, String status,
      Map<String, Object> data) {
    return new IncomingResult(UUID.randomUUID(), type, 1, MessageKind.RESULT, producer, Instant.parse(NOW),
        job.recoveryJobId(), job.currentQueryMessageId(), job.operationId(), null, null, status,
        null, null, null, data);
  }
}
