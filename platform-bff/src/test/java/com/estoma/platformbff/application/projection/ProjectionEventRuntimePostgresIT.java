package com.estoma.platformbff.application.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.platformbff.application.security.ProjectionEventQuarantineService;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.EventEnvelope;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.infrastructure.messaging.ProjectionEventDispatcher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.HashMap;
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
import tools.jackson.databind.json.JsonMapper;

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
  @Autowired ProjectionEventDispatcher dispatcher;
  @Autowired ProjectionEventQuarantineService quarantine;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clear() {
    jdbc.sql("""
        TRUNCATE TABLE inbox_messages, session_revocations, person_presentation_read_model,
          account_presentation_read_model,
          authorization_role_projection, authorization_access_projection,
          mailbox_messages_read_model, mailbox_visits_read_model,
          appointment_qr_projection,
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
  void appointmentScheduledV1AcceptsOldPayloadAndProjectsOpaqueQrWhenPresent() throws Exception {
    UUID oldAppointmentId = UUID.randomUUID();
    UUID newAppointmentId = UUID.randomUUID();
    String representation = "owner.opaque.reference";

    assertThat(runtime.dispatch(appointmentEvent(
        "AppointmentScheduled", UUID.randomUUID(), oldAppointmentId, 1, null)))
        .isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(runtime.dispatch(appointmentEvent(
        "AppointmentScheduled", UUID.randomUUID(), newAppointmentId, 1, representation)))
        .isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);

    assertThat(jdbc.sql("SELECT count(*) FROM appointment_qr_projection WHERE appointment_id = :id")
        .param("id", oldAppointmentId).query(Long.class).single()).isZero();
    var projected = jdbc.sql("""
        SELECT qr_representation, qr_lookup_hash, appointment_version
        FROM appointment_qr_projection WHERE appointment_id = :id
        """).param("id", newAppointmentId).query().singleRow();
    assertThat(projected.get("qr_representation")).isEqualTo(representation);
    assertThat((byte[]) projected.get("qr_lookup_hash")).isEqualTo(
        MessageDigest.getInstance("SHA-256").digest(representation.getBytes(StandardCharsets.UTF_8)));
    assertThat(projected.get("appointment_version")).isEqualTo(1L);
  }

  @Test
  void invalidQrRepresentationIsRejectedBeforeInbox() {
    UUID appointmentId = UUID.randomUUID();
    UUID blankMessageId = UUID.randomUUID();
    UUID oversizedMessageId = UUID.randomUUID();

    assertThat(runtime.dispatch(appointmentEvent(
        "AppointmentScheduled", blankMessageId, appointmentId, 1, "")))
        .isEqualTo(ProjectionEventRuntime.DispatchOutcome.UNTRUSTED);
    assertThat(runtime.dispatch(appointmentEvent(
        "AppointmentScheduled", oversizedMessageId, appointmentId, 1, "x".repeat(2001))))
        .isEqualTo(ProjectionEventRuntime.DispatchOutcome.UNTRUSTED);

    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE message_id IN (:first, :second)")
        .param("first", blankMessageId).param("second", oversizedMessageId)
        .query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM appointment_qr_projection")
        .query(Long.class).single()).isZero();
  }

  @Test
  void duplicateAndStaleAppointmentEventsCannotRewriteOrRestoreQr() {
    UUID appointmentId = UUID.randomUUID();
    UUID scheduledMessageId = UUID.randomUUID();
    ProjectionEvent scheduled = appointmentEvent(
        "AppointmentScheduled", scheduledMessageId, appointmentId, 1, "owner.original");

    assertThat(runtime.dispatch(scheduled)).isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(runtime.dispatch(appointmentEvent(
        "AppointmentScheduled", scheduledMessageId, appointmentId, 1, "owner.mutated")))
        .isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(runtime.dispatch(appointmentEvent(
        "AppointmentScheduled", UUID.randomUUID(), appointmentId, 1, "owner.same-version")))
        .isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(jdbc.sql("SELECT qr_representation FROM appointment_qr_projection WHERE appointment_id = :id")
        .param("id", appointmentId).query(String.class).single()).isEqualTo("owner.original");
    assertThat(runtime.dispatch(appointmentEvent(
        "AppointmentCompleted", UUID.randomUUID(), appointmentId, 3, null)))
        .isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(runtime.dispatch(appointmentEvent(
        "AppointmentScheduled", UUID.randomUUID(), appointmentId, 3, "owner.same-terminal-version")))
        .isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(runtime.dispatch(appointmentEvent(
        "AppointmentScheduled", UUID.randomUUID(), appointmentId, 2, "owner.stale")))
        .isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);

    var projected = jdbc.sql("""
        SELECT qr_representation, qr_lookup_hash, appointment_version
        FROM appointment_qr_projection WHERE appointment_id = :id
        """).param("id", appointmentId).query().singleRow();
    assertThat(projected.get("qr_representation")).isNull();
    assertThat(projected.get("qr_lookup_hash")).isNull();
    assertThat(projected.get("appointment_version")).isEqualTo(3L);
  }

  @Test
  void terminalBeforeScheduleCreatesFenceAndStartedDoesNotInvalidate() {
    UUID cancelledId = UUID.randomUUID();
    UUID startedId = UUID.randomUUID();

    runtime.dispatch(appointmentEvent(
        "AppointmentCancelled", UUID.randomUUID(), cancelledId, 4, null));
    runtime.dispatch(appointmentEvent(
        "AppointmentScheduled", UUID.randomUUID(), cancelledId, 1, "owner.late"));
    runtime.dispatch(appointmentEvent(
        "AppointmentScheduled", UUID.randomUUID(), startedId, 1, "owner.active"));
    runtime.dispatch(appointmentEvent(
        "AppointmentStarted", UUID.randomUUID(), startedId, 2, null));

    assertThat(jdbc.sql("SELECT qr_representation FROM appointment_qr_projection WHERE appointment_id = :id")
        .param("id", cancelledId).query(String.class).optional()).isEmpty();
    assertThat(jdbc.sql("SELECT appointment_version FROM appointment_qr_projection WHERE appointment_id = :id")
        .param("id", cancelledId).query(Long.class).single()).isEqualTo(4L);
    assertThat(jdbc.sql("SELECT qr_representation FROM appointment_qr_projection WHERE appointment_id = :id")
        .param("id", startedId).query(String.class).single()).isEqualTo("owner.active");
  }

  @Test
  void duplicateQrHashRollsBackInboxAndAppointmentProjection() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    UUID secondMessageId = UUID.randomUUID();
    runtime.dispatch(appointmentEvent(
        "AppointmentScheduled", UUID.randomUUID(), firstId, 1, "owner.unique"));

    assertThatThrownBy(() -> runtime.dispatch(appointmentEvent(
        "AppointmentScheduled", secondMessageId, secondId, 1, "owner.unique")))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE message_id = :id")
        .param("id", secondMessageId).query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM appointment_read_model WHERE appointment_id = :id")
        .param("id", secondId).query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM appointment_qr_projection")
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
  void washEntryRejectedV2WithoutChecksProjectsReasonAndStatusFromAccountActor() {
    UUID executionId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    String reason = "Motivo libre sin clasificación";
    Map<String, Object> payload = new HashMap<>();
    payload.putAll(validRejectionPayload(executionId));
    payload.put("executionStatus", "ENTRY_REJECTED");
    payload.put("executionVersion", 2);
    payload.put("rejectionReason", reason);
    payload.put("decidedByAccountId", UUID.randomUUID().toString());
    payload.put("decidedAt", now());

    assertThat(dispatcher.dispatch(rejectionEnvelope(messageId, "WashEntryRejected", 2,
        "wash-execution", MessageKind.EVENT, executionId, payload)))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);

    var projected = jdbc.sql("""
        SELECT status, rejection_reason, execution_version
        FROM wash_execution_read_model WHERE wash_execution_id = :id
        """).param("id", executionId).query().singleRow();
    assertThat(projected.get("status")).isEqualTo("ENTRY_REJECTED");
    assertThat(projected.get("rejection_reason")).isEqualTo(reason);
    assertThat(projected.get("execution_version")).isEqualTo(2L);
    assertThat(jdbc.sql("""
        SELECT count(*) FROM inbox_messages
        WHERE message_id = :id AND message_version = 2 AND producer = 'wash-execution'
        """).param("id", messageId).query(Long.class).single()).isEqualTo(1L);
  }

  @Test
  void washEntryRejectedV1AndClassifiedV2WithDeclaredChecksRemainSupported() {
    UUID v1ExecutionId = UUID.randomUUID();
    UUID v2ExecutionId = UUID.randomUUID();
    Map<String, Object> payload = new HashMap<>();
    payload.putAll(validRejectionPayload(v1ExecutionId));
    payload.put("executionStatus", "ENTRY_REJECTED");
    payload.put("rejectionReason", "Identidad no confirmada");
    payload.put("identityConfirmed", false);
    payload.put("requirementsSatisfied", true);

    assertThat(dispatcher.dispatch(rejectionEnvelope(UUID.randomUUID(), "WashEntryRejected", 1,
        "wash-execution", MessageKind.EVENT, v1ExecutionId, payload)))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);
    payload.put("washExecutionId", v2ExecutionId.toString());
    assertThat(dispatcher.dispatch(rejectionEnvelope(UUID.randomUUID(), "WashEntryRejected", 2,
        "wash-execution", MessageKind.EVENT, v2ExecutionId, payload)))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);
    assertThat(jdbc.sql("""
        SELECT status, rejection_reason FROM wash_execution_read_model
        WHERE wash_execution_id IN (:v1Id, :v2Id) ORDER BY wash_execution_id
        """).param("v1Id", v1ExecutionId).param("v2Id", v2ExecutionId)
        .query((rs, row) -> rs.getString("status") + ":" + rs.getString("rejection_reason"))
        .list()).containsExactly(
            "ENTRY_REJECTED:Identidad no confirmada",
            "ENTRY_REJECTED:Identidad no confirmada");
  }

  @Test
  void v2RegistrationDoesNotBroadenProducerTypeKindOrPayloadTrust() {
    UUID executionId = UUID.randomUUID();
    Map<String, Object> valid = new HashMap<>();
    valid.putAll(validRejectionPayload(executionId));
    valid.put("rejectionReason", "Motivo libre");
    Map<String, Object> partialChecks = new HashMap<>(valid);
    partialChecks.put("identityConfirmed", false);
    Map<String, Object> nullChecks = new HashMap<>(valid);
    nullChecks.put("identityConfirmed", null);
    nullChecks.put("requirementsSatisfied", null);
    Map<String, Object> nonBooleanChecks = new HashMap<>(valid);
    nonBooleanChecks.put("identityConfirmed", "false");
    nonBooleanChecks.put("requirementsSatisfied", "false");
    Map<String, Object> unexpected = new HashMap<>(valid);
    unexpected.put("rawToken", "must-not-cross-the-trust-boundary");

    assertThat(dispatcher.dispatch(rejectionEnvelope(UUID.randomUUID(), "WashEntryRejected", 2,
        "intruder", MessageKind.EVENT, executionId, valid)))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.UNTRUSTED);
    assertThat(dispatcher.dispatch(rejectionEnvelope(UUID.randomUUID(), "WashEntryAuthorized", 2,
        "wash-execution", MessageKind.EVENT, executionId, valid)))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.UNTRUSTED);
    assertThat(dispatcher.dispatch(rejectionEnvelope(UUID.randomUUID(), "WashEntryRejected", 2,
        "wash-execution", MessageKind.COMMAND, executionId, valid)))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.UNTRUSTED);
    for (Map<String, Object> invalid : java.util.List.of(
        partialChecks, nullChecks, nonBooleanChecks, unexpected)) {
      assertThat(dispatcher.dispatch(rejectionEnvelope(UUID.randomUUID(), "WashEntryRejected", 2,
          "wash-execution", MessageKind.EVENT, executionId, invalid)))
          .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.UNTRUSTED);
    }

    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages").query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM wash_execution_read_model").query(Long.class).single())
        .isZero();
  }

  @Test
  void rejectedV2MissingOrContradictoryRequiredContextCannotReachInbox() {
    UUID executionId = UUID.randomUUID();
    java.util.List<Map<String, Object>> invalidPayloads = new java.util.ArrayList<>();
    for (String field : validRejectionPayload(executionId).keySet()) {
      Map<String, Object> missing = validRejectionPayload(executionId);
      missing.remove(field);
      invalidPayloads.add(missing);
      Map<String, Object> explicitNull = validRejectionPayload(executionId);
      explicitNull.put(field, null);
      invalidPayloads.add(explicitNull);
    }
    for (Object invalidReason : java.util.List.of("", "  ", 123)) {
      Map<String, Object> invalid = validRejectionPayload(executionId);
      invalid.put("rejectionReason", invalidReason);
      invalidPayloads.add(invalid);
    }
    for (Map.Entry<String, Object> invalidField : Map.<String, Object>of(
        "executionStatus", "IN_PROGRESS", "executionVersion", 3,
        "decidedAt", "not-a-time", "appointmentId", "not-a-uuid",
        "washExecutionId", UUID.randomUUID().toString()).entrySet()) {
      Map<String, Object> invalid = validRejectionPayload(executionId);
      invalid.put(invalidField.getKey(), invalidField.getValue());
      invalidPayloads.add(invalid);
    }
    for (Map<String, Object> payload : invalidPayloads) {
      assertThat(dispatcher.dispatch(rejectionEnvelope(UUID.randomUUID(), "WashEntryRejected", 2,
          "wash-execution", MessageKind.EVENT, executionId, payload)))
          .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.UNTRUSTED);
    }
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages").query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM wash_execution_read_model").query(Long.class).single())
        .isZero();
  }

  private static Map<String, Object> validRejectionPayload(UUID executionId) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("washExecutionId", executionId.toString());
    payload.put("appointmentId", UUID.randomUUID().toString());
    payload.put("entryValidationId", UUID.randomUUID().toString());
    payload.put("rejectionReason", "Motivo libre");
    payload.put("decidedByAccountId", UUID.randomUUID().toString());
    payload.put("decidedAt", now());
    payload.put("executionStatus", "ENTRY_REJECTED");
    payload.put("executionVersion", 2);
    return payload;
  }

  @Test
  void completedEventProjectsFinalMaterialsAndDuplicateMessageCannotRewriteThem() {
    UUID executionId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    Instant completedAt = Instant.parse("2026-08-23T20:00:00Z");
    Map<String, Object> original = completionPayload(executionId, completedAt, 5);

    assertThat(dispatcher.dispatch(envelope(messageId, executionId, 6, original)))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);
    Map<String, Object> mutatedDuplicate = completionPayload(executionId, completedAt, 99);
    assertThat(dispatcher.dispatch(envelope(messageId, executionId, 6, mutatedDuplicate)))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);

    var projected = jdbc.sql("""
        SELECT final_package_count, final_exit_version FROM wash_execution_read_model
        WHERE wash_execution_id = :executionId
        """).param("executionId", executionId).query().singleRow();
    assertThat(projected.get("final_package_count")).isEqualTo(5);
    assertThat(projected.get("final_exit_version")).isEqualTo(6L);
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE message_id = :messageId")
        .param("messageId", messageId).query(Long.class).single()).isEqualTo(1L);
  }

  @Test
  void originalRedeliveryBackfillsACompletedRowWhoseInboxEntryPredatesFinalProjection() {
    UUID executionId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    Instant completedAt = Instant.parse("2026-08-23T20:00:00Z");
    jdbc.sql("""
        INSERT INTO wash_execution_read_model (
          wash_execution_id, status, completed_at, execution_version, updated_at)
        VALUES (:executionId, 'COMPLETED', :completedAt, 6, :completedAt)
        """).param("executionId", executionId)
        .param("completedAt", java.sql.Timestamp.from(completedAt)).update();
    jdbc.sql("""
        INSERT INTO inbox_messages (
          consumer_name, message_id, message_kind, message_type, message_version, producer,
          aggregate_id, aggregate_version, received_at, applied_at)
        VALUES ('bff-projection-runtime', :messageId, 'EVENT', 'WashExecutionCompleted', 1,
          'wash-execution', :executionId, 6, :completedAt, :completedAt)
        """).param("messageId", messageId).param("executionId", executionId)
        .param("completedAt", java.sql.Timestamp.from(completedAt)).update();

    assertThat(dispatcher.dispatch(envelope(
        messageId, executionId, 6, completionPayload(executionId, completedAt, 5))))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);

    var projected = jdbc.sql("""
        SELECT final_package_count, final_exit_version FROM wash_execution_read_model
        WHERE wash_execution_id = :executionId
        """).param("executionId", executionId).query().singleRow();
    assertThat(projected.get("final_package_count")).isEqualTo(5);
    assertThat(projected.get("final_exit_version")).isEqualTo(6L);
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE message_id = :messageId")
        .param("messageId", messageId).query(Long.class).single()).isEqualTo(1L);
  }

  @Test
  void nonIntegralOrNegativeFinalMaterialsAreRejectedBeforeInbox() {
    UUID executionId = UUID.randomUUID();
    Instant completedAt = Instant.parse("2026-08-23T20:00:00Z");
    Map<String, Object> decimal = completionPayload(executionId, completedAt, 5);
    decimal.put("finalPackageCount", 1.5d);
    Map<String, Object> negative = completionPayload(executionId, completedAt, 5);
    negative.put("finalPackageCount", -1);

    assertThat(dispatcher.dispatch(envelope(UUID.randomUUID(), executionId, 6, decimal)))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.UNTRUSTED);
    assertThat(dispatcher.dispatch(envelope(UUID.randomUUID(), executionId, 6, negative)))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.UNTRUSTED);
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages").query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM wash_execution_read_model").query(Long.class).single()).isZero();
  }

  @Test
  void cancelledEventAtomicallyProjectsOwnerTimestampAndReleasesActiveAssignment() {
    UUID executionId = UUID.randomUUID();
    UUID assignmentId = seedPendingExecutionWithAssignment(executionId, 5);
    UUID messageId = UUID.randomUUID();
    Instant cancelledAt = Instant.parse("2026-08-23T20:15:00Z");

    assertThat(dispatcher.dispatch(cancellationEnvelope(
        messageId, executionId, 6, cancellationPayload(executionId, 6, cancelledAt))))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);

    var execution = jdbc.sql("""
        SELECT status, execution_version, cancelled_at FROM wash_execution_read_model
        WHERE wash_execution_id = :executionId
        """).param("executionId", executionId).query().singleRow();
    assertThat(execution.get("status")).isEqualTo("CANCELLED");
    assertThat(execution.get("execution_version")).isEqualTo(6L);
    assertThat(((java.sql.Timestamp) execution.get("cancelled_at")).toInstant()).isEqualTo(cancelledAt);
    var assignment = jdbc.sql("""
        SELECT released_at, source_version FROM wash_resource_assignment_read_model
        WHERE resource_assignment_id = :assignmentId
        """).param("assignmentId", assignmentId).query().singleRow();
    assertThat(((java.sql.Timestamp) assignment.get("released_at")).toInstant()).isEqualTo(cancelledAt);
    assertThat(assignment.get("source_version")).isEqualTo(6L);
    assertThat(jdbc.sql("""
        SELECT count(*) FROM wash_resource_assignment_read_model
        WHERE wash_execution_id = :executionId AND released_at IS NULL
        """).param("executionId", executionId).query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE message_id = :messageId")
        .param("messageId", messageId).query(Long.class).single()).isEqualTo(1L);
  }

  @Test
  void cancellationWithoutAssignmentProjectsExecutionAndInboxWithoutInventingAssignment() {
    UUID executionId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    Instant cancelledAt = Instant.parse("2026-08-23T20:15:00Z");

    assertThat(dispatcher.dispatch(cancellationEnvelope(
        messageId, executionId, 2, cancellationPayload(executionId, 2, cancelledAt))))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);

    assertThat(jdbc.sql("""
        SELECT status FROM wash_execution_read_model WHERE wash_execution_id = :executionId
        """).param("executionId", executionId).query(String.class).single()).isEqualTo("CANCELLED");
    assertThat(jdbc.sql("SELECT count(*) FROM wash_resource_assignment_read_model")
        .query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE message_id = :messageId")
        .param("messageId", messageId).query(Long.class).single()).isEqualTo(1L);
  }

  @Test
  void invalidCancellationWireIsRejectedBeforeInboxAndReadModels() {
    UUID executionId = UUID.randomUUID();
    Instant cancelledAt = Instant.parse("2026-08-23T20:15:00Z");
    java.util.List<Map<String, Object>> invalidPayloads = new java.util.ArrayList<>();
    Map<String, Object> missingTimestamp = cancellationPayload(executionId, 6, cancelledAt);
    missingTimestamp.remove("cancelledAt");
    invalidPayloads.add(missingTimestamp);
    Map<String, Object> malformedTimestamp = cancellationPayload(executionId, 6, cancelledAt);
    malformedTimestamp.put("cancelledAt", "not-an-instant");
    invalidPayloads.add(malformedTimestamp);
    Map<String, Object> mismatchedVersion = cancellationPayload(executionId, 7, cancelledAt);
    invalidPayloads.add(mismatchedVersion);
    Map<String, Object> oversizedVersion = cancellationPayload(executionId, 6, cancelledAt);
    oversizedVersion.put("executionVersion", new java.math.BigInteger("9223372036854775810"));
    invalidPayloads.add(oversizedVersion);
    Map<String, Object> contradictoryStatus = cancellationPayload(executionId, 6, cancelledAt);
    contradictoryStatus.put("executionStatus", "PENDING_REASSIGNMENT");
    invalidPayloads.add(contradictoryStatus);

    for (Map<String, Object> payload : invalidPayloads) {
      assertThat(dispatcher.dispatch(cancellationEnvelope(
          UUID.randomUUID(), executionId, 6, payload)))
          .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.UNTRUSTED);
    }

    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages").query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM wash_execution_read_model").query(Long.class).single())
        .isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM wash_resource_assignment_read_model")
        .query(Long.class).single()).isZero();
  }

  @Test
  void duplicateAndHistoricalCancellationRedeliveryUseExactOwnerTimestampFence() {
    UUID executionId = UUID.randomUUID();
    UUID assignmentId = seedPendingExecutionWithAssignment(executionId, 5);
    UUID messageId = UUID.randomUUID();
    Instant cancelledAt = Instant.parse("2026-08-23T20:15:00Z");
    assertThat(dispatcher.dispatch(cancellationEnvelope(
        messageId, executionId, 6, cancellationPayload(executionId, 6, cancelledAt))))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);

    Map<String, Object> mutatedDuplicate = cancellationPayload(
        executionId, 6, cancelledAt.plusSeconds(30));
    assertThat(dispatcher.dispatch(cancellationEnvelope(
        messageId, executionId, 6, mutatedDuplicate)))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);
    assertThat(jdbc.sql("""
        SELECT released_at FROM wash_resource_assignment_read_model
        WHERE resource_assignment_id = :assignmentId
        """).param("assignmentId", assignmentId).query(Instant.class).single()).isEqualTo(cancelledAt);

    UUID historicalExecutionId = UUID.randomUUID();
    UUID historicalAssignmentId = UUID.randomUUID();
    UUID historicalMessageId = UUID.randomUUID();
    seedCancelledExecutionAndActiveAssignment(
        historicalExecutionId, historicalAssignmentId, 6, cancelledAt);
    seedCancellationInbox(historicalMessageId, historicalExecutionId, 6, cancelledAt);

    assertThat(dispatcher.dispatch(cancellationEnvelope(historicalMessageId, historicalExecutionId, 6,
        cancellationPayload(historicalExecutionId, 6, cancelledAt.plusSeconds(30)))))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);
    assertThat(jdbc.sql("""
        SELECT released_at FROM wash_resource_assignment_read_model
        WHERE resource_assignment_id = :assignmentId
        """).param("assignmentId", historicalAssignmentId).query(Instant.class).optional()).isEmpty();

    assertThat(dispatcher.dispatch(cancellationEnvelope(historicalMessageId, historicalExecutionId, 6,
        cancellationPayload(historicalExecutionId, 6, cancelledAt))))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);
    assertThat(jdbc.sql("""
        SELECT released_at, source_version FROM wash_resource_assignment_read_model
        WHERE resource_assignment_id = :assignmentId
        """).param("assignmentId", historicalAssignmentId)
        .query((rs, row) -> rs.getTimestamp("released_at").toInstant() + ":" + rs.getLong("source_version"))
        .single()).isEqualTo(cancelledAt + ":6");
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE message_id = :messageId")
        .param("messageId", historicalMessageId).query(Long.class).single()).isEqualTo(1L);
  }

  @Test
  void cancellationReleaseFailureRollsBackInboxExecutionAndAssignmentThenRedeliveryApplies() {
    UUID executionId = UUID.randomUUID();
    UUID assignmentId = seedPendingExecutionWithAssignment(executionId, 5);
    UUID messageId = UUID.randomUUID();
    Instant cancelledAt = Instant.parse("2026-08-23T20:15:00Z");
    jdbc.sql("""
        CREATE FUNCTION fail_test_cancellation_release() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.wash_execution_id = '%s'::uuid THEN
            RAISE EXCEPTION 'test cancellation release failure';
          END IF;
          RETURN NEW;
        END
        $$
        """.formatted(executionId)).update();
    jdbc.sql("""
        CREATE TRIGGER fail_test_cancellation_release_before_update
        BEFORE UPDATE ON wash_resource_assignment_read_model
        FOR EACH ROW EXECUTE FUNCTION fail_test_cancellation_release()
        """).update();
    try {
      assertThatThrownBy(() -> dispatcher.dispatch(cancellationEnvelope(
          messageId, executionId, 6, cancellationPayload(executionId, 6, cancelledAt))))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      jdbc.sql("""
          DROP TRIGGER IF EXISTS fail_test_cancellation_release_before_update
          ON wash_resource_assignment_read_model
          """).update();
      jdbc.sql("DROP FUNCTION IF EXISTS fail_test_cancellation_release()")
          .update();
    }

    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE message_id = :messageId")
        .param("messageId", messageId).query(Long.class).single()).isZero();
    assertThat(jdbc.sql("""
        SELECT status, execution_version FROM wash_execution_read_model
        WHERE wash_execution_id = :executionId
        """).param("executionId", executionId)
        .query((rs, row) -> rs.getString("status") + ":" + rs.getLong("execution_version"))
        .single()).isEqualTo("PENDING_REASSIGNMENT:5");
    assertThat(jdbc.sql("""
        SELECT released_at FROM wash_resource_assignment_read_model
        WHERE resource_assignment_id = :assignmentId
        """).param("assignmentId", assignmentId).query(Instant.class).optional()).isEmpty();

    assertThat(dispatcher.dispatch(cancellationEnvelope(
        messageId, executionId, 6, cancellationPayload(executionId, 6, cancelledAt))))
        .isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);
    assertThat(jdbc.sql("""
        SELECT released_at FROM wash_resource_assignment_read_model
        WHERE resource_assignment_id = :assignmentId
        """).param("assignmentId", assignmentId).query(Instant.class).single()).isEqualTo(cancelledAt);
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
  void canonicalSessionRevokedV1PayloadIsAccepted() {
    UUID messageId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    Instant revokedAt = Instant.parse("2026-08-23T20:00:00Z");
    ProjectionEvent event = new ProjectionEvent(
        messageId, "SessionRevoked", 1, "authentication", revokedAt,
        UUID.randomUUID(), UUID.randomUUID(), sessionId, null,
        Map.of("sessionId", sessionId.toString(), "accountId", accountId.toString(),
            "reasonCode", "USER_REQUESTED", "revokedAt", revokedAt.toString()));

    assertThat(runtime.dispatch(event)).isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(jdbc.sql("""
        SELECT reason_code FROM session_revocations
        WHERE session_id = :sessionId AND source_message_id = :messageId
        """).param("sessionId", sessionId).param("messageId", messageId)
        .query(String.class).single()).isEqualTo("USER_REQUESTED");
  }

  @Test
  void sessionRevokedWithUnexpectedFieldIsRejectedBeforeInboxOrProjection() {
    UUID messageId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    Map<String, Object> payload = new HashMap<>(Map.of(
        "sessionId", sessionId.toString(),
        "accountId", UUID.randomUUID().toString(),
        "reasonCode", "USER_REQUESTED",
        "revokedAt", "2026-08-23T20:00:00Z"));
    payload.put("unexpected", "must-not-cross-the-trust-boundary");
    ProjectionEvent event = new ProjectionEvent(
        messageId, "SessionRevoked", 1, "authentication", Instant.parse(now()),
        UUID.randomUUID(), UUID.randomUUID(), sessionId, null, payload);

    assertThat(runtime.dispatch(event)).isEqualTo(ProjectionEventRuntime.DispatchOutcome.UNTRUSTED);
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE message_id = :id")
        .param("id", messageId).query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM session_revocations WHERE session_id = :id")
        .param("id", sessionId).query(Long.class).single()).isZero();
  }

  private static Map<String, Object> completionPayload(UUID executionId, Instant completedAt, int packages) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("washExecutionId", executionId.toString());
    payload.put("executionStatus", "COMPLETED");
    payload.put("executionVersion", 6);
    payload.put("completedAt", completedAt.toString());
    payload.put("finalPackageCount", packages);
    payload.put("finalGreenPaperCassette8Count", 1);
    payload.put("finalGreenPaperCassette10Count", 2);
    payload.put("finalWitnessTapePortionCount", 3);
    return payload;
  }

  private static EventEnvelope envelope(UUID messageId, UUID executionId, int version,
      Map<String, Object> payload) {
    return new EventEnvelope(messageId, "WashExecutionCompleted", 1, MessageKind.EVENT,
        "wash-execution", Instant.parse("2026-08-23T20:00:00Z"), UUID.randomUUID(), UUID.randomUUID(),
        null, null, executionId, version, ActorContext.anonymous(),
        JsonMapper.builder().findAndAddModules().build().valueToTree(payload));
  }

  private static Map<String, Object> cancellationPayload(
      UUID executionId, int version, Instant cancelledAt) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("washExecutionId", executionId.toString());
    payload.put("executionStatus", "CANCELLED");
    payload.put("executionVersion", version);
    payload.put("cancelledAt", cancelledAt.toString());
    payload.put("cancellationType", "SUPERVISOR_DECISION");
    return payload;
  }

  private static EventEnvelope cancellationEnvelope(UUID messageId, UUID executionId, int version,
      Map<String, Object> payload) {
    return new EventEnvelope(messageId, "WashExecutionCancelled", 1, MessageKind.EVENT,
        "wash-execution", Instant.parse("2026-08-23T20:30:00Z"), UUID.randomUUID(), UUID.randomUUID(),
        null, null, executionId, version, ActorContext.anonymous(),
        JsonMapper.builder().findAndAddModules().build().valueToTree(payload));
  }

  private UUID seedPendingExecutionWithAssignment(UUID executionId, int version) {
    Instant updatedAt = Instant.parse("2026-08-23T20:00:00Z");
    jdbc.sql("""
        INSERT INTO wash_execution_read_model (
          wash_execution_id, status, authorized_at, execution_version, updated_at)
        VALUES (:executionId, 'PENDING_REASSIGNMENT', :updatedAt, :version, :updatedAt)
        """).param("executionId", executionId).param("version", version)
        .param("updatedAt", java.sql.Timestamp.from(updatedAt)).update();
    UUID assignmentId = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO wash_resource_assignment_read_model (
          resource_assignment_id, wash_execution_id, assignment_type, assigned_at,
          source_message_id, source_version, updated_at)
        VALUES (:assignmentId, :executionId, 'INITIAL', :updatedAt,
          :messageId, :version, :updatedAt)
        """).param("assignmentId", assignmentId).param("executionId", executionId)
        .param("messageId", UUID.randomUUID()).param("version", version)
        .param("updatedAt", java.sql.Timestamp.from(updatedAt)).update();
    return assignmentId;
  }

  private void seedCancelledExecutionAndActiveAssignment(
      UUID executionId, UUID assignmentId, int version, Instant cancelledAt) {
    jdbc.sql("""
        INSERT INTO wash_execution_read_model (
          wash_execution_id, status, cancelled_at, execution_version, updated_at)
        VALUES (:executionId, 'CANCELLED', :cancelledAt, :version, :cancelledAt)
        """).param("executionId", executionId).param("version", version)
        .param("cancelledAt", java.sql.Timestamp.from(cancelledAt)).update();
    jdbc.sql("""
        INSERT INTO wash_resource_assignment_read_model (
          resource_assignment_id, wash_execution_id, assignment_type, assigned_at,
          source_message_id, source_version, updated_at)
        VALUES (:assignmentId, :executionId, 'INITIAL', :assignedAt,
          :messageId, :sourceVersion, :assignedAt)
        """).param("assignmentId", assignmentId).param("executionId", executionId)
        .param("assignedAt", java.sql.Timestamp.from(cancelledAt.minusSeconds(600)))
        .param("messageId", UUID.randomUUID()).param("sourceVersion", version - 1).update();
  }

  private void seedCancellationInbox(
      UUID messageId, UUID executionId, int version, Instant cancelledAt) {
    jdbc.sql("""
        INSERT INTO inbox_messages (
          consumer_name, message_id, message_kind, message_type, message_version, producer,
          aggregate_id, aggregate_version, received_at, applied_at)
        VALUES ('bff-projection-runtime', :messageId, 'EVENT', 'WashExecutionCancelled', 1,
          'wash-execution', :executionId, :version, :cancelledAt, :cancelledAt)
        """).param("messageId", messageId).param("executionId", executionId)
        .param("version", version).param("cancelledAt", java.sql.Timestamp.from(cancelledAt)).update();
  }

  private static EventEnvelope rejectionEnvelope(UUID messageId, String messageType,
      int messageVersion, String producer, MessageKind messageKind, UUID executionId,
      Map<String, Object> payload) {
    return new EventEnvelope(messageId, messageType, messageVersion, messageKind, producer,
        Instant.parse(now()), UUID.randomUUID(), UUID.randomUUID(), null, null, executionId, 2,
        ActorContext.account(UUID.randomUUID(), "SYSTEM_PERSONNEL", "LAVADO_ULTRASONICO"),
        JsonMapper.builder().findAndAddModules().build().valueToTree(payload));
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
            "loginIdentifier", "202257019", "status", "ACTIVE", "updatedAt", now())));
    runtime.dispatch(event("AccountPresentationUpdated", "authentication", UUID.randomUUID(), accountId, 1,
        Map.of("accountId", accountId.toString(), "personId", personId.toString(), "loginType", "MATRICULA",
            "loginIdentifier", "stale", "status", "INACTIVE", "updatedAt", now())));

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

  private static ProjectionEvent appointmentEvent(String type, UUID messageId, UUID appointmentId,
      int version, String qrRepresentation) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("appointmentId", appointmentId.toString());
    payload.put("appointmentTimeSlotId", UUID.randomUUID().toString());
    payload.put("studentAccountId", UUID.randomUUID().toString());
    payload.put("status", switch (type) {
      case "AppointmentCancelled" -> "CANCELLED";
      case "AppointmentMissed" -> "MISSED";
      case "AppointmentEntryRejected" -> "ENTRY_REJECTED";
      case "AppointmentStarted" -> "IN_PROGRESS";
      case "AppointmentCompleted" -> "COMPLETED";
      default -> "SCHEDULED";
    });
    if (qrRepresentation != null) payload.put("qrRepresentation", qrRepresentation);
    return event(type, "appointments", messageId, appointmentId, version, payload);
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
