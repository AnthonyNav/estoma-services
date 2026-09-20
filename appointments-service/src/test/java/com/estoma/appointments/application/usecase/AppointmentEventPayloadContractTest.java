package com.estoma.appointments.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.CancellationType;
import com.estoma.appointments.domain.model.ExceptionalAppointmentAuthorization;
import com.estoma.appointments.domain.model.PieceType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AppointmentEventPayloadContractTest {
  private static final Instant START = Instant.parse("2026-08-25T14:00:00Z");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void slotPayloadsCarryCanonicalContextAndCancellationReason() {
    AppointmentTimeSlot slot = slot();
    var created = mapper.readTree(AppointmentEventPayloads.timeSlotCreated(slot));
    assertThat(created.path("appointmentTimeSlotId").asString()).isEqualTo(slot.appointmentTimeSlotId().toString());
    assertThat(created.path("calendarId").asString()).isEqualTo(slot.calendarId().toString());
    assertThat(created.path("sourceScheduleId").asString()).isEqualTo(slot.sourceScheduleId().toString());
    assertThat(created.path("serviceDate").asString()).isEqualTo(slot.serviceDate().toString());
    assertThat(created.path("startsAt").asString()).isEqualTo(slot.startsAt().toString());
    assertThat(created.path("endsAt").asString()).isEqualTo(slot.endsAt().toString());
    assertThat(created.path("arrivalToleranceUntil").asString()).isEqualTo(slot.arrivalToleranceUntil().toString());
    assertThat(created.path("version").asLong()).isEqualTo(1);
    assertThat(created.has("slotId")).isFalse();

    var cancelled = mapper.readTree(AppointmentEventPayloads.timeSlotCancelled(slot.cancel(), "holiday"));
    assertThat(cancelled.path("appointmentTimeSlotId").asString()).isEqualTo(slot.appointmentTimeSlotId().toString());
    assertThat(cancelled.path("serviceDate").asString()).isEqualTo(slot.serviceDate().toString());
    assertThat(cancelled.path("reason").asString()).isEqualTo("holiday");
    assertThat(cancelled.path("version").asLong()).isEqualTo(2);
  }

  @Test
  void appointmentLifecyclePayloadsMatchPublicEventsAndPreserveOwnerCodes() {
    Appointment scheduled = Appointment.scheduled(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), AppointmentType.NORMAL, 2, PieceType.HIGH_SPEED, null, START);
    var scheduledPayload = mapper.readTree(AppointmentEventPayloads.scheduled(scheduled));
    assertThat(scheduledPayload.path("appointmentId").asString()).isEqualTo(scheduled.appointmentId().toString());
    assertThat(scheduledPayload.path("studentAccountId").asString()).isEqualTo(scheduled.studentAccountId().toString());
    assertThat(scheduledPayload.path("courseSectionId").asString()).isEqualTo(scheduled.courseSectionId().toString());
    assertThat(scheduledPayload.path("createdAt").asString()).isEqualTo(START.toString());
    assertThat(scheduledPayload.path("instrumentCount").asInt()).isEqualTo(2);

    Appointment rejected = scheduled.rejectEntry("missing requirement", UUID.randomUUID(), START.plusSeconds(20));
    var rejectedPayload = mapper.readTree(AppointmentEventPayloads.entryRejected(rejected));
    assertThat(rejectedPayload.path("studentAccountId").asString()).isEqualTo(scheduled.studentAccountId().toString());
    assertThat(rejectedPayload.path("rejectionReason").asString()).isEqualTo("missing requirement");
    assertThat(rejectedPayload.path("rejectedAt").asString()).isEqualTo(START.plusSeconds(20).toString());

    Appointment cancelled = scheduled.cancel(CancellationType.CLINIC, "OWNER_CODE_\"_NEWLINE\nVALUE",
        "owner supplied", UUID.randomUUID(), START.plusSeconds(60));
    var cancelledPayload = mapper.readTree(AppointmentEventPayloads.cancelled(cancelled,
        UUID.randomUUID(), UUID.randomUUID()));
    assertThat(cancelledPayload.path("studentAccountId").asString()).isEqualTo(scheduled.studentAccountId().toString());
    assertThat(cancelledPayload.path("cancellationSubreason").asString()).isEqualTo("OWNER_CODE_\"_NEWLINE\nVALUE");
    assertThat(cancelledPayload.hasNonNull("washExecutionId")).isTrue();
    assertThat(cancelledPayload.hasNonNull("sourceEventId")).isTrue();
  }

  @Test
  void missedPayloadDoesNotInventPointBalance() {
    Appointment missed = Appointment.scheduled(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, START)
        .markMissed(START.plusSeconds(3600));
    var payload = mapper.readTree(AppointmentEventPayloads.missed(missed));
    assertThat(payload.path("studentAccountId").asString()).isEqualTo(missed.studentAccountId().toString());
    assertThat(payload.path("missedAt").asString()).isEqualTo(START.plusSeconds(3600).toString());
    assertThat(payload.has("points")).isFalse();
  }

  @Test
  void exceptionalAuthorizationEventsCarryAuditFields() {
    UUID supervisor = UUID.randomUUID();
    ExceptionalAppointmentAuthorization granted = ExceptionalAppointmentAuthorization.grant(
        UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 8, 25), supervisor,
        "second appointment", START, START.plusSeconds(7200));
    var grant = mapper.readTree(AppointmentEventPayloads.authorizationGranted(granted));
    assertThat(grant.path("authorizationDate").asString()).isEqualTo("2026-08-25");
    assertThat(grant.path("authorizedByAccountId").asString()).isEqualTo(supervisor.toString());
    assertThat(grant.path("reason").asString()).isEqualTo("second appointment");
    assertThat(grant.path("policyContextKnown").asBoolean()).isTrue();
    assertThat(grant.path("expiresAt").asString()).isEqualTo(START.plusSeconds(7200).toString());
    var successor = mapper.readTree(AppointmentEventPayloads.authorizationGranted(granted, java.util.List.of(granted)));
    assertThat(successor.path("supersededAuthorizationIds").get(0).asString())
        .isEqualTo(granted.exceptionalAuthorizationId().toString());

    var cancelled = granted.cancel(supervisor, "no longer needed", START.plusSeconds(30));
    var cancellation = mapper.readTree(AppointmentEventPayloads.authorizationCancelled(cancelled));
    assertThat(cancellation.path("cancellationReason").asString()).isEqualTo("no longer needed");
    assertThat(cancellation.path("cancelledByAccountId").asString()).isEqualTo(supervisor.toString());
  }

  private static AppointmentTimeSlot slot() {
    return AppointmentTimeSlot.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        LocalDate.ofInstant(START, ZoneOffset.UTC), START, START.plusSeconds(3600), ZoneOffset.UTC,
        START.minusSeconds(600), START.minusSeconds(1200), START.plusSeconds(600));
  }
}
