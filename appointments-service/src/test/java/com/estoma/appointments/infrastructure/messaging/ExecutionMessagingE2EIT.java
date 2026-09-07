package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.port.in.HandleWashEntryAuthorizedUseCase;
import com.estoma.appointments.application.port.in.HandleWashEntryRejectedUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionCancelledUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionCompletedUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionStartedUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.PendingWashExecutionEventRepository;
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
import java.time.Clock;
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
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
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
  @Autowired PendingWashExecutionEventRepository pending;
  @Autowired HandleWashEntryRejectedUseCase rejected;
  @Autowired HandleWashExecutionStartedUseCase started;
  @Autowired HandleWashExecutionCompletedUseCase completed;
  @Autowired HandleWashExecutionCancelledUseCase cancelled;
  @Autowired HandleWashEntryAuthorizedUseCase authorized;
  @Autowired EnvelopeJsonCodec codec;
  @Autowired ObjectMapper mapper;
  @Autowired Clock clock;
  @Autowired JdbcClient jdbc;
  @Autowired @Qualifier("requiredRouteRabbitTemplate") RabbitTemplate rabbit;
  @Autowired RabbitListenerEndpointRegistry listenerRegistry;

  @BeforeEach
  void clean() {
    SimpleMessageListenerContainer eventListener = (SimpleMessageListenerContainer)
        listenerRegistry.getListenerContainer("appointmentsEventConsumer");
    eventListener.stop();
    eventListener.setConcurrentConsumers(1);
    eventListener.setMaxConcurrentConsumers(1);
    eventListener.start();
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
    MessageEnvelope staleStartBarrier = executionEvent(UUID.randomUUID(), "WashExecutionStarted",
        executionId, appointmentId, 6, correlationId,
        "startedAt", NOW.plusSeconds(40), null, null);
    sendEvent(authorized);
    sendEvent(started);
    sendEvent(staleStartBarrier);
    awaitProcessed(staleStartBarrier.messageId(), "APPLIED");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages").query(Long.class).single())
        .isEqualTo(outboxBefore);
  }

  @Test
  void completionDeliveredBeforeStartConvergesOnceThroughPendingRecovery() throws Exception {
    UUID slotId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    UUID supervisorId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    slots.save(slot(slotId));
    appointments.save(Appointment.scheduled(appointmentId, slotId, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW.minusSeconds(300)));

    UUID completionMessageId = UUID.randomUUID();
    MessageEnvelope completion = executionEvent(completionMessageId, "WashExecutionCompleted",
        executionId, appointmentId, 6, correlationId,
        "completedAt", NOW.plusSeconds(90), "completedByAccountId", supervisorId);
    MessageEnvelope start = executionEvent(UUID.randomUUID(), "WashExecutionStarted",
        executionId, appointmentId, 5, correlationId,
        "startedAt", NOW.plusSeconds(40), null, null);

    sendEvent(completion);
    awaitProcessed(completionMessageId, "DEFERRED");
    assertThat(appointments.findById(appointmentId).orElseThrow().status().name())
        .isEqualTo("SCHEDULED");

    sendEvent(start);
    awaitStatus(appointmentId, "IN_PROGRESS");
    pendingRecovery().replay(NOW.plusSeconds(120));
    awaitStatus(appointmentId, "COMPLETED");

    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AppointmentCompleted' AND aggregate_id=:id")
        .param("id", appointmentId).query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM wash_execution_transition_projection WHERE wash_execution_id=:id AND event_type='WashExecutionCompleted'")
        .param("id", executionId).query(Long.class).single()).isEqualTo(1);

    MessageEnvelope staleStartBarrier = executionEvent(UUID.randomUUID(), "WashExecutionStarted",
        executionId, appointmentId, 5, correlationId,
        "startedAt", NOW.plusSeconds(40), null, null);
    sendEvent(completion);
    sendEvent(staleStartBarrier);
    awaitProcessed(staleStartBarrier.messageId(), "APPLIED");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AppointmentCompleted' AND aggregate_id=:id")
        .param("id", appointmentId).query(Long.class).single()).isEqualTo(1);
    assertThat(appointments.findById(appointmentId).orElseThrow().status().name())
        .isEqualTo("COMPLETED");
  }

  @Test
  void washEntryRejectedV2WithoutCheckFieldsAndV1BothConverge() throws Exception {
    UUID slotV2 = UUID.randomUUID();
    UUID appointmentV2 = UUID.randomUUID();
    UUID executionV2 = UUID.randomUUID();
    UUID slotV1 = UUID.randomUUID();
    UUID appointmentV1 = UUID.randomUUID();
    UUID executionV1 = UUID.randomUUID();
    UUID supervisorId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    slots.save(slot(slotV2));
    slots.save(slot(slotV1));
    appointments.save(Appointment.scheduled(appointmentV2, slotV2, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW.minusSeconds(300)));
    appointments.save(Appointment.scheduled(appointmentV1, slotV1, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW.minusSeconds(300)));

    MessageEnvelope rejectedV2 = rejectionEvent(UUID.randomUUID(), 2, executionV2, appointmentV2,
        2, correlationId, supervisorId, "Unclassified operational reason", false);
    MessageEnvelope rejectedV1 = rejectionEvent(UUID.randomUUID(), 1, executionV1, appointmentV1,
        2, correlationId, supervisorId, "Legacy requirement failed", true);

    sendEvent(rejectedV2);
    sendEvent(rejectedV1);
    awaitStatus(appointmentV2, "ENTRY_REJECTED");
    awaitStatus(appointmentV1, "ENTRY_REJECTED");

    assertThat(appointments.findById(appointmentV2).orElseThrow().rejectionReason())
        .isEqualTo("Unclassified operational reason");
    assertThat(appointments.findById(appointmentV1).orElseThrow().rejectionReason())
        .isEqualTo("Legacy requirement failed");
    assertThat(jdbc.sql("SELECT count(*) FROM processed_messages WHERE message_id IN (:v2,:v1) AND status='APPLIED'")
        .param("v2", rejectedV2.messageId()).param("v1", rejectedV1.messageId())
        .query(Long.class).single()).isEqualTo(2);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AppointmentEntryRejected' AND aggregate_id IN (:v2,:v1)")
        .param("v2", appointmentV2).param("v1", appointmentV1)
        .query(Long.class).single()).isEqualTo(2);
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

  private MessageEnvelope rejectionEvent(UUID messageId, int messageVersion, UUID executionId,
      UUID appointmentId, int aggregateVersion, UUID correlationId, UUID supervisorId,
      String reason, boolean includeLegacyChecks) {
    var payload = mapper.createObjectNode();
    payload.put("washExecutionId", executionId.toString());
    payload.put("appointmentId", appointmentId.toString());
    payload.put("decidedAt", NOW.plusSeconds(30).toString());
    payload.put("decidedByAccountId", supervisorId.toString());
    payload.put("rejectionReason", reason);
    if (includeLegacyChecks) {
      payload.put("identityConfirmed", true);
      payload.put("requirementsSatisfied", false);
    }
    return new MessageEnvelope(messageId, "WashEntryRejected", messageVersion, MessageKind.EVENT,
        "wash-execution", NOW, correlationId, null, null, null, executionId, aggregateVersion,
        new WireActor("SYSTEM", null, "wash-execution"), payload);
  }

  private void sendEvent(MessageEnvelope envelope) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(envelope.messageId().toString());
    String routingKey = MessagingNames.WASH_EXECUTION_EVENT_ROUTES.get(envelope.messageType());
    if (routingKey == null) throw new IllegalArgumentException("unsupported execution test Event");
    rabbit.send(MessagingNames.EVENTS_EXCHANGE, routingKey,
        new Message(codec.encode(envelope), properties));
  }

  private PendingExecutionRecoveryWorker pendingRecovery() {
    return new PendingExecutionRecoveryWorker(pending, rejected, started, completed, cancelled,
        authorized, codec, clock);
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

  private void awaitStatus(UUID appointmentId, String expected) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      var value = appointments.findById(appointmentId).orElse(null);
      if (value != null && expected.equals(value.status().name())) return;
      Thread.sleep(25);
    }
    throw new AssertionError("timed out waiting for Appointment status " + expected);
  }

  private void awaitProcessed(UUID messageId, String expected) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      var value = jdbc.sql("SELECT status FROM processed_messages WHERE message_id=:id")
          .param("id", messageId).query(String.class).optional();
      if (value.filter(expected::equals).isPresent()) return;
      Thread.sleep(25);
    }
    throw new AssertionError("timed out waiting for processed message status " + expected);
  }

  private static AppointmentTimeSlot slot(UUID id) {
    Instant starts = NOW.plusSeconds(3600);
    return AppointmentTimeSlot.create(id, UUID.randomUUID(), UUID.randomUUID(),
        LocalDate.ofInstant(starts, ZoneOffset.UTC), starts, starts.plusSeconds(3600), ZoneOffset.UTC,
        starts.minusSeconds(600), starts.minusSeconds(1200), starts.plusSeconds(600));
  }
}
