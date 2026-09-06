package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.appointments.application.port.in.AppointmentExecutionQueryUseCase;
import com.estoma.appointments.application.port.in.GetAppointmentAvailabilityUseCase;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.AppointmentQrRepresentationIssuer;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.WireActor;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class AppointmentAvailabilityQueryContractTest {
  private static final Instant NOW = Instant.parse("2026-09-04T14:00:00Z");

  @Test
  void emitsVersionedFencedOwnerSnapshotForExactRequestContext() {
    var execution = mock(AppointmentExecutionQueryUseCase.class);
    var availability = mock(GetAppointmentAvailabilityUseCase.class);
    var outbox = mock(OutboxRepository.class);
    var mapper = new ObjectMapper();
    var service = new AppointmentExecutionQueryService(execution, availability, outbox, mapper,
        Clock.fixed(NOW, ZoneOffset.UTC), mock(AppointmentQrRepresentationIssuer.class));
    UUID studentId = UUID.randomUUID();
    UUID courseSectionId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    var request = new GetAppointmentAvailabilityUseCase.Request(studentId,
        LocalDate.of(2026, 9, 4), AppointmentType.NORMAL, 15, PieceType.HIGH_SPEED,
        courseSectionId);
    when(availability.get(any(), any())).thenReturn(new GetAppointmentAvailabilityUseCase.Snapshot(
        request, true, List.of(), 0, 0, false, false, null,
        List.of(new GetAppointmentAvailabilityUseCase.AvailableTimeSlot(slotId,
            NOW.plusSeconds(3600), NOW.plusSeconds(7200), 2, NOW.plusSeconds(3000))),
        NOW, NOW.plusSeconds(30)));
    UUID queryId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    var payload = mapper.createObjectNode()
        .put("studentAccountId", studentId.toString().toUpperCase(java.util.Locale.ROOT))
        .put("serviceDate", "2026-09-04")
        .put("appointmentType", "NORMAL")
        .put("instrumentCount", 15)
        .put("pieceType", "HIGH_SPEED")
        .put("courseSectionId", courseSectionId.toString().toUpperCase(java.util.Locale.ROOT));
    var query = new MessageEnvelope(queryId, "GetAppointmentAvailability", 1,
        MessageKind.QUERY, "bff", NOW, correlationId, null, null, null, null, null,
        new WireActor("SYSTEM", null, "bff"), payload);

    service.answer(query);

    var captor = ArgumentCaptor.forClass(OutboxRepository.OutboxMessage.class);
    verify(outbox).append(captor.capture());
    var result = captor.getValue();
    assertThat(result.messageType()).isEqualTo("GetAppointmentAvailabilityResult");
    assertThat(result.messageVersion()).isEqualTo(1);
    assertThat(result.messageKind()).isEqualTo("RESULT");
    assertThat(result.messageId()).isNotNull();
    assertThat(result.correlationId()).isEqualTo(correlationId);
    assertThat(result.causationId()).isEqualTo(queryId);
    assertThat(result.aggregateId()).isNull();
    assertThat(result.aggregateVersion()).isNull();
    var resultPayload = mapper.readTree(result.payloadJson());
    assertThat(resultPayload.path("status").asString()).isEqualTo("SUCCEEDED");
    var data = resultPayload.path("data");
    assertThat(data.path("snapshotId").asString()).isEqualTo(result.messageId().toString());
    assertThat(data.path("snapshotGeneratedAt").asString()).isEqualTo(NOW.toString());
    assertThat(data.path("freshUntil").asString()).isEqualTo(NOW.plusSeconds(30).toString());
    assertThat(data.path("requestContext")).isEqualTo(payload);
    assertThat(data.path("exceptionalAuthorizationId").isNull()).isTrue();
    assertThat(data.path("availableTimeSlots").path(0).path("availableCapacity").asInt())
        .isEqualTo(2);
    assertThat(OutboxRelay.routingKey(MessageKind.RESULT, result.messageType())).isEqualTo("bff");
  }

  @Test
  void bffAvailabilityQueryRequiresExactSystemRequesterAndPayload() {
    var mapper = new ObjectMapper();
    var payload = mapper.createObjectNode()
        .put("studentAccountId", UUID.randomUUID().toString())
        .put("serviceDate", "2026-09-04")
        .put("appointmentType", "NORMAL")
        .put("instrumentCount", 1)
        .put("pieceType", "HIGH_SPEED")
        .put("courseSectionId", UUID.randomUUID().toString());
    var valid = new MessageEnvelope(UUID.randomUUID(), "GetAppointmentAvailability", 1,
        MessageKind.QUERY, "bff", NOW, UUID.randomUUID(), null, null, null, null, null,
        new WireActor("SYSTEM", null, "bff"), payload);
    QueryListener.validateRequester(valid);

    assertThatThrownBy(() -> QueryListener.validateRequester(new MessageEnvelope(valid.messageId(),
        valid.messageType(), valid.messageVersion(), valid.messageKind(), "wash-execution",
        valid.occurredAt(), valid.correlationId(), valid.causationId(), valid.operationId(),
        valid.idempotencyKey(), valid.aggregateId(), valid.aggregateVersion(), valid.actor(), payload)))
        .isInstanceOf(InvalidMessageException.class);

    payload.put("unexpected", true);
    var service = new AppointmentExecutionQueryService(mock(AppointmentExecutionQueryUseCase.class),
        mock(GetAppointmentAvailabilityUseCase.class), mock(OutboxRepository.class), mapper,
        Clock.fixed(NOW, ZoneOffset.UTC), mock(AppointmentQrRepresentationIssuer.class));
    assertThatThrownBy(() -> service.answer(new MessageEnvelope(valid.messageId(), valid.messageType(),
        valid.messageVersion(), valid.messageKind(), valid.producer(), valid.occurredAt(),
        valid.correlationId(), valid.causationId(), valid.operationId(), valid.idempotencyKey(),
        valid.aggregateId(), valid.aggregateVersion(), valid.actor(), payload)))
        .isInstanceOf(InvalidMessageException.class);

    payload.remove("unexpected");
    payload.put("instrumentCount", 4_294_967_297L);
    assertThatThrownBy(() -> service.answer(new MessageEnvelope(valid.messageId(), valid.messageType(),
        valid.messageVersion(), valid.messageKind(), valid.producer(), valid.occurredAt(),
        valid.correlationId(), valid.causationId(), valid.operationId(), valid.idempotencyKey(),
        valid.aggregateId(), valid.aggregateVersion(), valid.actor(), payload)))
        .isInstanceOf(InvalidMessageException.class);
  }
}
