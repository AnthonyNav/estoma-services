package com.estoma.appointments.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.port.in.RecalculateAppointmentCapacityForTimeSlotUseCase;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.WashAdministrationProjectionRepository;
import com.estoma.appointments.application.usecase.RecalculateAppointmentCapacityHandler;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ScheduleChangeCapacityIT extends AbstractPostgresIT {
  private static final LocalDate DATE = LocalDate.of(2026, 8, 24); // Monday
  private static final Instant SLOT_START = Instant.parse("2026-08-24T08:00:00Z");
  private static final Instant NOW = Instant.parse("2026-08-23T15:00:00Z");

  @Autowired AppointmentRepository appointments;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired AppointmentCapacityProjectionRepository capacity;
  @Autowired WashAdministrationProjectionRepository administration;
  @Autowired RecalculateAppointmentCapacityHandler recalculate;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("""
        TRUNCATE TABLE
          pending_capacity_evaluation, outbox_messages, command_idempotency_records,
          appointments, appointment_capacity_projections, appointment_time_slots,
          resource_priority_projection, tank_projection, cabin_projection,
          administrative_closure_projection, service_schedule_projection, service_calendar_projection,
          resource_unavailability_projection, cleaning_period_projection
        CASCADE
        """).update();
  }

  @Test
  void weeklyScheduleNarrowingCancelsOnlyEligibleScheduledAppointments() {
    UUID calendarId = UUID.randomUUID();
    UUID scheduleId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID tankId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();

    administration.upsertServiceCalendar(new WashAdministrationProjectionRepository.ServiceCalendarProjection(
        calendarId, "WASH", "UTC", DATE.minusDays(10), DATE.plusDays(10), "ACTIVE", 1, NOW));
    administration.upsertServiceSchedule(new WashAdministrationProjectionRepository.ServiceScheduleProjection(
        scheduleId, calendarId, 1, LocalTime.of(8, 0), LocalTime.of(17, 0), 60,
        "ACTIVE", 1, NOW));
    administration.upsertCabin(new WashAdministrationProjectionRepository.CabinProjection(
        cabinId, "NORMAL", "ACTIVE", 1, NOW));
    administration.upsertTank(new WashAdministrationProjectionRepository.TankProjection(
        tankId, cabinId, 3, "ACTIVE", 1, NOW));

    AppointmentTimeSlot slot = AppointmentTimeSlot.create(slotId, scheduleId, calendarId, DATE,
        SLOT_START, SLOT_START.plusSeconds(3600), ZoneOffset.UTC,
        SLOT_START.minusSeconds(600), SLOT_START.minusSeconds(1200), SLOT_START.plusSeconds(600));
    slots.save(slot);
    capacity.save(new AppointmentCapacityProjectionRepository.CapacityProjection(slotId, 3, 0, 0, 1, NOW));

    Appointment eligible = scheduled(slotId, NOW.minusSeconds(300));
    Appointment entryAuthorized = scheduled(slotId, NOW.minusSeconds(200)).authorizeEntry(NOW.minusSeconds(100));
    Appointment inProgress = scheduled(slotId, NOW.minusSeconds(100)).start(NOW.minusSeconds(50));
    appointments.save(eligible);
    appointments.save(entryAuthorized);
    appointments.save(inProgress);

    // Administrative owner narrows Monday service to 10:00-14:00. The already materialized 08:00
    // slot remains an owned historical slot, but its usable capacity is now zero.
    administration.upsertServiceSchedule(new WashAdministrationProjectionRepository.ServiceScheduleProjection(
        scheduleId, calendarId, 1, LocalTime.of(10, 0), LocalTime.of(14, 0), 60,
        "ACTIVE", 2, NOW.plusSeconds(1)));

    UUID sourceEvent = UUID.randomUUID();
    CommandMetadata metadata = new CommandMetadata(UUID.randomUUID(), "schedule-change:" + sourceEvent,
        UUID.randomUUID(), sourceEvent, ActorContext.system("appointments"), NOW.plusSeconds(2));
    var result = recalculate.recalculate(new RecalculateAppointmentCapacityForTimeSlotUseCase.Command(
        metadata, sourceEvent, slotId, 1, NOW.plusSeconds(2)));

    assertThat(result.status().name()).isEqualTo("APPLIED");
    var projected = capacity.findBySlotId(slotId).orElseThrow();
    assertThat(projected.normalCapacity()).isZero();
    assertThat(projected.journeyCapacity()).isZero();
    assertThat(projected.immunocompromisedCapacity()).isZero();

    assertThat(appointments.findById(eligible.appointmentId()).orElseThrow().status().name())
        .isEqualTo("CANCELLED");
    assertThat(appointments.findById(entryAuthorized.appointmentId()).orElseThrow().status().name())
        .isEqualTo("SCHEDULED");
    assertThat(appointments.findById(entryAuthorized.appointmentId()).orElseThrow().entryAuthorizedAt())
        .isNotNull();
    assertThat(appointments.findById(inProgress.appointmentId()).orElseThrow().status().name())
        .isEqualTo("IN_PROGRESS");

    String reduced = jdbc.sql("""
        SELECT payload::text FROM outbox_messages
        WHERE message_type='AppointmentTimeSlotCapacityReduced' AND aggregate_id=:slot
        """).param("slot", slotId).query(String.class).single();
    assertThat(reduced).contains("\"affectedAppointmentsCount\": 1");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AppointmentCancelled' AND aggregate_id=:id")
        .param("id", eligible.appointmentId()).query(Long.class).single()).isEqualTo(1);
  }

  private static Appointment scheduled(UUID slotId, Instant createdAt) {
    return Appointment.scheduled(UUID.randomUUID(), slotId, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, createdAt);
  }
}
