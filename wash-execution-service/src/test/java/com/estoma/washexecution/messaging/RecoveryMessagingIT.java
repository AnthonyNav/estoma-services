package com.estoma.washexecution.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.contracts.Actor;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessageKind;
import com.estoma.washexecution.contracts.MessagingNames;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.outbound.OutboxRelay;
import com.estoma.washexecution.infrastructure.messaging.recovery.ProjectionRecoveryCoordinator;
import com.estoma.washexecution.testsupport.AbstractMessagingIT;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

class RecoveryMessagingIT extends AbstractMessagingIT {
  private static final Instant NOW = Instant.parse("2026-08-23T10:00:00Z");
  private static final LocalDate DATE = LocalDate.of(2026, 8, 23);

  @Autowired ProjectionRecoveryCoordinator recovery;
  @Autowired OutboxRelay relay;
  @Autowired EnvelopeJsonCodec codec;

  @Test
  void appointmentsFullRecoveryRequiresCalendarContextBeforeReadiness() throws Exception {
    recovery.ensureFull("appointments", DATE);
    MessageEnvelope query = publishAndReceiveRecoveryQuery(APPOINTMENTS_QUERY_QUEUE);
    assertThat(query.messageType()).isEqualTo("ListAppointmentExecutionContextsPage");

    UUID appointmentId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID calendarId = UUID.randomUUID();
    Map<String, Object> appointment = Map.of(
        "appointmentId", appointmentId,
        "studentAccountId", UUID.randomUUID(),
        "appointmentTimeSlotId", slotId,
        "courseSectionId", UUID.randomUUID(),
        "appointmentType", "NORMAL",
        "instrumentCount", 2,
        "pieceType", "INSTRUMENT",
        "status", "SCHEDULED",
        "version", 4);
    Map<String, Object> slot = Map.of(
        "appointmentTimeSlotId", slotId,
        "calendarId", calendarId,
        "serviceDate", DATE.toString(),
        "startsAt", NOW,
        "endsAt", NOW.plusSeconds(3600),
        "timezone", "America/Mexico_City",
        "arrivalToleranceUntil", NOW.plusSeconds(600),
        "status", "ACTIVE",
        "version", 7);
    sendRecoveryResult(
        query,
        "appointments",
        Map.of(
            "status", "SUCCEEDED",
            "data", Map.of(
                "snapshotGeneratedAt", NOW,
                "serviceDate", DATE.toString(),
                "contexts", List.of(Map.of("appointment", appointment, "timeSlot", slot)))));

    awaitRecovery("appointments", "READY");
    assertThat(jdbc.queryForObject(
        "SELECT calendar_id FROM appointment_time_slot_projection WHERE appointment_time_slot_id=?",
        UUID.class, slotId)).isEqualTo(calendarId);
    assertThat(jdbc.queryForObject(
        "SELECT source_version FROM appointment_projection WHERE appointment_id=?",
        Long.class, appointmentId)).isEqualTo(4L);
  }

  @Test
  void appointmentsRecoveryMissingCalendarContextFailsClosed() throws Exception {
    recovery.ensureFull("appointments", DATE);
    MessageEnvelope query = publishAndReceiveRecoveryQuery(APPOINTMENTS_QUERY_QUEUE);
    UUID appointmentId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    Map<String, Object> appointment = Map.of(
        "appointmentId", appointmentId,
        "studentAccountId", UUID.randomUUID(),
        "appointmentTimeSlotId", slotId,
        "courseSectionId", UUID.randomUUID(),
        "appointmentType", "NORMAL",
        "instrumentCount", 1,
        "pieceType", "INSTRUMENT",
        "status", "SCHEDULED",
        "version", 1);
    Map<String, Object> slotWithoutCalendar = Map.of(
        "appointmentTimeSlotId", slotId,
        "serviceDate", DATE.toString(),
        "startsAt", NOW,
        "endsAt", NOW.plusSeconds(3600),
        "timezone", "America/Mexico_City",
        "arrivalToleranceUntil", NOW.plusSeconds(600),
        "status", "ACTIVE",
        "version", 1);

    sendRecoveryResult(
        query,
        "appointments",
        Map.of(
            "status", "SUCCEEDED",
            "data", Map.of(
                "snapshotGeneratedAt", NOW,
                "serviceDate", DATE.toString(),
                "contexts", List.of(Map.of("appointment", appointment, "timeSlot", slotWithoutCalendar)))));

    awaitRecovery("appointments", "DEGRADED");
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM appointment_projection WHERE appointment_id=?",
        Integer.class, appointmentId)).isZero();
  }

  @Test
  void washAdministrationRecoveryAppliesSnapshotBeforeReady() throws Exception {
    recovery.ensureFull("wash-administration", null);
    MessageEnvelope query = publishAndReceiveRecoveryQuery(ADMIN_QUERY_QUEUE);
    UUID cabinId = UUID.randomUUID();
    UUID tankId = UUID.randomUUID();
    UUID priorityId = UUID.randomUUID();

    sendRecoveryResult(
        query,
        "wash-administration",
        Map.of(
            "status", "SUCCEEDED",
            "data", Map.of(
                "snapshotGeneratedAt", NOW,
                "serviceCalendars", List.of(),
                "serviceSchedules", List.of(),
                "administrativeClosures", List.of(),
                "cabins", List.of(Map.of(
                    "cabinId", cabinId,
                    "code", "CAB-1",
                    "name", "Cabina 1",
                    "cabinType", "NORMAL",
                    "status", "ACTIVE",
                    "version", 2)),
                "tanks", List.of(Map.of(
                    "tankId", tankId,
                    "cabinId", cabinId,
                    "code", "TANK-1",
                    "name", "Tina 1",
                    "configuredCapacity", 3,
                    "status", "ACTIVE",
                    "version", 3)),
                "resourcePriorities", List.of(Map.of(
                    "resourcePriorityId", priorityId,
                    "appointmentType", "NORMAL",
                    "cabinType", "NORMAL",
                    "usageMode", "PREFERRED",
                    "priorityOrder", 1,
                    "status", "ACTIVE",
                    "version", 1)))));

    awaitRecovery("wash-administration", "READY");
    assertThat(jdbc.queryForObject(
        "SELECT configured_capacity FROM tank_projection WHERE tank_id=?",
        Integer.class, tankId)).isEqualTo(3);
  }

  @Test
  void authorizationRecoveryKeepsRoleAndAccessVersionsIndependent() throws Exception {
    recovery.ensureFull("authorization", null);
    MessageEnvelope query = publishAndReceiveRecoveryQuery(AUTH_QUERY_QUEUE);
    UUID accountId = UUID.randomUUID();
    UUID systemId = UUID.randomUUID();

    sendRecoveryResult(
        query,
        "authorization",
        Map.of(
            "status", "SUCCEEDED",
            "data", Map.of(
                "items", List.of(Map.of(
                    "accountId", accountId,
                    "role", Map.of("roleCode", "SUPERVISOR_LAVADO", "roleVersion", 9),
                    "systemAccesses", List.of(Map.of(
                        "systemAccessId", UUID.randomUUID(),
                        "systemId", systemId,
                        "systemCode", "LAVADO_ULTRASONICO",
                        "status", "ACTIVE",
                        "accessVersion", 12)))),
                "hasMore", false,
                "snapshotGeneratedAt", NOW)));

    awaitRecovery("authorization", "READY");
    assertThat(jdbc.queryForObject(
        "SELECT role_source_version FROM authorization_role_projection WHERE account_id=?",
        Long.class, accountId)).isEqualTo(9L);
    assertThat(jdbc.queryForObject("""
        SELECT access_source_version FROM authorization_access_projection
        WHERE account_id=? AND system_code='LAVADO_ULTRASONICO'
        """, Long.class, accountId)).isEqualTo(12L);
  }

  @Test
  void mismatchedCurrentAttemptMetadataFailsJobInsteadOfAdvancingSnapshot() throws Exception {
    recovery.ensureFull("wash-administration", null);
    MessageEnvelope query = publishAndReceiveRecoveryQuery(ADMIN_QUERY_QUEUE);
    MessageEnvelope invalid = new MessageEnvelope(
        UUID.randomUUID(),
        query.messageType() + "Result",
        1,
        MessageKind.RESULT,
        "wash-administration",
        NOW,
        UUID.randomUUID(),
        query.messageId(),
        query.operationId(),
        null,
        null,
        null,
        new Actor("SYSTEM", null, "wash-administration"),
        codec.toNode(Map.of(
            "status", "SUCCEEDED",
            "data", Map.of(
                "administrativeClosures", List.of(),
                "cabins", List.of(),
                "tanks", List.of(),
                "resourcePriorities", List.of()))));
    sendResult(invalid);

    awaitRecovery("wash-administration", "DEGRADED");
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM projection_recovery_jobs WHERE projection_name='wash-administration' AND status='FAILED'",
        Integer.class)).isEqualTo(1);
  }

  private MessageEnvelope publishAndReceiveRecoveryQuery(String tapQueue) {
    recovery.publishPendingQueries();
    relay.relay();
    Message raw = rabbit.receive(tapQueue, 5_000);
    assertThat(raw).isNotNull();
    MessageEnvelope query = codec.decode(raw.getBody());
    assertThat(query.messageKind()).isEqualTo(MessageKind.QUERY);
    assertThat(query.producer()).isEqualTo("wash-execution");
    return query;
  }

  private void sendRecoveryResult(MessageEnvelope query, String producer, Map<String, Object> payload) {
    MessageEnvelope result = new MessageEnvelope(
        UUID.randomUUID(),
        query.messageType() + "Result",
        1,
        MessageKind.RESULT,
        producer,
        NOW,
        query.correlationId(),
        query.messageId(),
        query.operationId(),
        null,
        null,
        null,
        new Actor("SYSTEM", null, producer),
        codec.toNode(payload));
    sendResult(result);
  }

  private void sendResult(MessageEnvelope result) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(result.messageId().toString());
    rabbit.send(
        MessagingNames.RESULTS_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID,
        new Message(codec.encode(result), properties));
  }

  private void awaitRecovery(String projection, String expected) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      var values = jdbc.queryForList("""
          SELECT readiness_status FROM projection_recovery_state WHERE projection_name=?
          """, String.class, projection);
      if (values.size() == 1 && expected.equals(values.getFirst())) return;
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for recovery state " + expected);
  }
}
