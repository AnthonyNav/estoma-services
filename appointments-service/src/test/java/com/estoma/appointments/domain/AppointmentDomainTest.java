package com.estoma.appointments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.appointments.domain.error.DomainViolationException;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentStatus;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.CancellationType;
import com.estoma.appointments.domain.model.PieceType;
import com.estoma.appointments.domain.service.AppointmentCapacityMatcher;
import com.estoma.appointments.domain.service.DailyPenaltyPolicy;
import com.estoma.appointments.domain.service.ImmunocompromisedReservationPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppointmentDomainTest {
  private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

  @Test void terminalStatesCannotReopen() {
    Appointment appointment = appointment(AppointmentType.NORMAL);
    Appointment cancelled = appointment.cancel(CancellationType.STUDENT, null, NOW.plusSeconds(1));
    assertThat(cancelled.status()).isEqualTo(AppointmentStatus.CANCELLED);
    assertThatThrownBy(() -> cancelled.start(NOW.plusSeconds(2)))
        .isInstanceOf(DomainViolationException.class);
  }

  @Test void pointsAreDerivedFromStatusAndCancellationType() {
    Appointment appointment = appointment(AppointmentType.NORMAL);
    assertThat(appointment.points()).isZero();
    assertThat(appointment.cancel(CancellationType.STUDENT, null, NOW).points()).isOne();
    assertThat(appointment.cancel(CancellationType.CLINIC, "CAPACITY_LOSS", NOW).points()).isZero();
    assertThat(appointment.markMissed(NOW).points()).isOne();
    assertThat(DailyPenaltyPolicy.MAX_POINTS).isEqualTo(3);
  }

  @Test void typeInstrumentBoundsAreEnforced() {
    assertThatThrownBy(() -> appointment(AppointmentType.NORMAL, 41)).isInstanceOf(DomainViolationException.class);
    assertThatThrownBy(() -> appointment(AppointmentType.JOURNEY, 81)).isInstanceOf(DomainViolationException.class);
    assertThat(appointment(AppointmentType.JOURNEY, 80).instrumentCount()).isEqualTo(80);
  }

  @Test void courseSectionIsRequired() {
    assertThatThrownBy(() -> Appointment.scheduled(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW))
        .isInstanceOf(NullPointerException.class);
  }

  @Test void clinicCancellationRequiresOwnerSubreasonButDoesNotOwnItsCatalog() {
    assertThatThrownBy(() -> appointment(AppointmentType.NORMAL).cancel(CancellationType.CLINIC, null, NOW))
        .isInstanceOf(DomainViolationException.class);
    Appointment extendedOwnerCode = appointment(AppointmentType.NORMAL)
        .cancel(CancellationType.CLINIC, "RESOURCE_UNAVAILABLE", NOW);
    assertThat(extendedOwnerCode.cancellationSubreason()).isEqualTo("RESOURCE_UNAVAILABLE");
  }

  @Test void exceptionalAuthorizationRestoresOnlyForClinicCancellation() {
    UUID authorizationId = UUID.randomUUID();
    Appointment appointment = Appointment.scheduled(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), AppointmentType.JOURNEY, 1, PieceType.HIGH_SPEED, authorizationId, NOW);
    assertThat(appointment.consumesExceptionalAuthorization()).isTrue();
    assertThat(appointment.cancel(CancellationType.STUDENT, null, NOW).consumesExceptionalAuthorization()).isTrue();
    assertThat(appointment.markMissed(NOW).consumesExceptionalAuthorization()).isTrue();
    Appointment clinic = appointment.cancel(CancellationType.CLINIC, "CAPACITY_LOSS", NOW);
    assertThat(clinic.consumesExceptionalAuthorization()).isFalse();
  }

  @Test void immunocompromisedPreventiveConflictIsSymmetricOnlyWithinImmunoPool() {
    AppointmentTimeSlot early = slot(NOW);
    AppointmentTimeSlot later = slot(NOW.plusSeconds(3 * 60 * 60 - 60));
    assertThat(ImmunocompromisedReservationPolicy.conflicts(
        early, AppointmentType.IMMUNOCOMPROMISED, later, AppointmentType.IMMUNOCOMPROMISED)).isTrue();
    assertThat(ImmunocompromisedReservationPolicy.conflicts(
        later, AppointmentType.IMMUNOCOMPROMISED, early, AppointmentType.IMMUNOCOMPROMISED)).isTrue();
    assertThat(ImmunocompromisedReservationPolicy.conflicts(
        early, AppointmentType.NORMAL, later, AppointmentType.IMMUNOCOMPROMISED)).isFalse();
  }

  @Test void capacityMatchingSharesNormalJourneyAndKeepsImmunoIndependent() {
    Appointment normal = appointment(AppointmentType.NORMAL);
    Appointment journey = appointment(AppointmentType.JOURNEY);
    Appointment immuno = appointment(AppointmentType.IMMUNOCOMPROMISED);

    assertThat(AppointmentCapacityMatcher.canFit(List.of(normal), AppointmentType.JOURNEY, 1, 1, 0)).isTrue();
    assertThat(AppointmentCapacityMatcher.canFit(List.of(normal, journey), AppointmentType.NORMAL, 1, 1, 0)).isFalse();
    assertThat(AppointmentCapacityMatcher.canFit(List.of(immuno), AppointmentType.NORMAL, 1, 0, 1)).isTrue();
    assertThat(AppointmentCapacityMatcher.canFit(List.of(immuno), AppointmentType.IMMUNOCOMPROMISED, 1, 1, 1)).isFalse();
  }

  @Test void startedClinicCancellationPreservesStartedAt() {
    Appointment started = appointment(AppointmentType.NORMAL).start(NOW.plusSeconds(30));
    Appointment cancelled = started.cancel(CancellationType.CLINIC, "CAPACITY_LOSS",
        "resource unavailable", UUID.randomUUID(), NOW.plusSeconds(60));
    assertThat(cancelled.status()).isEqualTo(AppointmentStatus.CANCELLED);
    assertThat(cancelled.startedAt()).isEqualTo(NOW.plusSeconds(30));
  }

  private static Appointment appointment(AppointmentType type) { return appointment(type, 1); }
  private static Appointment appointment(AppointmentType type, int count) {
    return Appointment.scheduled(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), type,
        count, PieceType.HIGH_SPEED, null, NOW);
  }

  private static AppointmentTimeSlot slot(Instant starts) {
    return AppointmentTimeSlot.create(UUID.randomUUID(), UUID.randomUUID(),
        LocalDate.ofInstant(starts, ZoneOffset.UTC), starts, starts.plusSeconds(30 * 60), ZoneOffset.UTC,
        starts.minusSeconds(10 * 60), starts.minusSeconds(20 * 60), starts.plusSeconds(40 * 60));
  }
}
