package com.estoma.appointments.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.port.in.CancelAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.usecase.CancelAppointmentTimeSlotHandler;
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

class SlotCancellationIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-08-23T15:00:00Z");

  @Autowired AppointmentRepository appointments;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired AuthorizationProjectionRepository authorization;
  @Autowired CancelAppointmentTimeSlotHandler handler;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("""
        TRUNCATE TABLE
          appointments, appointment_time_slots, account_role_projection,
          wash_system_access_projection, command_idempotency_records, outbox_messages
        CASCADE
        """).update();
  }

  @Test
  void cancellingSlotCancelsOnlyUnprotectedScheduledAppointmentsAndPreservesReason() {
    UUID adminId = UUID.randomUUID();
    authorization.upsertAccountRole(new AuthorizationProjectionRepository.AccountRoleProjection(
        adminId, UUID.randomUUID(), "ADMINISTRADOR_LAVADO", 1, NOW, true));
    authorization.upsertSystemAccess(new AuthorizationProjectionRepository.SystemAccessProjection(
        UUID.randomUUID(), adminId, UUID.randomUUID(), "LAVADO_ULTRASONICO", "ACTIVE", 1, NOW, true));

    UUID slotId = UUID.randomUUID();
    AppointmentTimeSlot slot = slot(slotId);
    slots.save(slot);
    Appointment eligible = scheduled(slotId, NOW.minusSeconds(300));
    Appointment entryAuthorized = scheduled(slotId, NOW.minusSeconds(200)).authorizeEntry(NOW.minusSeconds(100));
    Appointment inProgress = scheduled(slotId, NOW.minusSeconds(100)).start(NOW.minusSeconds(50));
    appointments.save(eligible);
    appointments.save(entryAuthorized);
    appointments.save(inProgress);

    String reason = "administrative schedule withdrawal";
    CommandMetadata metadata = new CommandMetadata(UUID.randomUUID(), "cancel-slot:" + slotId,
        UUID.randomUUID(), null, ActorContext.account(adminId), NOW);
    var result = handler.cancel(new CancelAppointmentTimeSlotUseCase.Command(
        metadata, slotId, reason, slot.version(), NOW));

    assertThat(result.status().name()).isEqualTo("APPLIED");
    assertThat(slots.findById(slotId).orElseThrow().isCancelled()).isTrue();

    Appointment cancelled = appointments.findById(eligible.appointmentId()).orElseThrow();
    assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
    assertThat(cancelled.cancellationType().name()).isEqualTo("CLINIC");
    assertThat(cancelled.cancellationSubreason()).isEqualTo("CAPACITY_LOSS");
    assertThat(cancelled.cancellationReason()).isEqualTo(reason);
    assertThat(cancelled.cancelledByAccountId()).isEqualTo(adminId);

    assertThat(appointments.findById(entryAuthorized.appointmentId()).orElseThrow().status().name())
        .isEqualTo("SCHEDULED");
    assertThat(appointments.findById(inProgress.appointmentId()).orElseThrow().status().name())
        .isEqualTo("IN_PROGRESS");

    String appointmentEvent = jdbc.sql("""
        SELECT payload::text FROM outbox_messages
        WHERE message_type='AppointmentCancelled' AND aggregate_id=:id
        """).param("id", eligible.appointmentId()).query(String.class).single();
    assertThat(appointmentEvent).contains(reason).contains(adminId.toString());

    String slotEvent = jdbc.sql("""
        SELECT payload::text FROM outbox_messages
        WHERE message_type='AppointmentTimeSlotCancelled' AND aggregate_id=:id
        """).param("id", slotId).query(String.class).single();
    assertThat(slotEvent).contains(reason);
  }

  private static AppointmentTimeSlot slot(UUID id) {
    Instant starts = NOW.plusSeconds(3600);
    return AppointmentTimeSlot.create(id, UUID.randomUUID(), UUID.randomUUID(),
        LocalDate.ofInstant(starts, ZoneOffset.UTC), starts, starts.plusSeconds(3600), ZoneOffset.UTC,
        starts.minusSeconds(600), starts.minusSeconds(1200), starts.plusSeconds(600));
  }

  private static Appointment scheduled(UUID slotId, Instant createdAt) {
    return Appointment.scheduled(UUID.randomUUID(), slotId, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, createdAt);
  }
}
