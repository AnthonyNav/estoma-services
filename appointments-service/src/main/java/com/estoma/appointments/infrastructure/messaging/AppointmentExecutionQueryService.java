package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.port.in.AppointmentExecutionQueryUseCase;
import com.estoma.appointments.application.port.in.GetAppointmentAvailabilityUseCase;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Maps wire Queries/Results only; authoritative reads live behind the Application input port. */
@Component
public class AppointmentExecutionQueryService {
  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int MAX_PAGE_SIZE = 500;

  private final AppointmentExecutionQueryUseCase queries;
  private final GetAppointmentAvailabilityUseCase availability;
  private final OutboxRepository outbox;
  private final ObjectMapper mapper;
  private final Clock clock;

  public AppointmentExecutionQueryService(AppointmentExecutionQueryUseCase queries,
      GetAppointmentAvailabilityUseCase availability,
      OutboxRepository outbox, ObjectMapper mapper, Clock clock) {
    this.queries = queries;
    this.availability = availability;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public void answer(MessageEnvelope query) {
    switch (query.messageType()) {
      case "GetAppointmentExecutionContext" -> answerOne(query);
      case "ListAppointmentExecutionContextsPage" -> answerPage(query);
      case "GetAppointmentAvailability" -> answerAvailability(query);
      default -> throw new InvalidMessageException("unsupported Appointment Query");
    }
  }

  private void answerAvailability(MessageEnvelope query) {
    requireExactFields(query.payload(), Set.of("studentAccountId", "serviceDate",
        "appointmentType", "instrumentCount", "pieceType", "courseSectionId"));
    GetAppointmentAvailabilityUseCase.Request request;
    try {
      request = new GetAppointmentAvailabilityUseCase.Request(
          requiredUuid(query.payload(), "studentAccountId"),
          LocalDate.parse(requiredText(query.payload(), "serviceDate")),
          AppointmentType.valueOf(requiredText(query.payload(), "appointmentType")),
          requiredInt(query.payload(), "instrumentCount"),
          PieceType.valueOf(requiredText(query.payload(), "pieceType")),
          requiredUuid(query.payload(), "courseSectionId"));
    } catch (InvalidMessageException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      appendRejected(query, "GetAppointmentAvailabilityResult", null,
          "INVALID_AVAILABILITY_CONTEXT");
      return;
    }

    var snapshot = availability.get(request, clock.instant());
    UUID snapshotId = UUID.randomUUID();
    ObjectNode payload = mapper.createObjectNode();
    payload.put("status", "SUCCEEDED");
    ObjectNode data = mapper.createObjectNode();
    data.put("snapshotId", snapshotId.toString());
    data.put("snapshotGeneratedAt", snapshot.snapshotGeneratedAt().toString());
    data.put("freshUntil", snapshot.freshUntil().toString());
    // The BFF fences the Result against the exact validated wire context it submitted. Preserve
    // lexical details (for example, uppercase UUID text) instead of reserializing typed values.
    data.set("requestContext", query.payload().deepCopy());
    data.put("canSchedule", snapshot.canSchedule());
    ArrayNode blockers = mapper.createArrayNode();
    snapshot.blockingReasons().forEach(blockers::add);
    data.set("blockingReasons", blockers);
    data.put("dailyPenaltyPoints", snapshot.dailyPenaltyPoints());
    data.put("dailyCompletedAppointments", snapshot.dailyCompletedAppointments());
    data.put("exceptionalAuthorizationRequired", snapshot.exceptionalAuthorizationRequired());
    data.put("exceptionalAuthorizationAvailable", snapshot.exceptionalAuthorizationAvailable());
    if (snapshot.exceptionalAuthorizationId() == null) data.putNull("exceptionalAuthorizationId");
    else data.put("exceptionalAuthorizationId", snapshot.exceptionalAuthorizationId().toString());
    ArrayNode availableSlots = mapper.createArrayNode();
    for (var slot : snapshot.availableTimeSlots()) {
      ObjectNode node = mapper.createObjectNode();
      node.put("appointmentTimeSlotId", slot.appointmentTimeSlotId().toString());
      node.put("startsAt", slot.startsAt().toString());
      node.put("endsAt", slot.endsAt().toString());
      node.put("availableCapacity", slot.availableCapacity());
      node.put("bookingDeadlineAt", slot.bookingDeadlineAt().toString());
      availableSlots.add(node);
    }
    data.set("availableTimeSlots", availableSlots);
    payload.set("data", data);
    append(query, snapshotId, "GetAppointmentAvailabilityResult", null, null, payload);
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
    node.put("calendarId", slot.calendarId().toString());
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
    append(query, UUID.randomUUID(), type, aggregateId, aggregateVersion, payload);
  }

  private void append(MessageEnvelope query, UUID messageId, String type, UUID aggregateId,
      Long aggregateVersion, ObjectNode payload) {
    var now = clock.instant();
    outbox.append(new OutboxRepository.OutboxMessage(messageId, type, 1, "RESULT",
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

  private static int requiredInt(tools.jackson.databind.JsonNode node, String field) {
    if (!node.has(field) || !node.path(field).isIntegralNumber()
        || !node.path(field).canConvertToInt()) {
      throw new InvalidMessageException(field + " must be an integer");
    }
    return node.path(field).asInt();
  }

  private static void requireExactFields(tools.jackson.databind.JsonNode node,
      java.util.Set<String> expected) {
    java.util.Set<String> actual = new java.util.HashSet<>();
    actual.addAll(node.propertyNames());
    if (!actual.equals(expected)) {
      throw new InvalidMessageException("availability payload fields do not match V1 contract");
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
