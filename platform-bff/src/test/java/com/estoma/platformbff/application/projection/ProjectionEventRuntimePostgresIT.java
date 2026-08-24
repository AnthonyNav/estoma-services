package com.estoma.platformbff.application.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.platformbff.application.security.ProjectionEventQuarantineService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
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
class ProjectionEventRuntimePostgresIT {
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

  @Autowired ProjectionEventRuntime runtime;
  @Autowired ProjectionEventQuarantineService quarantine;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clear() {
    jdbc.sql("""
        TRUNCATE TABLE inbox_messages, person_presentation_read_model,
          authorization_role_projection, authorization_access_projection,
          wash_execution_read_model, wash_resource_assignment_read_model CASCADE
        """).update();
  }

  @Test
  void duplicateMessageIdIsIdempotent() {
    UUID personId = UUID.randomUUID();
    ProjectionEvent event = personCreated(UUID.randomUUID(), personId, 1, "ACTIVE");

    assertThat(runtime.dispatch(event)).isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(runtime.dispatch(event)).isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE consumer_name = 'bff-projection-runtime'")
        .query(Long.class).single()).isEqualTo(1L);
    assertThat(jdbc.sql("SELECT count(*) FROM person_presentation_read_model")
        .query(Long.class).single()).isEqualTo(1L);
  }

  @Test
  void staleOwnerEventCannotOverwriteNewerReadModel() {
    UUID personId = UUID.randomUUID();
    runtime.dispatch(personCreated(UUID.randomUUID(), personId, 2, "ACTIVE"));
    runtime.dispatch(personUpdated(UUID.randomUUID(), personId, 1, "INACTIVE"));

    assertThat(jdbc.sql("SELECT status FROM person_presentation_read_model WHERE person_id = :id")
        .param("id", personId).query(String.class).single()).isEqualTo("ACTIVE");
  }

  @Test
  void roleAndAccessFenceIndependently() {
    UUID accountId = UUID.randomUUID();
    UUID systemId = UUID.randomUUID();
    UUID accessId = UUID.randomUUID();
    runtime.dispatch(event("AccountRoleAssigned", "authorization", UUID.randomUUID(), accountId, 2,
        Map.of("accountId", accountId.toString(), "roleCode", "ADMIN", "assignedAt", now())));
    runtime.dispatch(event("AccountRoleChanged", "authorization", UUID.randomUUID(), accountId, 1,
        Map.of("accountId", accountId.toString(), "newRoleCode", "STUDENT", "changedAt", now(),
            "previousRoleId", UUID.randomUUID().toString(), "previousRoleCode", "ADMIN", "newRoleId", UUID.randomUUID().toString())));
    runtime.dispatch(event("SystemAccessGranted", "authorization", UUID.randomUUID(), accessId, 2,
        Map.of("systemAccessId", accessId.toString(), "accountId", accountId.toString(), "systemId", systemId.toString(),
            "systemCode", "LAVADO_ULTRASONICO", "status", "ACTIVE", "grantedAt", now())));
    runtime.dispatch(event("SystemAccessRevoked", "authorization", UUID.randomUUID(), accessId, 1,
        Map.of("systemAccessId", accessId.toString(), "accountId", accountId.toString(), "systemId", systemId.toString(),
            "systemCode", "LAVADO_ULTRASONICO", "status", "REVOKED", "revokedAt", now())));

    assertThat(jdbc.sql("SELECT role_code FROM authorization_role_projection WHERE account_id = :id")
        .param("id", accountId).query(String.class).single()).isEqualTo("ADMIN");
    assertThat(jdbc.sql("SELECT status FROM authorization_access_projection WHERE account_id = :id")
        .param("id", accountId).query(String.class).single()).isEqualTo("ACTIVE");
  }

  @Test
  void washEventsWithSameExecutionVersionApplyIndependentEffects() {
    UUID executionId = UUID.randomUUID();
    UUID assignmentId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID tankId = UUID.randomUUID();
    runtime.dispatch(event("WashResourceAssigned", "wash-execution", UUID.randomUUID(), executionId, 7,
        Map.of("resourceAssignmentId", assignmentId.toString(), "washExecutionId", executionId.toString(),
            "appointmentId", appointmentId.toString(), "appointmentTimeSlotId", slotId.toString(),
            "cabinId", cabinId.toString(), "tankId", tankId.toString(), "assignmentType", "NORMAL",
            "assignedAt", now())));
    runtime.dispatch(event("WashExecutionStarted", "wash-execution", UUID.randomUUID(), executionId, 7,
        Map.of("washExecutionId", executionId.toString(), "appointmentId", appointmentId.toString(),
            "executionStatus", "STARTED", "executionVersion", 7, "appointmentType", "NORMAL",
            "appointmentTimeSlotId", slotId.toString(), "resourceAssignmentId", assignmentId.toString(),
            "cabinId", cabinId.toString(), "tankId", tankId.toString(), "startedAt", now())));

    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE consumer_name = 'bff-projection-runtime'")
        .query(Long.class).single()).isEqualTo(2L);
    assertThat(jdbc.sql("SELECT count(*) FROM wash_resource_assignment_read_model")
        .query(Long.class).single()).isEqualTo(1L);
    assertThat(jdbc.sql("SELECT status FROM wash_execution_read_model WHERE wash_execution_id = :id")
        .param("id", executionId).query(String.class).single()).isEqualTo("STARTED");
  }

  @Test
  void unknownProducerIsRejectedBeforeInboxMutation() {
    ProjectionEvent event = event("PersonCreated", "untrusted-owner", UUID.randomUUID(), UUID.randomUUID(), 1,
        Map.of("personId", UUID.randomUUID().toString(), "firstName", "Ada", "paternalSurname", "Nav",
            "status", "ACTIVE"));
    assertThat(runtime.dispatch(event)).isEqualTo(ProjectionEventRuntime.DispatchOutcome.UNTRUSTED);
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE consumer_name = 'bff-projection-runtime'")
        .query(Long.class).single()).isZero();
  }

  @Test
  void privacyBoundaryDropsUnneededPersonPiiAndStoresQuarantineDigestOnly() throws Exception {
    UUID personId = UUID.randomUUID();
    runtime.dispatch(event("PersonCreated", "users", UUID.randomUUID(), personId, 1,
        Map.of("personId", personId.toString(), "firstName", "Ada", "paternalSurname", "Nav",
            "institutionalEmail", "ada@example.invalid", "phone", "+52-555-0101",
            "status", "ACTIVE", "createdAt", now())));

    assertThat(jdbc.sql("SELECT institutional_email FROM person_presentation_read_model WHERE person_id = :id")
        .param("id", personId).query(String.class).optional()).isEmpty();

    byte[] sensitiveBody = "title=private&body=confidential".getBytes(StandardCharsets.UTF_8);
    UUID quarantineMessageId = UUID.randomUUID();
    quarantine.quarantine(quarantineMessageId, "MALFORMED_PROJECTION_EVENT", sensitiveBody);
    String storedDigest = jdbc.sql("SELECT body_sha256 FROM quarantined_messages WHERE message_id = :id")
        .param("id", quarantineMessageId).query(String.class).single();
    String expectedDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sensitiveBody));

    assertThat(storedDigest).isEqualTo(expectedDigest);
    assertThat(storedDigest).doesNotContain("private", "confidential");
  }

  private static ProjectionEvent personCreated(UUID messageId, UUID personId, int version, String status) {
    return event("PersonCreated", "users", messageId, personId, version,
        Map.of("personId", personId.toString(), "firstName", "Ada", "paternalSurname", "Nav",
            "status", status, "createdAt", now()));
  }

  private static ProjectionEvent personUpdated(UUID messageId, UUID personId, int version, String status) {
    return event("PersonUpdated", "users", messageId, personId, version,
        Map.of("personId", personId.toString(), "firstName", "Ada", "paternalSurname", "Nav",
            "status", status, "updatedAt", now()));
  }

  private static ProjectionEvent event(String type, String producer, UUID messageId, UUID aggregateId,
      int version, Map<String, Object> payload) {
    return new ProjectionEvent(messageId, type, 1, producer, Instant.parse(now()), UUID.randomUUID(),
        UUID.randomUUID(), aggregateId, version, payload);
  }

  private static String now() {
    return "2026-08-23T20:00:00Z";
  }
}
