package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Side-effect-free owner Queries; only durable correlated Result intents are emitted. */
@Component
public class AppointmentExecutionQueryService {
  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int MAX_PAGE_SIZE = 500;

  private final AppointmentRepository appointments;
  private final AppointmentTimeSlotRepository slots;
  private final OutboxRepository outbox;
  private final ObjectMapper mapper;
  private final Clock clock;

  public AppointmentExecutionQueryService(AppointmentRepository appointments,
      AppointmentTimeSlotRepository slots, OutboxRepository outbox, ObjectMapper mapper, Clock clock) {
    this.appointments = appointments; this.slots = slots; this.outbox = outbox;
    this.mapper = mapper; this.clock = clock;
  }

  @Transactional
  public void answer(MessageEnvelope query) {
    switch (query.messageType()) {
      case "GetAppointmentExecutionContext" -> answerOne(query);
      case "ListAppointmentExecutionContextsPage" -> answerPage(query);
      default -> throw new InvalidMessageException("unsupported Appointment Query");
    }
  }

  private void answerOne(MessageEnvelope query) {
    UUID appointmentId = requiredUuid(query.payload(), "appointmentId");
    var appointment = appointments.findById(appointmentId).orElse(null);
    if (appointment == null) {
      appendRejected(query, "GetAppointmentExecutionContextResult", appointmentId,
          "APPOINTMENT_NOT_FOUND");
      return;
    }
    var slot = slots.findById(appointment.appointmentTimeSlotId()).orElse(null);
    if (slot == null) {
      appendRejected(query, "GetAppointmentExecutionContextResult", appointmentId,
          "APPOINTMENT_TIME_SLOT_NOT_FOUND");
      return;
    }
    ObjectNode payload = mapper.createObjectNode();
    payload.put("status", "SUCCEEDED");
    ObjectNode data = mapper.createObjectNode();
    data.set("appointment", appointmentNode(appointment));
    data.set("timeSlot", slotNode(slot));
    payload.set("data", data);
    append(query, "GetAppointmentExecutionContextResult", appointmentId, appointment.version(), payload);
  }

  private void answerPage(MessageEnvelope query) {
    LocalDate serviceDate;
    try { serviceDate = LocalDate.parse(requiredText(query.payload(), "serviceDate")); }
    catch (RuntimeException invalid) {
      appendRejected(query, "ListAppointmentExecutionContextsPageResult", null, "INVALID_SERVICE_DATE");
      return;
    }
    int pageSize = query.payload().has("pageSize") ? query.payload().path("pageSize").asInt(-1) : DEFAULT_PAGE_SIZE;
    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      appendRejected(query, "ListAppointmentExecutionContextsPageResult", null, "INVALID_PAGE_SIZE");
      return;
    }
    UUID after;
    try { after = decodeToken(optionalText(query.payload(), "pageToken")); }
    catch (IllegalArgumentException invalid) {
      appendRejected(query, "ListAppointmentExecutionContextsPageResult", null, "INVALID_PAGE_TOKEN");
      return;
    }

    List<com.estoma.appointments.domain.model.Appointment> rows =
        appointments.findForServiceDatePage(serviceDate, after, pageSize + 1);
    boolean hasMore = rows.size() > pageSize;
    if (hasMore) rows = new ArrayList<>(rows.subList(0, pageSize));

    ArrayNode contexts = mapper.createArrayNode();
    for (var appointment : rows) {
      var slot = slots.findById(appointment.appointmentTimeSlotId())
          .orElseThrow(() -> new IllegalStateException(
              "Appointment " + appointment.appointmentId() + " has no owned time slot"));
      ObjectNode context = mapper.createObjectNode();
      context.set("appointment", appointmentNode(appointment));
      context.set("timeSlot", slotNode(slot));
      contexts.add(context);
    }

    ObjectNode payload = mapper.createObjectNode();
    payload.put("status", "SUCCEEDED");
    ObjectNode data = mapper.createObjectNode();
    data.put("snapshotGeneratedAt", clock.instant().toString());
    data.put("serviceDate", serviceDate.toString());
    data.set("contexts", contexts);
    if (hasMore && !rows.isEmpty()) data.put("nextPageToken", encodeToken(rows.getLast().appointmentId()));
    payload.set("data", data);
    append(query, "ListAppointmentExecutionContextsPageResult", null, null, payload);
  }

  private ObjectNode appointmentNode(com.estoma.appointments.domain.model.Appointment a) {
    ObjectNode node = mapper.createObjectNode();
    node.put("appointmentId", a.appointmentId().toString());
    node.put("studentAccountId", a.studentAccountId().toString());
    node.put("appointmentTimeSlotId", a.appointmentTimeSlotId().toString());
    node.put("courseSectionId", a.courseSectionId().toString());
    node.put("appointmentType", a.appointmentType().name());
    node.put("instrumentCount", a.instrumentCount());
    node.put("pieceType", a.pieceType().name());
    node.put("status", a.status().name());
    if (a.entryAuthorizedAt() != null) node.put("entryAuthorizedAt", a.entryAuthorizedAt().toString());
    node.put("version", a.version());
    return node;
  }

  private ObjectNode slotNode(com.estoma.appointments.domain.model.AppointmentTimeSlot slot) {
    ObjectNode node = mapper.createObjectNode();
    node.put("appointmentTimeSlotId", slot.appointmentTimeSlotId().toString());
    node.put("serviceDate", slot.serviceDate().toString());
    node.put("startsAt", slot.startsAt().toString());
    node.put("endsAt", slot.endsAt().toString());
    node.put("timezone", slot.timezone().getId());
    node.put("arrivalToleranceUntil", slot.arrivalToleranceUntil().toString());
    node.put("status", slot.status().name());
    node.put("version", slot.version());
    return node;
  }

  private void appendRejected(MessageEnvelope query, String type, UUID aggregateId, String code) {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("status", "REJECTED");
    ObjectNode error = mapper.createObjectNode();
    error.put("code", code);
    payload.set("error", error);
    append(query, type, aggregateId, null, payload);
  }

  private void append(MessageEnvelope query, String type, UUID aggregateId, Long aggregateVersion,
      ObjectNode payload) {
    var now = clock.instant();
    outbox.append(new OutboxRepository.OutboxMessage(UUID.randomUUID(), type, 1, "RESULT",
        aggregateId, aggregateVersion, query.correlationId(), query.messageId(), mapper.writeValueAsString(payload),
        now, now, "PENDING", 0, null, null, query.operationId()));
  }

  static String encodeToken(UUID appointmentId) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        appointmentId.toString().getBytes(StandardCharsets.UTF_8));
  }

  static UUID decodeToken(String token) {
    if (token == null) return null;
    try {
      return UUID.fromString(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid pageToken", invalid);
    }
  }

  private static UUID requiredUuid(tools.jackson.databind.JsonNode node, String field) {
    try { return UUID.fromString(requiredText(node, field)); }
    catch (RuntimeException invalid) { throw new InvalidMessageException(field + " must be UUID", invalid); }
  }
  private static String requiredText(tools.jackson.databind.JsonNode node, String field) {
    String value = optionalText(node, field);
    if (value == null || value.isBlank()) throw new InvalidMessageException(field + " is required");
    return value;
  }
  private static String optionalText(tools.jackson.databind.JsonNode node, String field) {
    return !node.has(field) || node.path(field).isNull() || !node.path(field).isTextual()
        ? null : node.path(field).asText();
  }
}
