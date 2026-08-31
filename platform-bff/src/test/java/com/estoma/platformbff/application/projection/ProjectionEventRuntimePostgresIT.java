package com.estoma.platformbff.application.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.platformbff.application.security.ProjectionEventQuarantineService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
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
          account_presentation_read_model,
          authorization_role_projection, authorization_access_projection,
          mailbox_messages_read_model, mailbox_visits_read_model,
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
  void communicationsVisitUsesVisitedAtAsMonotonicFenceWithoutAggregateMetadata() {
    UUID accountId = UUID.randomUUID();
    Instant newer = Instant.parse("2026-08-23T21:00:00Z");
    Instant older = Instant.parse("2026-08-23T20:00:00Z");

    assertThat(runtime.dispatch(communicationsEvent("InboxVisited",
        Map.of("accountId", accountId.toString(), "systemCode", "LAVADO_ULTRASONICO",
            "visitedAt", newer.toString())))).isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(runtime.dispatch(communicationsEvent("InboxVisited",
        Map.of("accountId", accountId.toString(), "systemCode", "LAVADO_ULTRASONICO",
            "visitedAt", older.toString())))).isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);

    OffsetDateTime stored = jdbc.sql("""
        SELECT last_visited_at FROM mailbox_visits_read_model
        WHERE account_id = :accountId AND system_code = 'LAVADO_ULTRASONICO'
        """).param("accountId", accountId).query(OffsetDateTime.class).single();
    assertThat(stored.toInstant()).isEqualTo(newer);
  }

  @Test
  void expirationBeforeCreationCreatesTombstoneAndLateCreationCannotReviveMessage() {
    UUID inboxMessageId = UUID.randomUUID();
    UUID communicationId = UUID.randomUUID();
    UUID recipientId = UUID.randomUUID();
    Instant expiredAt = Instant.parse("2026-08-23T21:00:00Z");
    Instant createdAt = Instant.parse("2026-08-23T19:00:00Z");

    assertThat(runtime.dispatch(communicationsEvent("InboxMessageExpired",
        Map.of("inboxMessageId", inboxMessageId.toString(), "communicationId", communicationId.toString(),
            "expiredAt", expiredAt.toString())))).isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);

    assertThat(jdbc.sql("SELECT communication_id FROM mailbox_messages_read_model WHERE inbox_message_id = :id")
        .param("id", inboxMessageId).query(UUID.class).optional()).isEmpty();

    ProjectionEvent created = event("InboxMessageCreated", "communications", UUID.randomUUID(), communicationId, 1,
        Map.of("inboxMessageId", inboxMessageId.toString(), "communicationId", communicationId.toString(),
            "recipientAccountId", recipientId.toString(), "systemCode", "LAVADO_ULTRASONICO",
            "communicationType", "APPOINTMENT", "title", "Cita", "body", "Mensaje",
            "createdAt", createdAt.toString()));
    assertThat(runtime.dispatch(created)).isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);

    assertThat(jdbc.sql("SELECT communication_id FROM mailbox_messages_read_model WHERE inbox_message_id = :id")
        .param("id", inboxMessageId).query(UUID.class).single()).isEqualTo(communicationId);
    OffsetDateTime storedExpiredAt = jdbc.sql("SELECT expired_at FROM mailbox_messages_read_model WHERE inbox_message_id = :id")
        .param("id", inboxMessageId).query(OffsetDateTime.class).single();
    assertThat(storedExpiredAt.toInstant()).isEqualTo(expiredAt);
  }

  @Test
  void staleExpirationCannotMoveExpiredAtBackwards() {
    UUID inboxMessageId = UUID.randomUUID();
    Instant newer = Instant.parse("2026-08-23T22:00:00Z");
    Instant older = Instant.parse("2026-08-23T21:00:00Z");

    runtime.dispatch(communicationsEvent("InboxMessageExpired",
        Map.of("inboxMessageId", inboxMessageId.toString(), "expiredAt", newer.toString())));
    runtime.dispatch(communicationsEvent("InboxMessageExpired",
        Map.of("inboxMessageId", inboxMessageId.toString(), "expiredAt", older.toString())));

    OffsetDateTime stored = jdbc.sql("SELECT expired_at FROM mailbox_messages_read_model WHERE inbox_message_id = :id")
        .param("id", inboxMessageId).query(OffsetDateTime.class).single();
    assertThat(stored.toInstant()).isEqualTo(newer);
  }

  @Test
  void inboxMessageCreatedAggregateMustIdentifyCommunicationAndRollsBackInboxOnMismatch() {
    UUID inboxMessageId = UUID.randomUUID();
    UUID communicationId = UUID.randomUUID();
    UUID wrongAggregateId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    ProjectionEvent created = event("InboxMessageCreated", "communications", messageId, wrongAggregateId, 1,
        Map.of("inboxMessageId", inboxMessageId.toString(), "communicationId", communicationId.toString(),
            "recipientAccountId", UUID.randomUUID().toString(), "systemCode", "LAVADO_ULTRASONICO",
            "communicationType", "APPOINTMENT", "title", "Cita", "body", "Mensaje",
            "createdAt", now()));

    assertThatThrownBy(() -> runtime.dispatch(created)).isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE message_id = :id")
        .param("id", messageId).query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM mailbox_messages_read_model WHERE inbox_message_id = :id")
        .param("id", inboxMessageId).query(Long.class).single()).isZero();
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
        .param("id", personId).query(String.class).optional()).contains("ada@example.invalid");

    byte[] sensitiveBody = "title=private&body=confidential".getBytes(StandardCharsets.UTF_8);
    UUID quarantineMessageId = UUID.randomUUID();
    quarantine.quarantine(quarantineMessageId, "MALFORMED_PROJECTION_EVENT", sensitiveBody);
    String storedDigest = jdbc.sql("SELECT body_sha256 FROM quarantined_messages WHERE message_id = :id")
        .param("id", quarantineMessageId).query(String.class).single();
    String expectedDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sensitiveBody));

    assertThat(storedDigest).isEqualTo(expectedDigest);
    assertThat(storedDigest).doesNotContain("private", "confidential");
  }

  @Test
  void personEmailAndAccountPresentationUseIndependentOwnerVersionFences() {
    UUID personId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    runtime.dispatch(personCreated(UUID.randomUUID(), personId, 1, "ACTIVE"));
    runtime.dispatch(event("PersonUpdated", "users", UUID.randomUUID(), personId, 2,
        Map.of("personId", personId.toString(), "firstName", "Ada", "paternalSurname", "Nav",
            "institutionalEmail", "ada.updated@example.invalid", "status", "ACTIVE", "updatedAt", now())));
    runtime.dispatch(event("AccountPresentationUpdated", "authentication", UUID.randomUUID(), accountId, 2,
        Map.of("accountId", accountId.toString(), "personId", personId.toString(), "loginType", "MATRICULA",
            "loginIdentifier", "202257019", "accountStatus", "ACTIVE", "updatedAt", now())));
    runtime.dispatch(event("AccountPresentationUpdated", "authentication", UUID.randomUUID(), accountId, 1,
        Map.of("accountId", accountId.toString(), "personId", personId.toString(), "loginType", "MATRICULA",
            "loginIdentifier", "stale", "accountStatus", "INACTIVE", "updatedAt", now())));

    assertThat(jdbc.sql("SELECT institutional_email FROM person_presentation_read_model WHERE person_id = :id")
        .param("id", personId).query(String.class).single()).isEqualTo("ada.updated@example.invalid");
    assertThat(jdbc.sql("SELECT login_identifier, account_status FROM account_presentation_read_model WHERE account_id = :id")
        .param("id", accountId).query((rs, row) -> rs.getString("login_identifier") + ":" + rs.getString("account_status"))
        .single()).isEqualTo("202257019:ACTIVE");
  }

  private static ProjectionEvent personCreated(UUID messageId, UUID personId, int version, String status) {
    return event("PersonCreated", "users", messageId, personId, version,
        Map.of("personId", personId.toString(), "firstName", "Ada", "paternalSurname", "Nav",
            "institutionalEmail", "ada@example.invalid", "status", status, "createdAt", now()));
  }

  private static ProjectionEvent personUpdated(UUID messageId, UUID personId, int version, String status) {
    return event("PersonUpdated", "users", messageId, personId, version,
        Map.of("personId", personId.toString(), "firstName", "Ada", "paternalSurname", "Nav",
            "institutionalEmail", "ada@example.invalid", "status", status, "updatedAt", now()));
  }

  private static ProjectionEvent event(String type, String producer, UUID messageId, UUID aggregateId,
      int version, Map<String, Object> payload) {
    return new ProjectionEvent(messageId, type, 1, producer, Instant.parse(now()), UUID.randomUUID(),
        UUID.randomUUID(), aggregateId, version, payload);
  }

  private static ProjectionEvent communicationsEvent(String type, Map<String, Object> payload) {
    return new ProjectionEvent(UUID.randomUUID(), type, 1, "communications", Instant.parse(now()),
        UUID.randomUUID(), UUID.randomUUID(), null, null, payload);
  }

  private static String now() {
    return "2026-08-23T20:00:00Z";
  }
}
