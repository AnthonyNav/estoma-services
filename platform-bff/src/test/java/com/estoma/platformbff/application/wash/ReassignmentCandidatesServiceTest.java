package com.estoma.platformbff.application.wash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.ReassignmentCandidatesPort;
import com.estoma.platformbff.application.port.ReassignmentCandidatesPort.Candidate;
import com.estoma.platformbff.application.port.ReassignmentCandidatesPort.ProjectedExecution;
import com.estoma.platformbff.application.port.ReassignmentCandidatesPort.Snapshot;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReassignmentCandidatesServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-05T18:00:00Z");
  private static final UUID EXECUTION_ID = UUID.randomUUID();
  @Mock LocalAuthorizationService authorization;
  @Mock ProjectionReadinessService readiness;
  @Mock ReassignmentCandidatesPort port;
  private ReassignmentCandidatesService service;

  @BeforeEach
  void setUp() {
    service = new ReassignmentCandidatesService(authorization, readiness, port,
        Clock.fixed(NOW, ZoneOffset.UTC), true);
  }

  @Test
  void servesOwnerOrderAndDerivesRecommendationWithoutWriting() {
    Candidate first = candidate("107", "B", 2);
    Candidate second = candidate("108", "C", 1);
    when(port.findProjectedExecution(EXECUTION_ID)).thenReturn(Optional.of(
        new ProjectedExecution(EXECUTION_ID, "PENDING_REASSIGNMENT", 2)));
    when(port.findSnapshot(EXECUTION_ID)).thenReturn(Optional.of(new Snapshot(EXECUTION_ID, 2,
        UUID.randomUUID(), NOW.minusSeconds(1), NOW.plusSeconds(9), List.of(first, second))));

    var response = service.candidates(EXECUTION_ID);

    assertThat(response.executionVersion()).isEqualTo(2);
    assertThat(response.candidates()).extracting("cabinCode").containsExactly("107", "108");
    assertThat(response.recommendedCandidate()).isEqualTo(response.candidates().getFirst());
    verify(authorization).requireWashSupervisor();
    verify(readiness).requireReady("wash-execution");
    verify(port).findProjectedExecution(EXECUTION_ID);
    verify(port).findSnapshot(EXECUTION_ID);
    verifyNoMoreInteractions(port);
  }

  @Test
  void provenEmptyIsARealSuccessfulSnapshot() {
    when(port.findProjectedExecution(EXECUTION_ID)).thenReturn(Optional.of(
        new ProjectedExecution(EXECUTION_ID, "PENDING_REASSIGNMENT", 2)));
    when(port.findSnapshot(EXECUTION_ID)).thenReturn(Optional.of(new Snapshot(EXECUTION_ID, 2,
        UUID.randomUUID(), NOW, NOW.plusSeconds(10), List.of())));

    var response = service.candidates(EXECUTION_ID);

    assertThat(response.candidates()).isEmpty();
    assertThat(response.recommendedCandidate()).isNull();
  }

  @Test
  void missingStaleFutureOrWrongVersionSnapshotsFailClosedWithoutRefreshWrites() {
    when(port.findProjectedExecution(EXECUTION_ID)).thenReturn(Optional.of(
        new ProjectedExecution(EXECUTION_ID, "PENDING_REASSIGNMENT", 3)));
    when(port.findSnapshot(EXECUTION_ID)).thenReturn(Optional.of(new Snapshot(EXECUTION_ID, 2,
        UUID.randomUUID(), NOW.minusSeconds(11), NOW.minusSeconds(1), List.of())));

    assertThatThrownBy(() -> service.candidates(EXECUTION_ID))
        .isInstanceOf(ProjectionUnavailableException.class);
    verifyNoMoreInteractions(port);
  }

  @Test
  void ownerFreshUntilBoundaryIsAlreadyExpired() {
    when(port.findProjectedExecution(EXECUTION_ID)).thenReturn(Optional.of(
        new ProjectedExecution(EXECUTION_ID, "PENDING_REASSIGNMENT", 3)));
    when(port.findSnapshot(EXECUTION_ID)).thenReturn(Optional.of(new Snapshot(EXECUTION_ID, 3,
        UUID.randomUUID(), NOW.minusSeconds(10), NOW, List.of())));

    assertThatThrownBy(() -> service.candidates(EXECUTION_ID))
        .isInstanceOf(ProjectionUnavailableException.class);
  }

  @Test
  void futureDatedOwnerSnapshotFailsClosed() {
    when(port.findProjectedExecution(EXECUTION_ID)).thenReturn(Optional.of(
        new ProjectedExecution(EXECUTION_ID, "PENDING_REASSIGNMENT", 3)));
    when(port.findSnapshot(EXECUTION_ID)).thenReturn(Optional.of(new Snapshot(EXECUTION_ID, 3,
        UUID.randomUUID(), NOW.plusNanos(1), NOW.plusSeconds(10).plusNanos(1), List.of())));

    assertThatThrownBy(() -> service.candidates(EXECUTION_ID))
        .isInstanceOf(ProjectionUnavailableException.class);
  }

  @Test
  void featureGateFailsClosedBeforeAnyProjectionReadOrWrite() {
    var disabled = new ReassignmentCandidatesService(authorization, readiness, port,
        Clock.fixed(NOW, ZoneOffset.UTC), false);

    assertThatThrownBy(() -> disabled.candidates(EXECUTION_ID))
        .isInstanceOf(ProjectionUnavailableException.class);
    verify(port, never()).findProjectedExecution(EXECUTION_ID);
    verifyNoMoreInteractions(port);
  }

  private static Candidate candidate(String cabin, String tank, int capacity) {
    return new Candidate(UUID.randomUUID(), cabin, "Cabina " + cabin, UUID.randomUUID(), tank,
        "Tina " + tank, capacity);
  }
}
