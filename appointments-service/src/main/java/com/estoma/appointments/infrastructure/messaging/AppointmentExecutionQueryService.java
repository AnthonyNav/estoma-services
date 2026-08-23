package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.port.in.AppointmentExecutionQueryUseCase;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Maps wire Queries/Results only; authoritative reads live behind the Application input port. */
@Component
public final class AppointmentExecutionQueryService {
  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int MAX_PAGE_SIZE = 500;

  private final AppointmentExecutionQueryUseCase queries;
  private final OutboxRepository outbox;
  private final ObjectMapper mapper;
  private final Clock clock;

  public AppointmentExecutionQueryService(AppointmentExecutionQueryUseCase queries,
      OutboxRepository outbox, ObjectMapper mapper, Clock clock) {
    this.queries = queries;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
  }

  public void answer(MessageEnvelope query) {
    switch (query.messageType()) {
      case "GetAppointmentExecutionContext" -> answerOne(query);
      case "ListAppointmentExecutionContextsPage" -> answerPage(query);
      default -> throw new InvalidMessageException("unsupported Appointment Query");
    }
  }

  private void answerOne(MessageEnvelope query) {
    UUID appointmentId = requiredUuid(query.payload(), "appointmentId");
    var result = queries.get(appointmentId, clock.instant());
    if (result.status() == AppointmentExecutionQueryUseCase.Status.NOT_FOUND) {
      appendRejected(query, "GetAppointmentExecutionContextResult", appointmentId,
          result.errorCode());
      return;
    }

    var context = result.context();
    ObjectNode payload = mapper.createObjectNode();
    payload.put("status", "SUCCEEDED");
    ObjectNode data = mapper.createObjectNode();
    data.set("appointment", appointmentNode(context.appointment()));
    data.set("timeSlot", slotNode(context.timeSlot()));
    payload.set("data", data);
    append(query, "GetAppointmentExecutionContextResult", appointmentId,
        context.appointment().version(), payload);
  }

  private void answerPage(MessageEnvelope query) {
    LocalDate serviceDate;
    try {
      serviceDate = LocalDate.parse(requiredText(query.payload(), "serviceDate"));
    } catch (RuntimeException invalid) {
      appendRejected(query, "ListAppointmentExecutionContextsPageResult", null,
          "INVALID_SERVICE_DATE");
      return;
    }

    int pageSize = query.payload().has("pageSize")
        ? query.payload().path("pageSize").asInt(-1) : DEFAULT_PAGE_SIZE;
    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      appendRejected(query, "ListAppointmentExecutionContextsPageResult", null,
          "INVALID_PAGE_SIZE");
      return;
    }

    UUID after;
    try {
      after = decodeToken(optionalText(query.payload(), "pageToken"));
    } catch (IllegalArgumentException invalid) {
      appendRejected(query, "ListAppointmentExecutionContextsPageResult", null,
          "INVALID_PAGE_TOKEN");
      return;
    }

    var result = queries.list(serviceDate, after, pageSize, clock.instant());
    ArrayNode contexts = mapper.createArrayNode();
    for (var contextValue : result.contexts()) {
      ObjectNode context = mapper.createObjectNode();
      context.set("appointment", appointmentNode(contextValue.appointment()));
      context.set("timeSlot", slotNode(contextValue.timeSlot()));
      contexts.add(context);
    }

    ObjectNode payload = mapper.createObjectNode();
    payload.put("status", "SUCCEEDED");
    ObjectNode data = mapper.createObjectNode();
    data.put("snapshotGeneratedAt", result.snapshotGeneratedAt().toString());
    data.put("serviceDate", result.serviceDate().toString());
    data.set("contexts", contexts);
    if (result.nextAfterAppointmentId() != null) {
      data.put("nextPageToken", encodeToken(result.nextAfterAppointmentId()));
    }
    payload.set("data", data);
    append(query, "ListAppointmentExecutionContextsPageResult", null, null, payload);
  }

  private ObjectNode appointmentNode(Appointment a) {
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

  private ObjectNode slotNode(AppointmentTimeSlot slot) {
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
        aggregateId, aggregateVersion, query.correlationId(), query.messageId(),
        mapper.writeValueAsString(payload), now, now, "PENDING", 0, null, null,
        query.operationId()));
  }

  static String encodeToken(UUID appointmentId) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        appointmentId.toString().getBytes(StandardCharsets.UTF_8));
  }

  static UUID decodeToken(String token) {
    if (token == null) return null;
    try {
      return UUID.fromString(new String(
          Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid pageToken", invalid);
    }
  }

  private static UUID requiredUuid(tools.jackson.databind.JsonNode node, String field) {
    try {
      return UUID.fromString(requiredText(node, field));
    } catch (RuntimeException invalid) {
      throw new InvalidMessageException(field + " must be UUID", invalid);
    }
  }

  private static String requiredText(tools.jackson.databind.JsonNode node, String field) {
    String value = optionalText(node, field);
    if (value == null || value.isBlank()) throw new InvalidMessageException(field + " is required");
    return value;
  }

  private static String optionalText(tools.jackson.databind.JsonNode node, String field) {
    return !node.has(field) || node.path(field).isNull() || !node.path(field).isTextual()
        ? null : node.path(field).asString();
  }
}
