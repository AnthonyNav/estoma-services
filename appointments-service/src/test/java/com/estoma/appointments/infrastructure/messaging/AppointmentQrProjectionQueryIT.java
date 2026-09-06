package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = {
    "estoma.qr.enabled=true",
    "estoma.qr.secret=0123456789abcdef0123456789abcdef"
})
class AppointmentQrProjectionQueryIT extends AbstractPostgresIT {
  private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 9, 6);
  private static final Instant NOW = Instant.parse("2026-09-06T14:00:00Z");
  private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID TERMINAL = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Autowired AppointmentExecutionQueryService service;
  @Autowired AppointmentRepository appointments;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void clean() {
    jdbc.sql("TRUNCATE TABLE appointments, appointment_time_slots, outbox_messages CASCADE").update();
  }

  @Test
  void pagesActiveRepresentationsAndTerminalTombstonesWithVersionFence() {
    seedAppointments();
    MessageEnvelope firstQuery = query(UUID.randomUUID(), payload(SERVICE_DATE, 2, null));

    service.answer(firstQuery);

    JsonNode firstPage = resultFor(firstQuery.messageId());
    assertThat(firstPage.path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(firstPage.path("data").path("snapshotGeneratedAt").asString()).isNotBlank();
    assertThat(firstPage.path("data").path("serviceDate").asString()).isEqualTo(SERVICE_DATE.toString());
    assertThat(firstPage.path("data").path("items")).hasSize(2);
    JsonNode scheduled = firstPage.path("data").path("items").path(0);
    assertThat(scheduled.path("appointmentId").asString()).isEqualTo(FIRST.toString());
    assertThat(scheduled.path("appointmentVersion").asLong()).isEqualTo(1L);
    assertThat(scheduled.path("appointmentStatus").asString()).isEqualTo("SCHEDULED");
    assertThat(scheduled.path("entryAuthorizedAt").isNull()).isTrue();
    assertThat(scheduled.path("qrRepresentation").asString()).startsWith("v1." + FIRST + ".");
    JsonNode inProgress = firstPage.path("data").path("items").path(1);
    assertThat(inProgress.path("appointmentStatus").asString()).isEqualTo("IN_PROGRESS");
    assertThat(inProgress.path("appointmentVersion").asLong()).isEqualTo(2L);
    assertThat(inProgress.path("qrRepresentation").asString()).startsWith("v1." + SECOND + ".");

    String next = firstPage.path("data").path("nextPageToken").asString();
    MessageEnvelope secondQuery = query(UUID.randomUUID(), payload(SERVICE_DATE, 2, next));
    service.answer(secondQuery);

    JsonNode secondPage = resultFor(secondQuery.messageId());
    assertThat(secondPage.path("data").path("items")).singleElement().satisfies(item -> {
      assertThat(item.path("appointmentId").asString()).isEqualTo(TERMINAL.toString());
      assertThat(item.path("appointmentVersion").asLong()).isEqualTo(2L);
      assertThat(item.path("appointmentStatus").asString()).isEqualTo("CANCELLED");
      assertThat(item.path("qrRepresentation").isNull()).isTrue();
    });
    assertThat(secondPage.path("data").has("nextPageToken")).isFalse();
  }

  @Test
  void exactReplayCreatesOneDurableResultAndDateMismatchedCursorIsRejected() {
    seedAppointments();
    MessageEnvelope original = query(UUID.randomUUID(), payload(SERVICE_DATE, 1, null));
    service.answer(original);
    JsonNode originalPayload = resultFor(original.messageId()).deepCopy();
    Appointment changed = appointments.findById(FIRST).orElseThrow().cancelByStudent(NOW.plusSeconds(30));
    assertThat(appointments.updateIfVersion(changed, 1)).isTrue();
    service.answer(original);

    assertThat(resultCountFor(original.messageId())).isEqualTo(1L);
    assertThat(resultFor(original.messageId())).isEqualTo(originalPayload);
    assertThat(originalPayload.path("data").path("items").path(0)
        .path("appointmentStatus").asString()).isEqualTo("SCHEDULED");
    String token = originalPayload.path("data").path("nextPageToken").asString();
    MessageEnvelope mismatch = query(UUID.randomUUID(),
        payload(SERVICE_DATE.plusDays(1), 1, token));

    service.answer(mismatch);

    assertThat(resultFor(mismatch.messageId()).path("error").path("code").asString())
        .isEqualTo("INVALID_PAGE_TOKEN");
  }

  @Test
  void rejectsOutOfRangeAndNonIntegralPageSizes() {
    JsonNode[] payloads = {
        mapper.createObjectNode().put("serviceDate", SERVICE_DATE.toString()).put("pageSize", 0),
        mapper.createObjectNode().put("serviceDate", SERVICE_DATE.toString()).put("pageSize", 101),
        mapper.createObjectNode().put("serviceDate", SERVICE_DATE.toString()).put("pageSize", 1.5)
    };
    for (JsonNode payload : payloads) {
      MessageEnvelope invalid = query(UUID.randomUUID(), payload);
      service.answer(invalid);
      assertThat(resultFor(invalid.messageId()).path("error").path("code").asString())
          .isEqualTo("INVALID_PAGE_SIZE");
    }
  }

  @Test
  void payloadAndRequesterAreStrict() {
    MessageEnvelope valid = query(UUID.randomUUID(), payload(SERVICE_DATE, 100, null));
    QueryListener.validateRequester(valid);

    assertThatThrownBy(() -> QueryListener.validateRequester(copyRequester(valid, "wash-execution",
        new WireActor("SYSTEM", null, "wash-execution"))))
        .isInstanceOf(InvalidMessageException.class);
    assertThatThrownBy(() -> QueryListener.validateRequester(copyRequester(valid, "bff",
        new WireActor("SYSTEM", UUID.randomUUID(), "bff"))))
        .isInstanceOf(InvalidMessageException.class);
    assertThatThrownBy(() -> QueryListener.validateRequester(new MessageEnvelope(
        valid.messageId(), valid.messageType(), valid.messageVersion(), valid.messageKind(),
        valid.producer(), valid.occurredAt(), valid.correlationId(), valid.causationId(),
        valid.operationId(), "forbidden", valid.aggregateId(), valid.aggregateVersion(),
        valid.actor(), valid.payload())))
        .isInstanceOf(InvalidMessageException.class);
    assertThatThrownBy(() -> QueryListener.validateRequester(new MessageEnvelope(
        valid.messageId(), valid.messageType(), valid.messageVersion(), valid.messageKind(),
        valid.producer(), valid.occurredAt(), valid.correlationId(), valid.causationId(),
        valid.operationId(), valid.idempotencyKey(), UUID.randomUUID(), 1,
        valid.actor(), valid.payload())))
        .isInstanceOf(InvalidMessageException.class);

    JsonNode extra = payload(SERVICE_DATE, 10, null).put("unexpected", true);
    assertThatThrownBy(() -> service.answer(query(UUID.randomUUID(), extra)))
        .isInstanceOf(InvalidMessageException.class);
  }

  private void seedAppointments() {
    UUID slotId = UUID.randomUUID();
    slots.save(AppointmentTimeSlot.create(slotId, UUID.randomUUID(), UUID.randomUUID(), SERVICE_DATE,
        NOW.plusSeconds(3600), NOW.plusSeconds(7200), ZoneOffset.UTC, NOW.plusSeconds(1800),
        NOW.plusSeconds(1200), NOW.plusSeconds(4200)));
    appointments.save(scheduled(FIRST, slotId, UUID.randomUUID()));
    appointments.save(scheduled(SECOND, slotId, UUID.randomUUID()).start(NOW.plusSeconds(10)));
    appointments.save(scheduled(TERMINAL, slotId, UUID.randomUUID()).cancelByStudent(NOW.plusSeconds(20)));
  }

  private static Appointment scheduled(UUID id, UUID slotId, UUID studentId) {
    return Appointment.scheduled(id, slotId, studentId, UUID.randomUUID(), AppointmentType.NORMAL,
        2, PieceType.HIGH_SPEED, null, NOW);
  }

  private tools.jackson.databind.node.ObjectNode payload(LocalDate date, int size, String token) {
    var payload = mapper.createObjectNode().put("serviceDate", date.toString()).put("pageSize", size);
    if (token != null) payload.put("pageToken", token);
    return payload;
  }

  private MessageEnvelope query(UUID id, JsonNode payload) {
    return new MessageEnvelope(id, "ListAppointmentQrProjectionSnapshotsPage", 1,
        MessageKind.QUERY, "bff", NOW, UUID.randomUUID(), null, null, null,
        null, null, new WireActor("SYSTEM", null, "bff"), payload);
  }

  private static MessageEnvelope copyRequester(MessageEnvelope query, String producer, WireActor actor) {
    return new MessageEnvelope(query.messageId(), query.messageType(), query.messageVersion(),
        query.messageKind(), producer, query.occurredAt(), query.correlationId(), query.causationId(),
        query.operationId(), query.idempotencyKey(), query.aggregateId(), query.aggregateVersion(),
        actor, query.payload());
  }

  private JsonNode resultFor(UUID queryId) {
    String payload = jdbc.sql("SELECT CAST(payload AS text) FROM outbox_messages "
            + "WHERE message_type='ListAppointmentQrProjectionSnapshotsPageResult' "
            + "AND causation_id=:query")
        .param("query", queryId).query(String.class).single();
    return mapper.readTree(payload);
  }

  private long resultCountFor(UUID queryId) {
    return jdbc.sql("SELECT count(*) FROM outbox_messages "
            + "WHERE message_type='ListAppointmentQrProjectionSnapshotsPageResult' "
            + "AND causation_id=:query")
        .param("query", queryId).query(Long.class).single();
  }
}
