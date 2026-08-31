package com.estoma.platformbff.application.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.ProjectionGenesisPort;
import com.estoma.platformbff.application.port.ProjectionStatusRepository;
import com.estoma.platformbff.domain.projection.ProjectionState;
import com.estoma.platformbff.domain.projection.ProjectionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectionGenesisServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

  @Mock ProjectionGenesisPort genesis;
  @Mock ProjectionStatusRepository statuses;
  @InjectMocks ProjectionGenesisService service;

  @Test
  void establishesEveryNonRecoverableProjectionFromInitialBootstrappingState() {
    service = new ProjectionGenesisService(genesis, statuses, Clock.fixed(NOW, ZoneOffset.UTC));
    ProjectionGenesisService.TARGETS.forEach(name -> when(genesis.findStatus(name))
        .thenReturn(Optional.of(new ProjectionState(name, ProjectionStatus.BOOTSTRAPPING,
            null, null, 0, NOW.minusSeconds(1)))));
    when(genesis.exists(ProjectionGenesisService.MARKER)).thenReturn(false);
    when(genesis.claim(ProjectionGenesisService.MARKER, NOW)).thenReturn(true);

    assertThat(service.establishFreshBaseline()).isTrue();

    verify(genesis).claim(ProjectionGenesisService.MARKER, NOW);
    for (String name : ProjectionGenesisService.TARGETS) {
      verify(statuses).upsert(new ProjectionState(name, ProjectionStatus.READY, null, null, 0, NOW));
    }
  }

  @Test
  void refusesToPromoteAnExistingOrPartiallyRecoveredEnvironment() {
    service = new ProjectionGenesisService(genesis, statuses, Clock.fixed(NOW, ZoneOffset.UTC));
    when(genesis.findStatus(anyString())).thenAnswer(invocation -> {
      String name = invocation.getArgument(0);
      ProjectionStatus status = "appointments".equals(name)
          ? ProjectionStatus.READY : ProjectionStatus.BOOTSTRAPPING;
      return Optional.of(new ProjectionState(name, status, NOW, null, 0, NOW));
    });

    assertThatThrownBy(() -> service.establishFreshBaseline())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("appointments");

    verify(genesis, never()).claim(any(), any());
    verifyNoInteractions(statuses);
  }

  @Test
  void doesNotRepeatGenesisAfterTheDurableMarkerExists() {
    service = new ProjectionGenesisService(genesis, statuses, Clock.fixed(NOW, ZoneOffset.UTC));
    when(genesis.exists(ProjectionGenesisService.MARKER)).thenReturn(true);

    assertThat(service.establishFreshBaseline()).isFalse();

    verifyNoInteractions(statuses);
    verify(genesis, never()).claim(any(), any());
  }

  @Test
  void doesNothingWhenAnotherReplicaAlreadyClaimedTheMarker() {
    service = new ProjectionGenesisService(genesis, statuses, Clock.fixed(NOW, ZoneOffset.UTC));
    ProjectionGenesisService.TARGETS.forEach(name -> when(genesis.findStatus(name))
        .thenReturn(Optional.of(new ProjectionState(name, ProjectionStatus.BOOTSTRAPPING,
            null, null, 0, NOW))));
    when(genesis.exists(ProjectionGenesisService.MARKER)).thenReturn(false);
    when(genesis.claim(ProjectionGenesisService.MARKER, NOW)).thenReturn(false);

    assertThat(service.establishFreshBaseline()).isFalse();
    verifyNoInteractions(statuses);
  }
}
