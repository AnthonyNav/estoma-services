package com.estoma.appointments.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.port.in.MarkAppointmentAsMissedUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.appointments.application.usecase.MarkAppointmentAsMissedHandler;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.WireActor;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import com.estoma.appointments.infrastructure.messaging.WashExecutionEntityRecoveryResultProcessor;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class MissedAuthoritativeConfirmationIT extends AbstractPostgresIT {
  private static final Instant START = Instant.parse("2026-08-24T08:00:00Z");
  private static final Instant EVALUATION = START.plusSeconds(12 * 60L);

  @Autowired AppointmentRepository appointments;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired MarkAppointmentAsMissedHandler missed;
  @Autowired WashExecutionEntityRecoveryResultProcessor entityRecovery;
  @Autowired ProjectionRecoveryCoordinator recovery;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("""
        TRUNCATE TABLE
          missed_evaluation_confirmations, wash_execution_evidence_projection,
          wash_execution_transition_projection, pending_wash_execution_events,
          processed_messages, projection_recovery_state, command_idempotency_records,
          outbox_messages, appointments, appointment_time_slots
        CASCADE
        """).update();
    recovery.ready("wash-execution", 1);
  }

  @Test
  void arrivalAtPlusNineMinutesPreventsMissedEvenWhenEvidenceArrivesAfterEvaluation() {
    Fixture value = fixture();
    var first = missed.mark(missedCommand(value.appointmentId(), 1, UUID.randomUUID(), EVALUATION));
    assertThat(first.status().name()).isEqualTo("DEFERRED");
    assertThat(appointments.findById(value.appointmentId()).orElseThrow().status().name()).isEqualTo("SCHEDULED");

    UUID queryId = pendingQuery(value.appointmentId());
    assertThat(pendingQueryOperationId(value.appointmentId())).isEqualTo(queryId);
    UUID executionId = UUID.randomUUID();
    entityRecovery.process(entityResult(queryId, value.appointmentId(), executionId,
        START.plusSeconds(9 * 60L), EVALUATION.plusSeconds(1)));

    var retry = missed.mark(missedCommand(value.appointmentId(), 1, UUID.randomUUID(), EVALUATION.plusSeconds(2)));
    assertThat(retry.code()).isEqualTo("VALID_ARRIVAL_PRESENT");
    assertThat(appointments.findById(value.appointmentId()).orElseThrow().status().name()).isEqualTo("SCHEDULED");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AppointmentMissed' AND aggregate_id=:id")
        .param("id", value.appointmentId()).query(Long.class).single()).isZero();
  }

  @Test
  void authoritativeAbsenceAfterToleranceAllowsMissedOnRetry() {
    Fixture value = fixture();
    var first = missed.mark(missedCommand(value.appointmentId(), 1, UUID.randomUUID(), EVALUATION));
    assertThat(first.status().name()).isEqualTo("DEFERRED");

    UUID queryId = pendingQuery(value.appointmentId());
    entityRecovery.process(entityResult(queryId, value.appointmentId(), null, null,
        EVALUATION.plusSeconds(1)));

    var retry = missed.mark(missedCommand(value.appointmentId(), 1, UUID.randomUUID(), EVALUATION.plusSeconds(2)));
    assertThat(retry.status().name()).isEqualTo("APPLIED");
    assertThat(appointments.findById(value.appointmentId()).orElseThrow().status().name()).isEqualTo("MISSED");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AppointmentMissed' AND aggregate_id=:id")
        .param("id", value.appointmentId()).query(Long.class).single()).isEqualTo(1);
  }

  private Fixture fixture() {
    UUID slotId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    slots.save(AppointmentTimeSlot.create(slotId, UUID.randomUUID(), UUID.randomUUID(),
        LocalDate.ofInstant(START, ZoneOffset.UTC), START, START.plusSeconds(3600), ZoneOffset.UTC,
        START.minusSeconds(600), START.minusSeconds(1200), START.plusSeconds(600)));
    appointments.save(Appointment.scheduled(appointmentId, slotId, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, START.minusSeconds(3600)));
    return new Fixture(appointmentId);
  }

  private MarkAppointmentAsMissedUseCase.Command missedCommand(UUID appointmentId, long version,
      UUID messageId, Instant now) {
    CommandMetadata metadata = new CommandMetadata(messageId, appointmentId + ":" + version,
        UUID.randomUUID(), null, ActorContext.system("appointments"), now);
    return new MarkAppointmentAsMissedUseCase.Command(metadata, appointmentId, version, now);
  }

  private UUID pendingQuery(UUID appointmentId) {
    return jdbc.sql("""
        SELECT message_id FROM outbox_messages
        WHERE message_type='GetWashExecutionIntegrationContext' AND aggregate_id=:appointment
        ORDER BY occurred_at DESC LIMIT 1
        """).param("appointment", appointmentId).query(UUID.class).single();
  }

  private UUID pendingQueryOperationId(UUID appointmentId) {
    return jdbc.sql("""
        SELECT operation_id FROM outbox_messages
        WHERE message_type='GetWashExecutionIntegrationContext' AND aggregate_id=:appointment
        ORDER BY occurred_at DESC LIMIT 1
        """).param("appointment", appointmentId).query(UUID.class).single();
  }

  private MessageEnvelope entityResult(UUID queryId, UUID appointmentId, UUID executionId,
      Instant arrivedAt, Instant snapshotAt) {
    var payload = mapper.createObjectNode();
    payload.put("status", "SUCCEEDED");
    var data = mapper.createObjectNode();
    data.put("appointmentId", appointmentId.toString());
    if (executionId == null) {
      data.putNull("washExecution");
    } else {
      var execution = mapper.createObjectNode();
      execution.put("washExecutionId", executionId.toString());
      execution.put("appointmentType", "NORMAL");
      execution.put("status", "PENDING_ENTRY");
      execution.put("arrivedAt", arrivedAt.toString());
      execution.put("version", 1);
      data.set("washExecution", execution);
    }
    data.set("resourceUnavailabilities", mapper.createArrayNode());
    data.set("cleaningPeriods", mapper.createArrayNode());
    payload.set("data", data);
    return new MessageEnvelope(UUID.randomUUID(), "GetWashExecutionIntegrationContextResult", 1,
        MessageKind.RESULT, "wash-execution", snapshotAt, UUID.randomUUID(), queryId,
        null, null, null, null, new WireActor("SYSTEM", null, "wash-execution"), payload);
  }

  private record Fixture(UUID appointmentId) {}
}
