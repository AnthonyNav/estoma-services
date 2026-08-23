package com.estoma.washexecution.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.contracts.Actor;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessageKind;
import com.estoma.washexecution.contracts.MessagingNames;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.outbound.OutboxRelay;
import com.estoma.washexecution.testsupport.AbstractMessagingIT;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class IntegrationQueryMessagingIT extends AbstractMessagingIT {
  private static final Instant NOW = Instant.parse("2026-08-23T10:00:00Z");
  private static final LocalDate DATE = LocalDate.of(2026, 8, 23);

  @Autowired EnvelopeJsonCodec codec;
  @Autowired OutboxRelay relay;

  @Test
  void pointQueryReconstructsEntryAuthorizationEvidenceAndPublishesDurableResult() throws Exception {
    UUID appointmentId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    UUID supervisorId = UUID.randomUUID();
    Instant decidedAt = NOW.plusSeconds(120);
    seedExecution(executionId, appointmentId, "IN_PROGRESS", 3);
    jdbc.update("""
        INSERT INTO entry_validations(
          entry_validation_id,wash_execution_id,identity_confirmed,requirements_satisfied,decision,
          rejection_reason,decided_by_account_id,decided_at,created_at)
        VALUES(?,?,true,true,'AUTHORIZED',NULL,?,?,now())
        """, UUID.randomUUID(), executionId, supervisorId, decidedAt);

    MessageEnvelope query = query(
        "GetWashExecutionIntegrationContext",
        "appointments",
        Map.of("appointmentId", appointmentId));
    send(query);
    awaitOutbox(query.messageId());

    relay.relay();

    Message raw = rabbit.receive(APPOINTMENTS_RESULT_QUEUE, 5_000);
    assertThat(raw).isNotNull();
    MessageEnvelope result = codec.decode(raw.getBody());
    assertThat(result.messageType()).isEqualTo("GetWashExecutionIntegrationContextResult");
    assertThat(result.messageKind()).isEqualTo(MessageKind.RESULT);
    assertThat(result.causationId()).isEqualTo(query.messageId());
    assertThat(result.operationId()).isEqualTo(query.operationId());
    assertThat(result.correlationId()).isEqualTo(query.correlationId());
    assertThat(result.payload().get("status").asText()).isEqualTo("SUCCEEDED");
    JsonNode execution = result.payload().path("data").path("washExecution");
    assertThat(execution.path("appointmentId").asText()).isEqualTo(appointmentId.toString());
    assertThat(execution.path("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(Instant.parse(execution.path("entryAuthorizedAt").asText())).isEqualTo(decidedAt);
  }

  @Test
  void unauthorizedRequesterReceivesRejectedResultInsteadOfPoisoningQueryQueue() throws Exception {
    MessageEnvelope query = query(
        "GetWashExecutionIntegrationContext",
        "unknown-requester",
        Map.of("appointmentId", UUID.randomUUID()));
    send(query);
    awaitOutbox(query.messageId());

    relay.relay();

    Message raw = rabbit.receive(UNAUTHORIZED_RESULT_QUEUE, 5_000);
    assertThat(raw).isNotNull();
    MessageEnvelope result = codec.decode(raw.getBody());
    assertThat(result.payload().get("status").asText()).isEqualTo("REJECTED");
    assertThat(result.payload().path("error").path("code").asText())
        .isEqualTo("QUERY_PRODUCER_NOT_ALLOWED");
    assertThat(rabbit.receive(MessagingNames.QUERY_DLQ, 250)).isNull();
  }

  @Test
  void fullPageIncludesActiveOperationalUnavailabilityEvenWithoutExecutionRelationship() throws Exception {
    UUID slotId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    UUID unavailabilityId = UUID.randomUUID();
    seedSlot(slotId);
    seedAppointment(appointmentId, slotId);
    seedExecution(executionId, appointmentId, "PENDING_ENTRY", 1);
    jdbc.update("""
        INSERT INTO resource_unavailabilities(
          resource_unavailability_id,detected_during_wash_execution_id,cabin_id,tank_id,cause_type,
          reason,disabled_by_account_id,disabled_at,restored_by_account_id,restored_at,resolution,
          version,created_at,updated_at)
        VALUES(?,NULL,?,NULL,'MANUAL_DISABLE','maintenance',?,?,NULL,NULL,NULL,1,now(),now())
        """, unavailabilityId, UUID.randomUUID(), UUID.randomUUID(), NOW);

    MessageEnvelope query = query(
        "ListWashExecutionIntegrationContextsPage",
        "appointments",
        Map.of("serviceDate", DATE.toString(), "pageSize", 50));
    send(query);
    awaitOutbox(query.messageId());
    relay.relay();

    Message raw = rabbit.receive(APPOINTMENTS_RESULT_QUEUE, 5_000);
    assertThat(raw).isNotNull();
    MessageEnvelope result = codec.decode(raw.getBody());
    JsonNode data = result.payload().path("data");
    assertThat(data.path("executions").size()).isEqualTo(1);
    assertThat(data.path("resourceUnavailabilities").size()).isEqualTo(1);
    assertThat(data.path("resourceUnavailabilities").get(0)
        .path("resourceUnavailabilityId").asText()).isEqualTo(unavailabilityId.toString());
  }

  @Test
  void malformedPageTokenReturnsStableRejectedResult() throws Exception {
    MessageEnvelope query = query(
        "ListWashExecutionIntegrationContextsPage",
        "appointments",
        Map.of("serviceDate", DATE.toString(), "pageToken", "not-a-uuid"));
    send(query);
    awaitOutbox(query.messageId());
    relay.relay();

    Message raw = rabbit.receive(APPOINTMENTS_RESULT_QUEUE, 5_000);
    assertThat(raw).isNotNull();
    MessageEnvelope result = codec.decode(raw.getBody());
    assertThat(result.payload().path("status").asText()).isEqualTo("REJECTED");
    assertThat(result.payload().path("error").path("code").asText()).isEqualTo("INVALID_PAGE_TOKEN");
  }

  private MessageEnvelope query(String type, String producer, Map<String, Object> payload) {
    UUID correlation = UUID.randomUUID();
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, MessageKind.QUERY, producer, NOW,
        correlation, null, UUID.randomUUID(), null, null, null,
        new Actor("SYSTEM", null, producer), codec.toNode(payload));
  }

  private void send(MessageEnvelope query) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(query.messageId().toString());
    rabbit.send(
        MessagingNames.QUERIES_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID,
        new Message(codec.encode(query), properties));
  }

  private void awaitOutbox(UUID causationId) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      Integer count = jdbc.queryForObject(
          "SELECT count(*) FROM outbox_messages WHERE causation_id=? AND message_kind='RESULT'",
          Integer.class, causationId);
      if (count != null && count == 1) return;
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for Query Result Outbox");
  }

  private void seedExecution(UUID executionId, UUID appointmentId, String status, int version) {
    jdbc.update("""
        INSERT INTO wash_executions(
          wash_execution_id,appointment_id,appointment_type,status,arrived_at,version,created_at,updated_at)
        VALUES(?,?,'NORMAL',?,?,?,now(),now())
        """, executionId, appointmentId, status, NOW, version);
  }

  private void seedSlot(UUID slotId) {
    jdbc.update("""
        INSERT INTO appointment_time_slot_projection(
          appointment_time_slot_id,calendar_id,service_date,starts_at,ends_at,timezone,
          arrival_tolerance_until,status,source_version,updated_at)
        VALUES(?,?,?, ?,?,'America/Mexico_City',?,'ACTIVE',1,now())
        """, slotId, UUID.randomUUID(), DATE, NOW, NOW.plusSeconds(3600), NOW.plusSeconds(600));
  }

  private void seedAppointment(UUID appointmentId, UUID slotId) {
    jdbc.update("""
        INSERT INTO appointment_projection(
          appointment_id,student_account_id,appointment_time_slot_id,course_section_id,
          appointment_type,instrument_count,piece_type,appointment_status,entry_authorized_at,
          source_version,updated_at)
        VALUES(?,?,?,?, 'NORMAL',1,'INSTRUMENT','SCHEDULED',NULL,1,now())
        """, appointmentId, UUID.randomUUID(), slotId, UUID.randomUUID());
  }
}
