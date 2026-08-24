package com.estoma.appointments.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.port.out.ProjectionRecoveryRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectionRecoveryTest {
  @Test
  void criticalReadinessIsFailClosedUntilEveryProjectionIsReady() {
    MemoryStates states = new MemoryStates();
    ProjectionRecoveryCoordinator coordinator = new ProjectionRecoveryCoordinator(states, java.time.Clock.systemUTC());
    assertThat(coordinator.criticalReady()).isFalse();
    ProjectionRecoveryCoordinator.CRITICAL_PROJECTIONS.forEach(name -> coordinator.ready(name, 1));
    assertThat(coordinator.criticalReady()).isTrue();
    coordinator.degraded("wash-execution", "evidence rebuild failed");
    assertThat(coordinator.criticalReady()).isFalse();
  }

  private static final class MemoryStates implements ProjectionRecoveryRepository {
    final Map<String, RecoveryState> values = new HashMap<>();
    @Override public RecoveryState get(String name) { return values.getOrDefault(name, new RecoveryState(name, "UNINITIALIZED", 0, Instant.EPOCH, null, 1)); }
    @Override public boolean update(RecoveryState state, long expectedVersion) { values.put(state.projectionName(), state); return true; }
  }
}
