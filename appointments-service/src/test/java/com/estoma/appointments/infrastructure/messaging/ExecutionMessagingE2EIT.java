package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.WashExecutionEvidenceRepository;
import com.estoma.appointments.application.port.out.WashExecutionTransitionRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.MessagingNames;
import com.estoma.appointments.contracts.WireActor;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
class ExecutionMessagingE2EIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-08-23T15:00:00Z");

  @Container
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  @DynamicPropertySource
  static void rabbitProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.addresses", RABBIT::getAmqpUrl);
    registry.add("estoma.messaging.topology-declare", () -> "true");
    registry.add("estoma.messaging.listeners-enabled", () -> "true");
    registry.add("estoma.messaging.outbox-relay-enabled", () -> "false");
    registry.add("estoma.messaging.recovery-worker-enabled", () -> "false");
    registry.add("estoma.qr.secret", () -> "execution-e2e-secret");
  }

  @Autowired AppointmentRepository appointments;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired WashExecutionEvidenceRepository evidence;
  @Autowired WashExecutionTransitionRepository transitions;
  @Autowired EnvelopeJsonCodec codec;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcClient jdbc;
  @Autowired @Qualifier("requiredRouteRabbitTemplate") RabbitTemplate rabbit;

  @BeforeEach
  void clean() {
    jdbc.sql("""
        TRUNCATE TABLE
          wash_execution_evidence_projection, wash_execution_transition_projection,
          pending_wash_execution_events, processed_messages, outbox_messages,
          appointments, appointment_time_slots
        CASCADE
        """).update();
  }

  @Test
  void entryAuthorizedAndStartedWithSameExecutionVersionBothApplyExactlyOnce() throws Exception {
    UUID slotId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    UUID supervisorId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    slots.save(slot(slotId));
    appointments.save(Appointment.scheduled(appointmentId, slotId, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW.minusSeconds(300)));

    UUID authorizedMessageId = UUID.randomUUID();
    UUID startedMessageId = UUID.randomUUID();
    MessageEnvelope authorized = executionEvent(authorizedMessageId, "WashEntryAuthorized",
        executionId, appointmentId, 7, correlationId,
        "decidedAt", NOW.plusSeconds(30), "decidedByAccountId", supervisorId);
    MessageEnvelope started = executionEvent(startedMessageId, "WashExecutionStarted",
        executionId, appointmentId, 7, correlationId,
        "startedAt", NOW.plusSeconds(40), null, null);

    sendEvent(authorized);
    sendEvent(started);
    awaitApplied(appointmentId);

    var stored = appointments.findById(appointmentId).orElseThrow();
    assertThat(stored.status().name()).isEqualTo("IN_PROGRESS");
    assertThat(stored.entryAuthorizedAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(evidence.findByAppointmentId(appointmentId).orElseThrow().entryAuthorizedExecutionVersion())
        .isEqualTo(7);
    assertThat(transitions.find(appointmentId, "WashExecutionStarted").orElseThrow().executionVersion())
        .isEqualTo(7);
    assertThat(jdbc.sql("SELECT count(*) FROM processed_messages WHERE message_id IN (:a,:b) AND status='APPLIED'")
        .param("a", authorizedMessageId).param("b", startedMessageId).query(Long.class).single())
        .isEqualTo(2);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AppointmentStarted' AND aggregate_id=:id")
        .param("id", appointmentId).query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AppointmentStatusChanged'")
        .query(Long.class).single()).isZero();

    // Exact redelivery is absorbed by Inbox/evidence/transition fencing.
    long outboxBefore = jdbc.sql("SELECT count(*) FROM outbox_messages").query(Long.class).single();
    sendEvent(authorized);
    sendEvent(started);
    Thread.sleep(300);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages").query(Long.class).single())
        .isEqualTo(outboxBefore);
  }

  private MessageEnvelope executionEvent(UUID messageId, String type, UUID executionId,
      UUID appointmentId, int version, UUID correlationId,
      String timeField, Instant time, String actorField, UUID actorId) {
    var payload = mapper.createObjectNode();
    payload.put("washExecutionId", executionId.toString());
    payload.put("appointmentId", appointmentId.toString());
    payload.put(timeField, time.toString());
    if (actorField != null) payload.put(actorField, actorId.toString());
    return new MessageEnvelope(messageId, type, 1, MessageKind.EVENT, "wash-execution", NOW,
        correlationId, null, null, null, executionId, version,
        new WireActor("SYSTEM", null, "wash-execution"), payload);
  }

  private void sendEvent(MessageEnvelope envelope) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(envelope.messageId().toString());
    rabbit.send(MessagingNames.EVENTS_EXCHANGE, "wash-execution.execution.lifecycle",
        new Message(codec.encode(envelope), properties));
  }

  private void awaitApplied(UUID appointmentId) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      var value = appointments.findById(appointmentId).orElse(null);
      if (value != null && "IN_PROGRESS".equals(value.status().name())
          && value.entryAuthorizedAt() != null) return;
      Thread.sleep(25);
    }
    throw new AssertionError("timed out waiting for same-version execution events");
  }

  private static AppointmentTimeSlot slot(UUID id) {
    Instant starts = NOW.plusSeconds(3600);
    return AppointmentTimeSlot.create(id, UUID.randomUUID(), UUID.randomUUID(),
        LocalDate.ofInstant(starts, ZoneOffset.UTC), starts, starts.plusSeconds(3600), ZoneOffset.UTC,
        starts.minusSeconds(600), starts.minusSeconds(1200), starts.plusSeconds(600));
  }
}
