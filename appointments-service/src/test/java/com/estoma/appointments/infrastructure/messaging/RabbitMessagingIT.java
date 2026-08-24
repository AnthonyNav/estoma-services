package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.MessagingNames;
import com.estoma.appointments.contracts.WireActor;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import com.estoma.appointments.infrastructure.persistence.JdbcAppointmentRepository;
import com.estoma.appointments.infrastructure.persistence.JdbcAppointmentTimeSlotRepository;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
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
class RabbitMessagingIT extends AbstractPostgresIT {
  @Container
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  @DynamicPropertySource
  static void rabbitProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.addresses", RABBIT::getAmqpUrl);
    registry.add("estoma.messaging.topology-declare", () -> "true");
    registry.add("estoma.messaging.listeners-enabled", () -> "false");
    registry.add("estoma.messaging.outbox-relay-enabled", () -> "false");
    registry.add("estoma.messaging.recovery-worker-enabled", () -> "false");
    registry.add("estoma.messaging.retry1-ttl", () -> "100ms");
    registry.add("estoma.messaging.retry2-ttl", () -> "100ms");
    registry.add("estoma.qr.secret", () -> "integration-test-secret");
  }

  @Autowired AmqpAdmin admin;
  @Autowired OutboxRepository outbox;
  @Autowired OutboxRelay relay;
  @Autowired FailureRouter failures;
  @Autowired EnvelopeJsonCodec codec;
  @Autowired AppointmentExecutionQueryService queryAdapter;
  @Autowired JdbcAppointmentRepository appointments;
  @Autowired JdbcAppointmentTimeSlotRepository slots;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired @Qualifier("requiredRouteRabbitTemplate") RabbitTemplate rabbit;

  @BeforeEach
  void clean() {
    for (String queue : new String[]{
        MessagingNames.COMMAND_QUEUE, MessagingNames.QUERY_QUEUE, MessagingNames.EVENT_QUEUE,
        MessagingNames.RESULT_QUEUE, MessagingNames.COMMAND_RETRY_1, MessagingNames.COMMAND_RETRY_2,
        MessagingNames.COMMAND_DLQ, MessagingNames.QUERY_RETRY_1, MessagingNames.QUERY_RETRY_2,
        MessagingNames.QUERY_DLQ, MessagingNames.EVENT_RETRY_1, MessagingNames.EVENT_RETRY_2,
        MessagingNames.EVENT_DLQ, MessagingNames.RESULT_RETRY_1, MessagingNames.RESULT_RETRY_2,
        MessagingNames.RESULT_DLQ}) {
      admin.purgeQueue(queue, true);
    }
    jdbc.sql("DELETE FROM outbox_messages").update();
  }

  @Test
  void canonicalTopologyIsActuallyDeclared() {
    assertThat(admin.getQueueInfo(MessagingNames.COMMAND_QUEUE)).isNotNull();
    assertThat(admin.getQueueInfo(MessagingNames.QUERY_QUEUE)).isNotNull();
    assertThat(admin.getQueueInfo(MessagingNames.EVENT_QUEUE)).isNotNull();
    assertThat(admin.getQueueInfo(MessagingNames.RESULT_QUEUE)).isNotNull();
    assertThat(admin.getQueueInfo(MessagingNames.COMMAND_RETRY_1)).isNotNull();
    assertThat(admin.getQueueInfo(MessagingNames.COMMAND_RETRY_2)).isNotNull();
    assertThat(admin.getQueueInfo(MessagingNames.COMMAND_DLQ)).isNotNull();
  }

  @Test
  void confirmedOutboxRelayPublishesInternalCommandToAppointmentsRoute() {
    UUID appointmentId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    Instant now = Instant.now();
    outbox.append(new OutboxRepository.OutboxMessage(messageId, "MarkAppointmentAsMissed", 1,
        "COMMAND", appointmentId, 7L, UUID.randomUUID(), null,
        "{\"appointmentId\":\"" + appointmentId + "\",\"expectedVersion\":7}",
        now, now, "PENDING", 0, null, null));

    relay.relay();

    Message published = rabbit.receive(MessagingNames.COMMAND_QUEUE, 2_000);
    assertThat(published).isNotNull();
    var envelope = codec.decode(codec.readTree(published.getBody()));
    assertThat(envelope.messageId()).isEqualTo(messageId);
    assertThat(envelope.messageKind()).isEqualTo(MessageKind.COMMAND);
    assertThat(envelope.producer()).isEqualTo("appointments");
    assertThat(envelope.actor().type()).isEqualTo("SYSTEM");
    assertThat(envelope.actor().service()).isEqualTo("appointments");
    assertThat(envelope.idempotencyKey()).isEqualTo(appointmentId + ":7");
    assertThat(jdbc.sql("SELECT status FROM outbox_messages WHERE message_id=:id")
        .param("id", messageId).query(String.class).single()).isEqualTo("PUBLISHED");
  }

  @Test
  void ownerQueryProducesCanonicalResultForWashExecutionRoute() {
    String resultQueue = "test.results.wash-execution." + UUID.randomUUID();
    admin.declareQueue(new Queue(resultQueue, false, true, true));
    admin.declareBinding(new Binding(resultQueue, Binding.DestinationType.QUEUE,
        MessagingNames.RESULTS_EXCHANGE, "wash-execution", null));

    Instant start = Instant.parse("2026-08-23T15:00:00Z");
    UUID slotId = UUID.randomUUID();
    UUID calendarId = UUID.randomUUID();
    AppointmentTimeSlot slot = AppointmentTimeSlot.create(slotId, UUID.randomUUID(), calendarId,
        LocalDate.ofInstant(start, ZoneOffset.UTC), start, start.plusSeconds(3600), ZoneOffset.UTC,
        start.minusSeconds(600), start.minusSeconds(1200), start.plusSeconds(600));
    slots.save(slot);
    UUID appointmentId = UUID.randomUUID();
    appointments.save(Appointment.scheduled(appointmentId, slotId, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, start.minusSeconds(1800)));

    UUID queryId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    var payload = mapper.createObjectNode().put("appointmentId", appointmentId.toString());
    queryAdapter.answer(new MessageEnvelope(queryId, "GetAppointmentExecutionContext", 1,
        MessageKind.QUERY, "wash-execution", Instant.now(), correlationId, null, null, null,
        null, null, new WireActor("SYSTEM", null, "wash-execution"), payload));

    relay.relay();

    Message published = rabbit.receive(resultQueue, 2_000);
    assertThat(published).isNotNull();
    var result = codec.decode(codec.readTree(published.getBody()));
    assertThat(result.messageType()).isEqualTo("GetAppointmentExecutionContextResult");
    assertThat(result.messageKind()).isEqualTo(MessageKind.RESULT);
    assertThat(result.producer()).isEqualTo("appointments");
    assertThat(result.correlationId()).isEqualTo(correlationId);
    assertThat(result.causationId()).isEqualTo(queryId);
    assertThat(result.payload().path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(result.payload().path("data").path("appointment").path("appointmentId").asString())
        .isEqualTo(appointmentId.toString());
    assertThat(result.payload().path("data").path("timeSlot").path("appointmentTimeSlotId").asString())
        .isEqualTo(slotId.toString());
    assertThat(result.payload().path("data").path("timeSlot").path("calendarId").asString())
        .isEqualTo(calendarId.toString());
  }

  @Test
  void transientFailureUsesTwoRetriesThenDlq() {
    String marker = UUID.randomUUID().toString();
    Message initial = message(marker);

    assertThat(failures.route(initial, MessagingNames.COMMAND_QUEUE, true))
        .isEqualTo(FailureRouter.Route.RETRY);
    Message secondAttempt = rabbit.receive(MessagingNames.COMMAND_QUEUE, 2_000);
    assertThat(body(secondAttempt)).isEqualTo(marker);
    assertThat((Integer) secondAttempt.getMessageProperties().getHeader("x-appointments-attempt"))
        .isEqualTo(1);

    assertThat(failures.route(secondAttempt, MessagingNames.COMMAND_QUEUE, true))
        .isEqualTo(FailureRouter.Route.RETRY);
    Message thirdAttempt = rabbit.receive(MessagingNames.COMMAND_QUEUE, 2_000);
    assertThat(body(thirdAttempt)).isEqualTo(marker);
    assertThat((Integer) thirdAttempt.getMessageProperties().getHeader("x-appointments-attempt"))
        .isEqualTo(2);

    assertThat(failures.route(thirdAttempt, MessagingNames.COMMAND_QUEUE, true))
        .isEqualTo(FailureRouter.Route.DLQ);
    Message dead = rabbit.receive(MessagingNames.COMMAND_DLQ, 2_000);
    assertThat(body(dead)).isEqualTo(marker);
    assertThat((Integer) dead.getMessageProperties().getHeader("x-appointments-attempt")).isEqualTo(3);
  }

  private static Message message(String body) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
  }

  private static String body(Message message) {
    assertThat(message).isNotNull();
    return new String(message.getBody(), StandardCharsets.UTF_8);
  }
}
