package com.estoma.washexecution.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.contracts.Actor;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessageKind;
import com.estoma.washexecution.contracts.MessagingNames;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.inbound.DeferredEventReplayWorker;
import com.estoma.washexecution.testsupport.AbstractMessagingIT;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

class EventProjectionMessagingIT extends AbstractMessagingIT {
  private static final Instant OCCURRED = Instant.parse("2026-08-23T10:00:00Z");

  @Autowired EnvelopeJsonCodec codec;
  @Autowired DeferredEventReplayWorker replay;

  @Test
  void outOfOrderAppointmentEventIsDeferredThenReplayedAndStaleEventCannotRegressProjection()
      throws Exception {
    UUID appointmentId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID studentId = UUID.randomUUID();
    UUID courseSectionId = UUID.randomUUID();

    MessageEnvelope cancelled = event(
        "AppointmentCancelled", "appointments", appointmentId, 2,
        Map.of(
            "appointmentId", appointmentId,
            "appointmentTimeSlotId", slotId,
            "studentAccountId", studentId,
            "cancellationType", "STUDENT",
            "cancelledAt", OCCURRED));
    sendEvent("appointments.appointment.cancelled", cancelled);

    awaitInbox(cancelled.messageId(), "DEFERRED");
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM appointment_projection WHERE appointment_id=?",
        Integer.class, appointmentId)).isZero();
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM projection_recovery_jobs WHERE projection_name='appointments' AND scope='ENTITY' AND status='PENDING'",
        Integer.class)).isEqualTo(1);

    MessageEnvelope scheduled = event(
        "AppointmentScheduled", "appointments", appointmentId, 1,
        Map.of(
            "appointmentId", appointmentId,
            "appointmentTimeSlotId", slotId,
            "studentAccountId", studentId,
            "courseSectionId", courseSectionId,
            "appointmentType", "NORMAL",
            "instrumentCount", 2,
            "pieceType", "INSTRUMENT"));
    sendEvent("appointments.appointment.scheduled", scheduled);
    awaitInbox(scheduled.messageId(), "APPLIED");

    replay.replay();

    assertThat(jdbc.queryForObject(
        "SELECT appointment_status FROM appointment_projection WHERE appointment_id=?",
        String.class, appointmentId)).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject(
        "SELECT source_version FROM appointment_projection WHERE appointment_id=?",
        Long.class, appointmentId)).isEqualTo(2L);
    assertThat(jdbc.queryForObject(
        "SELECT status FROM processed_messages WHERE consumer_name='wash-execution.events' AND message_id=?",
        String.class, cancelled.messageId())).isEqualTo("APPLIED");

    MessageEnvelope stale = event(
        "AppointmentScheduled", "appointments", appointmentId, 1,
        Map.of(
            "appointmentId", appointmentId,
            "appointmentTimeSlotId", slotId,
            "studentAccountId", studentId,
            "courseSectionId", courseSectionId,
            "appointmentType", "NORMAL",
            "instrumentCount", 2,
            "pieceType", "INSTRUMENT"));
    sendEvent("appointments.appointment.scheduled", stale);
    awaitInbox(stale.messageId(), "APPLIED");

    assertThat(jdbc.queryForObject(
        "SELECT appointment_status FROM appointment_projection WHERE appointment_id=?",
        String.class, appointmentId)).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject(
        "SELECT source_version FROM appointment_projection WHERE appointment_id=?",
        Long.class, appointmentId)).isEqualTo(2L);
  }

  @Test
  void slotEventWithoutCalendarContextRemainsFailClosedAndStartsFullRecovery() throws Exception {
    UUID slotId = UUID.randomUUID();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("appointmentTimeSlotId", slotId);
    payload.put("sourceScheduleId", UUID.randomUUID());
    payload.put("serviceDate", LocalDate.of(2026, 8, 23));
    payload.put("startsAt", OCCURRED);
    payload.put("endsAt", OCCURRED.plusSeconds(3600));
    payload.put("timezone", "America/Mexico_City");
    payload.put("bookingDeadlineAt", OCCURRED.minusSeconds(3600));
    payload.put("cancellationDeadlineAt", OCCURRED.minusSeconds(1200));
    payload.put("arrivalToleranceUntil", OCCURRED.plusSeconds(600));
    payload.put("status", "ACTIVE");

    MessageEnvelope withoutCalendar = event(
        "AppointmentTimeSlotCreated", "appointments", slotId, 1, payload);
    sendEvent("appointments.appointment-time-slot.created", withoutCalendar);

    awaitInbox(withoutCalendar.messageId(), "DEFERRED");

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM appointment_time_slot_projection WHERE appointment_time_slot_id=?",
        Integer.class, slotId)).isZero();
    assertThat(jdbc.queryForObject(
        "SELECT readiness_status FROM projection_recovery_state WHERE projection_name='appointments'",
        String.class)).isEqualTo("REBUILDING");
    assertThat(jdbc.queryForObject("""
        SELECT count(*) FROM projection_recovery_jobs
        WHERE projection_name='appointments' AND scope='FULL' AND service_date=? AND status='PENDING'
        """, Integer.class, LocalDate.of(2026, 8, 23))).isEqualTo(1);
  }

  @Test
  void validAdministrativeEventIsAppliedAndExactRedeliveryIsIdempotent() throws Exception {
    UUID cabinId = UUID.randomUUID();
    MessageEnvelope registered = event(
        "CabinRegistered", "wash-administration", cabinId, 1,
        Map.of(
            "cabinId", cabinId,
            "code", "CAB-01",
            "name", "Cabina 1",
            "cabinType", "NORMAL",
            "status", "ACTIVE"));

    sendEvent("wash-administration.cabin.registered", registered);
    awaitInbox(registered.messageId(), "APPLIED");
    sendEvent("wash-administration.cabin.registered", registered);
    Thread.sleep(250);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM cabin_projection WHERE cabin_id=?",
        Integer.class, cabinId)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT source_version FROM cabin_projection WHERE cabin_id=?",
        Long.class, cabinId)).isEqualTo(1L);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM processed_messages WHERE consumer_name='wash-execution.events' AND message_id=?",
        Integer.class, registered.messageId())).isEqualTo(1);
  }

  private MessageEnvelope event(
      String type, String producer, UUID aggregateId, int aggregateVersion, Map<String, Object> payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, MessageKind.EVENT, producer, OCCURRED,
        UUID.randomUUID(), null, null, null, aggregateId, aggregateVersion,
        new Actor("SYSTEM", null, producer), codec.toNode(payload));
  }

  private void sendEvent(String routingKey, MessageEnvelope envelope) {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    props.setMessageId(envelope.messageId().toString());
    rabbit.send(
        MessagingNames.EVENTS_EXCHANGE,
        routingKey,
        new Message(codec.encode(envelope), props));
  }

  private void awaitInbox(UUID messageId, String status) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      var states = jdbc.queryForList("""
          SELECT status FROM processed_messages
          WHERE consumer_name='wash-execution.events' AND message_id=?
          """, String.class, messageId);
      if (states.size() == 1 && status.equals(states.getFirst())) return;
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for inbox status " + status);
  }
}
