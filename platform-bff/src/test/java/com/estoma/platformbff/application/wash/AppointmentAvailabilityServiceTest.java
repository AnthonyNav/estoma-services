package com.estoma.platformbff.application.wash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.AppointmentAvailabilityPort;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.AvailableTimeSlot;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Context;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Snapshot;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.application.wash.AppointmentAvailabilityService.AppointmentAvailabilityRefreshingException;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentType;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.PieceType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppointmentAvailabilityServiceTest {
  private static final UUID ACCOUNT_ID = UUID.randomUUID();
  private static final UUID COURSE_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-09-04T18:00:00Z");

  @Mock LocalAuthorizationService authorization;
  @Mock ProjectionReadinessService readiness;
  @Mock AppointmentAvailabilityPort availability;
  private AppointmentAvailabilityService service;

  @BeforeEach
  void setUp() {
    service = new AppointmentAvailabilityService(authorization, readiness, availability,
        Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30), Duration.ofSeconds(30), 1);
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
  }

  @Test
  void servesOnlyTheFreshOwnerDerivedSnapshot() {
    Context context = context();
    UUID slotId = UUID.randomUUID();
    when(availability.findSnapshot(context)).thenReturn(Optional.of(new Snapshot(context,
        UUID.randomUUID(), NOW.minusSeconds(1), NOW.plusSeconds(20), true, List.of(), 0, 0,
        false, false, null, List.of(new AvailableTimeSlot(slotId, NOW.plusSeconds(300),
            NOW.plusSeconds(3600), 4, NOW.plusSeconds(20))))));

    var response = service.availability(AppointmentType.NORMAL, 15, PieceType.HIGH_SPEED, COURSE_ID);

    assertThat(response.canSchedule()).isTrue();
    assertThat(response.availableTimeSlots()).singleElement()
        .satisfies(slot -> assertThat(slot.appointmentTimeSlotId()).isEqualTo(slotId));
    verify(readiness).requireReady("appointments");
    verify(availability, never()).enqueueRefresh(any(), any());
  }

  @Test
  void missingSnapshotEnqueuesOneDurableRefreshAndFailsClosed() {
    when(availability.findSnapshot(context())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.availability(
        AppointmentType.NORMAL, 15, PieceType.HIGH_SPEED, COURSE_ID))
        .isInstanceOf(AppointmentAvailabilityRefreshingException.class)
        .extracting("retryAfterSeconds").isEqualTo(1L);

    var refresh = ArgumentCaptor.forClass(AppointmentAvailabilityPort.Refresh.class);
    verify(availability).enqueueRefresh(refresh.capture(),
        org.mockito.ArgumentMatchers.eq(NOW.minusSeconds(30)));
    assertThat(refresh.getValue().context()).isEqualTo(context());
  }

  @Test
  void snapshotFreshUntilIsInclusiveButTheNextInstantFailsClosed() {
    Context context = context();
    var slot = new AvailableTimeSlot(UUID.randomUUID(), NOW.plusSeconds(300),
        NOW.plusSeconds(3600), 4, NOW);
    when(availability.findSnapshot(context)).thenReturn(Optional.of(new Snapshot(context,
        UUID.randomUUID(), NOW.minusSeconds(30), NOW, true, List.of(), 0, 0,
        false, false, null, List.of(slot))));

    assertThat(service.availability(
        AppointmentType.NORMAL, 15, PieceType.HIGH_SPEED, COURSE_ID).canSchedule()).isTrue();
    verify(availability, never()).enqueueRefresh(any(), any());

    var afterBoundary = new AppointmentAvailabilityService(authorization, readiness, availability,
        Clock.fixed(NOW.plusNanos(1), ZoneOffset.UTC), Duration.ofSeconds(30),
        Duration.ofSeconds(30), 1);
    assertThatThrownBy(() -> afterBoundary.availability(
        AppointmentType.NORMAL, 15, PieceType.HIGH_SPEED, COURSE_ID))
        .isInstanceOf(AppointmentAvailabilityRefreshingException.class);
  }

  @Test
  void localInstrumentRangeRejectsBeforeAnyOwnerRefresh() {
    assertThatThrownBy(() -> service.availability(
        AppointmentType.NORMAL, 41, PieceType.HIGH_SPEED, COURSE_ID))
        .isInstanceOf(IllegalArgumentException.class);
    verify(availability, never()).findSnapshot(any());
  }

  @Test
  void recentOwnerRejectionReturnsBadRequestSemanticsWithoutRefreshLoop() {
    Context context = context();
    var rejected = new AppointmentAvailabilityPort.Refresh(context, UUID.randomUUID(),
        UUID.randomUUID(), "REJECTED", null, null, 1, null, NOW.minusSeconds(1),
        "INVALID_AVAILABILITY_CONTEXT", NOW.minusSeconds(2));
    when(availability.findSnapshot(context)).thenReturn(Optional.empty());
    when(availability.findRefresh(context)).thenReturn(Optional.of(rejected));

    assertThatThrownBy(() -> service.availability(
        AppointmentType.NORMAL, 15, PieceType.HIGH_SPEED, COURSE_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rejected by owner");
    verify(availability, never()).enqueueRefresh(any(), any());
  }

  private static Context context() {
    return new Context(ACCOUNT_ID, LocalDate.of(2026, 9, 4), "NORMAL", 15, "HIGH_SPEED",
        COURSE_ID);
  }
}
