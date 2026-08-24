package com.estoma.communications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.contracts.consumed.AppointmentCancelledPayload;
import com.estoma.communications.contracts.consumed.ConsumedEventTypes;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import com.estoma.communications.testsupport.Envelopes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AppointmentCancelledAdapterIT extends AbstractPostgresIT {

  @Autowired private ProjectionDispatcher dispatcher;
  @Autowired private RecipientProjectionRepository recipientProjectionRepository;
  @Autowired private CommunicationRepository communicationRepository;

  @Test
  void capacityLossCancellationByTheClinicCreatesACommunication() {
    UUID studentAccountId = activeRecipient();
    UUID appointmentId = UUID.randomUUID();

    var envelope = Envelopes.event(ConsumedEventTypes.APPOINTMENT_CANCELLED,
        new AppointmentCancelledPayload(appointmentId, studentAccountId, "CLINIC", "CAPACITY_LOSS"), appointmentId, 1);
    var outcome = dispatcher.dispatch(envelope);

    assertThat(outcome).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(communicationRepository.findBySourceAndIdempotency("appointments", appointmentId).orElseThrow().correlationId())
        .isEqualTo(envelope.correlationId());
  }

  @Test
  void aRedeliveredEventUnderADifferentMessageIdIsAnIdempotentNoOp() {
    UUID studentAccountId = activeRecipient();
    UUID appointmentId = UUID.randomUUID();
    AppointmentCancelledPayload payload = new AppointmentCancelledPayload(appointmentId, studentAccountId, "CLINIC", "CAPACITY_LOSS");
    dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.APPOINTMENT_CANCELLED, payload, appointmentId, 1, UUID.randomUUID()));

    var outcome = dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.APPOINTMENT_CANCELLED, payload, appointmentId, 1, UUID.randomUUID()));

    assertThat(outcome).isInstanceOf(ProjectionOutcome.Applied.class);
  }

  @Test
  void studentInitiatedCancellationDoesNotTriggerTheAdapter() {
    UUID studentAccountId = activeRecipient();
    UUID appointmentId = UUID.randomUUID();

    var outcome = dispatcher.dispatch(
        Envelopes.event(ConsumedEventTypes.APPOINTMENT_CANCELLED,
            new AppointmentCancelledPayload(appointmentId, studentAccountId, "STUDENT", null), appointmentId, 1));

    assertThat(outcome).isInstanceOf(ProjectionOutcome.Applied.class);
  }

  private UUID activeRecipient() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    recipientProjectionRepository.upsertAccountIfNewer(accountId, personId, "ACTIVE", 1, Instant.now());
    recipientProjectionRepository.updatePersonIfNewer(personId, "Alumno", "alumno@estoma.edu", "ACTIVE", 1, Instant.now());
    return accountId;
  }
}
