package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.estoma.appointments.application.port.in.AppointmentExecutionQueryUseCase;
import com.estoma.appointments.application.port.in.GetAppointmentAvailabilityUseCase;
import com.estoma.appointments.application.port.out.AppointmentQrRepresentationIssuer;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.WireActor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class AppointmentQrQueryContractTest {
  @Test
  void validRecoveryQueryFailsClosedWhileProducerFeatureIsOff() {
    var ownerReads = mock(AppointmentExecutionQueryUseCase.class);
    var availability = mock(GetAppointmentAvailabilityUseCase.class);
    var outbox = mock(OutboxRepository.class);
    var issuer = mock(AppointmentQrRepresentationIssuer.class);
    when(issuer.enabled()).thenReturn(false);
    var mapper = new ObjectMapper();
    Instant now = Instant.parse("2026-09-06T14:00:00Z");
    var service = new AppointmentExecutionQueryService(ownerReads, availability, outbox, mapper,
        Clock.fixed(now, ZoneOffset.UTC), issuer);
    UUID queryId = UUID.randomUUID();
    var query = new MessageEnvelope(queryId, "ListAppointmentQrProjectionSnapshotsPage", 1,
        MessageKind.QUERY, "bff", now, UUID.randomUUID(), null, null, null, null, null,
        new WireActor("SYSTEM", null, "bff"), mapper.createObjectNode()
            .put("serviceDate", "2026-09-06").put("pageSize", 100));

    service.answer(query);

    var captor = ArgumentCaptor.forClass(OutboxRepository.OutboxMessage.class);
    verify(outbox).append(captor.capture());
    var result = captor.getValue();
    assertThat(result.messageType()).isEqualTo("ListAppointmentQrProjectionSnapshotsPageResult");
    assertThat(result.causationId()).isEqualTo(queryId);
    assertThat(mapper.readTree(result.payloadJson()).path("error").path("code").asString())
        .isEqualTo("QR_PROJECTION_DISABLED");
    verifyNoInteractions(ownerReads);
  }
}
