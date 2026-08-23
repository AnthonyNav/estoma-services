package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Side-effect-free owner query; only the durable Result intent is emitted. */
@Component
public class AppointmentExecutionQueryService {
  private final AppointmentRepository appointments;
  private final AppointmentTimeSlotRepository slots;
  private final OutboxRepository outbox;
  public AppointmentExecutionQueryService(AppointmentRepository appointments, AppointmentTimeSlotRepository slots, OutboxRepository outbox) {
    this.appointments = appointments; this.slots = slots; this.outbox = outbox;
  }

  @Transactional
  public void answer(MessageEnvelope query) {
    UUID appointmentId = optionalUuid(query.payload(), "appointmentId");
    var appointment = appointmentId == null ? null : appointments.findById(appointmentId).orElse(null);
    String payload = appointment == null ? "{\"found\":false}" : context(appointment, slots.findById(appointment.appointmentTimeSlotId()).orElse(null));
    Instant now = query.occurredAt();
    outbox.append(new OutboxRepository.OutboxMessage(UUID.randomUUID(), query.messageType() + "Result", 1, "RESULT",
        appointmentId, appointment == null ? null : appointment.version(), query.correlationId(), query.messageId(), payload,
        now, now, "PENDING", 0, null, null));
  }

  private static String context(com.estoma.appointments.domain.model.Appointment a,
      com.estoma.appointments.domain.model.AppointmentTimeSlot slot) {
    return "{\"found\":true,\"appointmentId\":\"" + a.appointmentId() + "\",\"studentAccountId\":\""
        + a.studentAccountId() + "\",\"slotId\":\"" + a.appointmentTimeSlotId() + "\",\"status\":\""
        + a.status() + "\",\"appointmentType\":\"" + a.appointmentType() + "\",\"entryAuthorized\":"
        + (a.entryAuthorizedAt() != null) + (slot == null ? "" : ",\"startsAt\":\"" + slot.startsAt() + "\",\"endsAt\":\"" + slot.endsAt() + "\"") + "}";
  }
  private static UUID optionalUuid(tools.jackson.databind.JsonNode node, String field) {
    if (!node.has(field) || !node.path(field).isTextual()) return null;
    return UUID.fromString(node.path(field).asText());
  }
}
