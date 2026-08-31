package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.MessagingNames;
import com.estoma.appointments.contracts.WireActor;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
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
class BoundedContextE2EIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-08-23T15:00:00Z");
  private static final String BFF_RESULTS = "test.appointments.e2e.results.bff";
  private static final String APPOINTMENTS_EVENTS = "test.appointments.e2e.events";

  @Container
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  @DynamicPropertySource
  static void rabbitProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.addresses", RABBIT::getAmqpUrl);
    registry.add("estoma.messaging.topology-declare", () -> "true");
    registry.add("estoma.messaging.listeners-enabled", () -> "true");
    registry.add("estoma.messaging.outbox-relay-enabled", () -> "false");
    registry.add("estoma.messaging.recovery-worker-enabled", () -> "false");
    registry.add("estoma.messaging.retry1-ttl", () -> "100ms");
    registry.add("estoma.messaging.retry2-ttl", () -> "100ms");
    registry.add("estoma.qr.secret", () -> "bounded-context-e2e-secret");
  }

  @Autowired AcademicProjectionRepository academic;
  @Autowired AuthorizationProjectionRepository authorization;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired AppointmentCapacityProjectionRepository capacity;
  @Autowired AppointmentRepository appointments;
  @Autowired OutboxRelay relay;
  @Autowired EnvelopeJsonCodec codec;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcClient jdbc;
  @Autowired AmqpAdmin admin;
  @Autowired @Qualifier("requiredRouteRabbitTemplate") RabbitTemplate rabbit;

  @BeforeEach
  void cleanAndBindObservationQueues() {
    admin.declareQueue(new Queue(BFF_RESULTS, false, false, false));
    admin.declareQueue(new Queue(APPOINTMENTS_EVENTS, false, false, false));
    admin.declareBinding(new Binding(BFF_RESULTS, Binding.DestinationType.QUEUE,
        MessagingNames.RESULTS_EXCHANGE, "bff", null));
    admin.declareBinding(new Binding(APPOINTMENTS_EVENTS, Binding.DestinationType.QUEUE,
        MessagingNames.EVENTS_EXCHANGE, "appointments.#", null));
    admin.purgeQueue(BFF_RESULTS, true);
    admin.purgeQueue(APPOINTMENTS_EVENTS, true);
    admin.purgeQueue(MessagingNames.COMMAND_QUEUE, true);

    jdbc.sql("""
        TRUNCATE TABLE
          appointments, appointment_time_slots, appointment_capacity_projections,
          exceptional_appointment_authorizations,
          academic_period_projection, student_academic_profile_projection,
          course_section_projection, student_course_section_projection,
          account_role_projection, wash_system_access_projection,
          command_idempotency_records, processed_messages, outbox_messages
        CASCADE
        """).update();
  }

  @Test
  void bffScheduleCommandCommitsAppointmentThenPublishesCorrelatedResultAndEvent() throws Exception {
    UUID studentId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID courseId = UUID.randomUUID();
    seedBookableContext(studentId, slotId, courseId);

    UUID correlationId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String idempotencyKey = "schedule:" + appointmentId;
    MessageEnvelope command = scheduleCommand(
        UUID.randomUUID(), correlationId, operationId, idempotencyKey,
        appointmentId, studentId, slotId, courseId);

    sendCommand(command);
    awaitAppointment(appointmentId);
    awaitOutboxCount(appointmentId, 2);
    relay.relay();

    MessageEnvelope result = receive(BFF_RESULTS, 5_000);
    MessageEnvelope event = receive(APPOINTMENTS_EVENTS, 5_000);
    assertThat(result.messageType()).isEqualTo("ScheduleAppointmentSucceeded");
    assertThat(result.messageKind()).isEqualTo(MessageKind.RESULT);
    assertThat(result.operationId()).isEqualTo(operationId);
    assertThat(result.correlationId()).isEqualTo(correlationId);
    assertThat(result.causationId()).isEqualTo(command.messageId());
    assertThat(result.payload().path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(result.payload().path("data").path("aggregateId").asString())
        .isEqualTo(appointmentId.toString());

    assertThat(event.messageType()).isEqualTo("AppointmentScheduled");
    assertThat(event.messageKind()).isEqualTo(MessageKind.EVENT);
    assertThat(event.aggregateId()).isEqualTo(appointmentId);
    assertThat(event.correlationId()).isEqualTo(correlationId);
    assertThat(event.causationId()).isEqualTo(command.messageId());
    assertThat(event.payload().path("appointmentId").asString()).isEqualTo(appointmentId.toString());
    assertThat(event.payload().path("studentAccountId").asString()).isEqualTo(studentId.toString());
    assertThat(event.payload().path("courseSectionId").asString()).isEqualTo(courseId.toString());

    long initialOutbox = outboxCount(appointmentId);

    // Exact broker redelivery of the same physical message: no second Result/Event intent.
    sendCommand(command);
    awaitCommandQueueDrained();
    Thread.sleep(150);
    assertThat(outboxCount(appointmentId)).isEqualTo(initialOutbox);
    relay.relay();
    assertThat(rabbit.receive(BFF_RESULTS, 300)).isNull();
    assertThat(rabbit.receive(APPOINTMENTS_EVENTS, 300)).isNull();

    // Logical retry: a new message with the same idempotency key receives the stored outcome,
    // but the mutation and domain Event are not repeated.
    MessageEnvelope logicalRetry = scheduleCommand(
        UUID.randomUUID(), correlationId, operationId, idempotencyKey,
        appointmentId, studentId, slotId, courseId);
    sendCommand(logicalRetry);
    awaitOutboxCount(appointmentId, initialOutbox + 1);
    relay.relay();
    MessageEnvelope replayResult = receive(BFF_RESULTS, 5_000);
    assertThat(replayResult.messageType()).isEqualTo("ScheduleAppointmentSucceeded");
    assertThat(replayResult.causationId()).isEqualTo(logicalRetry.messageId());
    assertThat(replayResult.payload().path("data").path("aggregateId").asString())
        .isEqualTo(appointmentId.toString());
    assertThat(rabbit.receive(APPOINTMENTS_EVENTS, 300)).isNull();
    assertThat(jdbc.sql("SELECT count(*) FROM appointments WHERE appointment_id=:id")
        .param("id", appointmentId).query(Long.class).single()).isEqualTo(1);
  }

  private void seedBookableContext(UUID studentId, UUID slotId, UUID courseId) {
    UUID periodId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    UUID calendarId = UUID.randomUUID();
    UUID scheduleId = UUID.randomUUID();
    Instant start = NOW.plusSeconds(3600);

    academic.upsertAcademicPeriod(new AcademicProjectionRepository.AcademicPeriodProjection(
        periodId, "2026-2", "ACTIVE", 1, NOW, true));
    academic.upsertStudentProfile(new AcademicProjectionRepository.StudentAcademicProfileProjection(
        profileId, studentId, 5, "ACTIVE", 1, NOW, true));
    academic.upsertCourseSection(new AcademicProjectionRepository.CourseSectionProjection(
        courseId, periodId, "ACTIVE", 1, NOW, true));
    academic.upsertStudentCourse(new AcademicProjectionRepository.StudentCourseSectionProjection(
        profileId, studentId, courseId, periodId, NOW.minusSeconds(3600), null, 1, NOW, true));

    authorization.upsertAccountRole(new AuthorizationProjectionRepository.AccountRoleProjection(
        studentId, UUID.randomUUID(), "ALUMNO", 1, NOW, true));
    authorization.upsertSystemAccess(new AuthorizationProjectionRepository.SystemAccessProjection(
        UUID.randomUUID(), studentId, UUID.randomUUID(), "LAVADO_ULTRASONICO", "ACTIVE", 1, NOW, true));

    slots.save(AppointmentTimeSlot.create(slotId, scheduleId, calendarId,
        LocalDate.ofInstant(start, ZoneOffset.UTC), start, start.plusSeconds(3600), ZoneOffset.UTC,
        start.minusSeconds(600), start.minusSeconds(1200), start.plusSeconds(600)));
    capacity.save(new AppointmentCapacityProjectionRepository.CapacityProjection(
        slotId, 1, 0, 0, 1, NOW));
  }

  private MessageEnvelope scheduleCommand(UUID messageId, UUID correlationId, UUID operationId,
      String idempotencyKey, UUID appointmentId, UUID studentId, UUID slotId, UUID courseId) {
    var payload = mapper.createObjectNode();
    payload.put("appointmentId", appointmentId.toString());
    payload.put("studentAccountId", studentId.toString());
    payload.put("appointmentTimeSlotId", slotId.toString());
    payload.put("courseSectionId", courseId.toString());
    payload.put("appointmentType", "NORMAL");
    payload.put("instrumentCount", 1);
    payload.put("pieceType", "HIGH_SPEED");
    payload.put("regulationAccepted", true);
    return new MessageEnvelope(messageId, "ScheduleAppointment", 1, MessageKind.COMMAND,
        "bff", NOW, correlationId, null, operationId, idempotencyKey, appointmentId, null,
        new WireActor("ACCOUNT", studentId, null), payload);
  }

  private void sendCommand(MessageEnvelope command) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(command.messageId().toString());
    rabbit.send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID,
        new Message(codec.encode(command), properties));
  }

  private MessageEnvelope receive(String queue, long timeout) {
    Message raw = rabbit.receive(queue, timeout);
    assertThat(raw).as("message from " + queue).isNotNull();
    return codec.decode(codec.readTree(raw.getBody()));
  }

  private void awaitAppointment(UUID appointmentId) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (appointments.findById(appointmentId).isPresent()) return;
      Thread.sleep(25);
    }
    throw new AssertionError("timed out waiting for Appointment commit");
  }

  private void awaitOutboxCount(UUID aggregateId, long expected) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (outboxCount(aggregateId) >= expected) return;
      Thread.sleep(25);
    }
    throw new AssertionError("timed out waiting for outbox count " + expected);
  }

  private long outboxCount(UUID aggregateId) {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE aggregate_id=:id")
        .param("id", aggregateId).query(Long.class).single();
  }

  private void awaitCommandQueueDrained() throws InterruptedException {
    long deadline = System.nanoTime() + 3_000_000_000L;
    while (System.nanoTime() < deadline) {
      var info = admin.getQueueInfo(MessagingNames.COMMAND_QUEUE);
      if (info != null && info.getMessageCount() == 0) return;
      Thread.sleep(25);
    }
  }
}
