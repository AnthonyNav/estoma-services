package com.estoma.washexecution.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.application.command.*;
import com.estoma.washexecution.application.port.WashExecutionCommandUseCase;
import com.estoma.washexecution.contracts.*;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.inbound.EventInboxProcessor;
import com.estoma.washexecution.infrastructure.messaging.inbound.RecoveryResultInboxProcessor;
import com.estoma.washexecution.infrastructure.messaging.recovery.ProjectionRecoveryCoordinator;
import com.estoma.washexecution.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MissingAppointmentSlotRecoveryIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-09-09T19:00:00Z");
  private static final LocalDate DATE = LocalDate.of(2026, 9, 9);
  @Autowired ProjectionRecoveryCoordinator recovery;
  @Autowired EventInboxProcessor events;
  @Autowired RecoveryResultInboxProcessor results;
  @Autowired EnvelopeJsonCodec codec;
  @Autowired WashExecutionCommandUseCase commands;

  @BeforeEach
  void readyOtherProjections() {
    for (String name : List.of("wash-administration", "authorization")) {
      jdbc.update("""
          INSERT INTO projection_recovery_state(projection_name,source_logical_component_id,
            readiness_status,updated_at) VALUES(?,?,'READY',now())
          """, name, name);
    }
  }

  @Test
  void dailyRebuildThenAlreadyAppliedScheduleRecoversOwnerContextAndAllowsArrival() {
    UUID slot = UUID.randomUUID();
    UUID appointment = UUID.randomUUID();
    Map<String, Object> slotData = slot(slot);
    Map<String, Object> appointmentData = appointment(appointment, slot);
    events.process(event("AppointmentTimeSlotCreated", slot, slotData));
    recovery.ensureBootstrap(DATE);
    assertThat(count("appointment_time_slot_projection")).isZero();
    completeQuery("ListAppointmentExecutionContextsPage", Map.of("contexts", List.of()));
    events.process(event("AppointmentScheduled", appointment, appointmentData));
    assertThat(jdbc.queryForObject("SELECT status FROM processed_messages WHERE message_type='AppointmentScheduled'", String.class))
        .isEqualTo("APPLIED");
    assertThat(arrival(appointment).errorCode()).isEqualTo("APPOINTMENT_CONTEXT_UNAVAILABLE");

    recovery.ensureBootstrap(DATE);
    recovery.ensureBootstrap(DATE);
    assertThat(entityJobs()).isEqualTo(1);
    completeQuery("GetAppointmentExecutionContext", Map.of(
        "appointment", appointmentData, "timeSlot", slotData));
    assertThat(count("appointment_time_slot_projection")).isEqualTo(1);
    assertThat(arrival(appointment).status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    recovery.ensureBootstrap(DATE);
    assertThat(entityJobs()).isEqualTo(1);
  }

  @Test
  void repairIsBoundedSkipsTerminalAppointmentsAndWaitsForFullRecovery() {
    recovery.ensureBootstrap(DATE);
    for (int i = 0; i < 12; i++) {
      UUID id = UUID.randomUUID();
      events.process(event("AppointmentScheduled", id, appointment(id, UUID.randomUUID())));
    }
    UUID terminal = UUID.randomUUID();
    events.process(event("AppointmentScheduled", terminal, appointment(terminal, UUID.randomUUID())));
    jdbc.update("UPDATE appointment_projection SET appointment_status='CANCELLED',source_version=2 WHERE appointment_id=?", terminal);
    recovery.ensureBootstrap(DATE);
    assertThat(entityJobs()).isZero();
    completeQuery("ListAppointmentExecutionContextsPage", Map.of("contexts", List.of()));
    recovery.ensureBootstrap(DATE);
    assertThat(entityJobs()).isEqualTo(10);
    recovery.ensureBootstrap(DATE);
    assertThat(entityJobs()).isEqualTo(12);
    recovery.ensureBootstrap(DATE);
    assertThat(entityJobs()).isEqualTo(12);
  }

  @Test
  void emptyOwnerResultDoesNotCreateEndlessJobsButNewProjectionCanRetry() {
    recovery.ensureBootstrap(DATE);
    completeQuery("ListAppointmentExecutionContextsPage", Map.of("contexts", List.of()));
    UUID id = UUID.randomUUID();
    events.process(event("AppointmentScheduled", id, appointment(id, UUID.randomUUID())));
    recovery.ensureBootstrap(DATE);
    completeQuery("GetAppointmentExecutionContext", Map.of());
    recovery.ensureBootstrap(DATE);
    assertThat(entityJobs()).isEqualTo(1);
    jdbc.update("UPDATE appointment_projection SET updated_at=clock_timestamp(),source_version=2 WHERE appointment_id=?", id);
    recovery.ensureBootstrap(DATE);
    assertThat(entityJobs()).isEqualTo(2);
  }

  private void completeQuery(String type, Map<String, Object> data) {
    recovery.publishPendingQueries();
    var job = jdbc.queryForMap("SELECT * FROM projection_recovery_jobs WHERE current_query_type=?", type);
    results.process(new MessageEnvelope(UUID.randomUUID(), type + "Result", 1, MessageKind.RESULT,
        "appointments", NOW, (UUID) job.get("current_correlation_id"),
        (UUID) job.get("current_query_message_id"), (UUID) job.get("current_operation_id"),
        null, null, null, new Actor("SYSTEM", null, "appointments"),
        codec.toNode(Map.of("status", "SUCCEEDED", "data", data))));
  }

  private MessageEnvelope event(String type, UUID id, Map<String, Object> payload) {
    return new MessageEnvelope(UUID.randomUUID(), type, 1, MessageKind.EVENT, "appointments", NOW,
        UUID.randomUUID(), null, UUID.randomUUID(), null, id, 1,
        new Actor("SYSTEM", null, "appointments"), codec.toNode(payload));
  }

  private Map<String, Object> appointment(UUID id, UUID slot) {
    return Map.of("appointmentId", id, "studentAccountId", UUID.randomUUID(),
        "appointmentTimeSlotId", slot, "courseSectionId", UUID.randomUUID(),
        "appointmentType", "NORMAL", "instrumentCount", 1, "pieceType", "HIGH_SPEED",
        "status", "SCHEDULED", "version", 1);
  }

  private Map<String, Object> slot(UUID id) {
    return Map.of("appointmentTimeSlotId", id, "calendarId", UUID.randomUUID(), "serviceDate", DATE,
        "startsAt", NOW, "endsAt", NOW.plusSeconds(3600), "timezone", "America/Mexico_City",
        "arrivalToleranceUntil", NOW.plusSeconds(600), "status", "ACTIVE", "version", 1);
  }

  private CommandOutcome arrival(UUID appointment) {
    UUID supervisor = UUID.randomUUID();
    jdbc.update("INSERT INTO authorization_role_projection VALUES(?,'SUPERVISOR_LAVADO',1,now())", supervisor);
    jdbc.update("INSERT INTO authorization_access_projection VALUES(?,?,'LAVADO_ULTRASONICO','ACTIVE',1,now())",
        supervisor, UUID.randomUUID());
    return commands.dispatch(new CommandRequest(new CommandMetadata(UUID.randomUUID(), UUID.randomUUID(),
        null, UUID.randomUUID(), "bff", UUID.randomUUID().toString(), ActorContext.account(supervisor)),
        new WashExecutionCommand.RegisterWashArrival(UUID.randomUUID(), appointment, NOW)));
  }

  private int entityJobs() {
    return jdbc.queryForObject("SELECT count(*) FROM projection_recovery_jobs WHERE scope='ENTITY'", Integer.class);
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }
}
