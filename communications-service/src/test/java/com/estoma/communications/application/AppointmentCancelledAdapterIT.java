package com.estoma.communications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.contracts.consumed.AcademicPeriodActivatedPayload;
import com.estoma.communications.contracts.consumed.AppointmentCancelledPayload;
import com.estoma.communications.contracts.consumed.ConsumedEventTypes;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import com.estoma.communications.testsupport.Envelopes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentCancelledAdapterIT extends AbstractPostgresIT {

  @Autowired private ProjectionDispatcher dispatcher;
  @Autowired private RecipientProjectionRepository recipientProjectionRepository;
  @Autowired private CommunicationRepository communicationRepository;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void resetAcademicPeriodProjection() {
    jdbc.sql("DELETE FROM academic_period_projections").update();
    jdbc.sql("""
        UPDATE academic_period_projection_state
        SET readiness = 'BOOTSTRAPPING', active_academic_period_id = NULL, active_source_version = NULL,
            active_source_occurred_at = NULL, recovery_query_message_id = NULL, recovery_requested_at = NULL, updated_at = now()
        WHERE state_key = 1
        """).update();
  }

  @Test
  void capacityLossCancellationByTheClinicCreatesACommunication() {
    projectActiveAcademicPeriod();
    UUID studentAccountId = activeRecipient();
    UUID appointmentId = UUID.randomUUID();

    var envelope = Envelopes.event(ConsumedEventTypes.APPOINTMENT_CANCELLED,
        new AppointmentCancelledPayload(appointmentId, studentAccountId, "CLINIC", "CAPACITY_LOSS"), appointmentId, 1);
    var outcome = dispatcher.dispatch(envelope);

    assertThat(outcome).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(communicationRepository.findBySourceAndIdempotency("appointments", appointmentId).orElseThrow().correlationId())
        .isEqualTo(envelope.correlationId());
    assertThat(communicationsFor(appointmentId)).isEqualTo(1L);
    assertThat(inboxFor(appointmentId)).isEqualTo(1L);
    assertThat(emailFor(appointmentId)).isEqualTo(1L);
    assertThat(jdbc.sql("SELECT system_code FROM communications WHERE source_service = 'appointments' AND source_reference_id = :id")
        .param("id", appointmentId.toString()).query(String.class).single()).isEqualTo("LAVADO_ULTRASONICO");
    assertThat(jdbc.sql("""
        SELECT i.expires_at FROM inbox_messages i JOIN communications c ON c.communication_id = i.communication_id
        WHERE c.source_service = 'appointments' AND c.source_reference_id = :id
        """).param("id", appointmentId.toString()).query(java.sql.Timestamp.class).single().toInstant())
        .isEqualTo(Instant.parse("2031-01-01T06:00:00Z"));
    assertThat(jdbc.sql("""
        SELECT i.body FROM inbox_messages i JOIN communications c ON c.communication_id = i.communication_id
        WHERE c.source_service = 'appointments' AND c.source_reference_id = :id
        """).param("id", appointmentId.toString()).query(String.class).single())
        .doesNotContainIgnoringCase("reprogram");
    assertThat(jdbc.sql("""
        SELECT e.text_body FROM email_deliveries e JOIN communications c ON c.communication_id = e.communication_id
        WHERE c.source_service = 'appointments' AND c.source_reference_id = :id
        """).param("id", appointmentId.toString()).query(String.class).single())
        .doesNotContainIgnoringCase("reprogram");
    assertThat(jdbc.sql("""
        SELECT requested_channels::text FROM communications
        WHERE source_service = 'appointments' AND source_reference_id = :id
        """).param("id", appointmentId.toString()).query(String.class).single()).isEqualTo("{INBOX,EMAIL}");
  }

  @Test
  void aRedeliveredEventUnderADifferentMessageIdIsAnIdempotentNoOp() {
    projectActiveAcademicPeriod();
    UUID studentAccountId = activeRecipient();
    UUID appointmentId = UUID.randomUUID();
    AppointmentCancelledPayload payload = new AppointmentCancelledPayload(appointmentId, studentAccountId, "CLINIC", "CAPACITY_LOSS");
    dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.APPOINTMENT_CANCELLED, payload, appointmentId, 1, UUID.randomUUID()));

    var outcome = dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.APPOINTMENT_CANCELLED, payload, appointmentId, 1, UUID.randomUUID()));

    assertThat(outcome).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(communicationsFor(appointmentId)).isEqualTo(1L);
    assertThat(inboxFor(appointmentId)).isEqualTo(1L);
    assertThat(emailFor(appointmentId)).isEqualTo(1L);
  }

  @Test
  void studentInitiatedCancellationDoesNotTriggerTheAdapter() {
    UUID studentAccountId = activeRecipient();
    UUID appointmentId = UUID.randomUUID();

    AppointmentCancelledPayload payload = new AppointmentCancelledPayload(appointmentId, studentAccountId, "STUDENT", null);
    var outcome = dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.APPOINTMENT_CANCELLED, payload, appointmentId, 1));
    var redelivery = dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.APPOINTMENT_CANCELLED, payload, appointmentId, 1));

    assertThat(outcome).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(redelivery).isInstanceOf(ProjectionOutcome.Applied.class);
    assertNoDurableCommunication(appointmentId);
  }

  @Test
  void wrongSubreasonAndPartialCancellationMatchDoNotCreateAnyChannel() {
    UUID studentAccountId = activeRecipient();
    UUID wrongReasonId = UUID.randomUUID();
    UUID partialMatchId = UUID.randomUUID();
    dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.APPOINTMENT_CANCELLED,
        new AppointmentCancelledPayload(wrongReasonId, studentAccountId, "CLINIC", "OTHER"), wrongReasonId, 1));
    dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.APPOINTMENT_CANCELLED,
        new AppointmentCancelledPayload(partialMatchId, studentAccountId, "STUDENT", "CAPACITY_LOSS"), partialMatchId, 1));

    assertNoDurableCommunication(wrongReasonId);
    assertNoDurableCommunication(partialMatchId);
  }

  @Test
  void malformedQualifyingEventDoesNotCreateAnyChannel() {
    UUID messageId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    MessageEnvelope malformed = new MessageEnvelope(
        messageId, ConsumedEventTypes.APPOINTMENT_CANCELLED, 1, MessageKind.EVENT, "appointments", Instant.now(),
        UUID.randomUUID(), null, null, null, appointmentId, 1, null,
        new tools.jackson.databind.ObjectMapper().createObjectNode()
            .put("cancellationType", "CLINIC").put("cancellationSubreason", "CAPACITY_LOSS"));

    assertThatThrownBy(() -> dispatcher.dispatch(malformed)).isInstanceOf(IllegalArgumentException.class);
    assertNoDurableCommunication(appointmentId);
  }

  @Test
  void qualifyingCancellationFailsClosedUntilThePeriodProjectionIsAuthoritative() {
    UUID studentAccountId = activeRecipient();
    UUID appointmentId = UUID.randomUUID();
    assertThatThrownBy(() -> dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.APPOINTMENT_CANCELLED,
        new AppointmentCancelledPayload(appointmentId, studentAccountId, "CLINIC", "CAPACITY_LOSS"), appointmentId, 1)))
        .isInstanceOf(RuntimeException.class);

    assertNoDurableCommunication(appointmentId);
  }

  private void projectActiveAcademicPeriod() {
    UUID periodId = UUID.randomUUID();
    dispatcher.dispatch(Envelopes.event(
        ConsumedEventTypes.ACADEMIC_PERIOD_ACTIVATED,
        new AcademicPeriodActivatedPayload(periodId, "2030-B", "ACTIVE", LocalDate.of(2030, 8, 1), LocalDate.of(2030, 12, 31)),
        periodId,
        1));
  }

  private void assertNoDurableCommunication(UUID appointmentId) {
    assertThat(communicationsFor(appointmentId)).isZero();
    assertThat(inboxFor(appointmentId)).isZero();
    assertThat(emailFor(appointmentId)).isZero();
  }

  private long communicationsFor(UUID appointmentId) {
    return jdbc.sql("SELECT count(*) FROM communications WHERE source_service = 'appointments' AND source_reference_id = :id")
        .param("id", appointmentId.toString()).query(Long.class).single();
  }

  private long inboxFor(UUID appointmentId) {
    return jdbc.sql("""
        SELECT count(*) FROM inbox_messages i
        JOIN communications c ON c.communication_id = i.communication_id
        WHERE c.source_service = 'appointments' AND c.source_reference_id = :id
        """).param("id", appointmentId.toString()).query(Long.class).single();
  }

  private long emailFor(UUID appointmentId) {
    return jdbc.sql("""
        SELECT count(*) FROM email_deliveries e
        JOIN communications c ON c.communication_id = e.communication_id
        WHERE c.source_service = 'appointments' AND c.source_reference_id = :id
        """).param("id", appointmentId.toString()).query(Long.class).single();
  }

  private UUID activeRecipient() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    recipientProjectionRepository.upsertAccountIfNewer(accountId, personId, "ACTIVE", 1, Instant.now());
    recipientProjectionRepository.updatePersonIfNewer(personId, "Alumno", "alumno@estoma.edu", "ACTIVE", 1, Instant.now());
    return accountId;
  }
}
