package com.estoma.appointments.application.recovery;

import com.estoma.appointments.application.port.out.ProjectionRecoveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectionRecoveryCoordinator {
  public static final Set<String> CRITICAL_PROJECTIONS = Set.of(
      "academic", "authorization", "wash-administration", "wash-execution");
  private final ProjectionRecoveryRepository states;
  private final Clock clock;

  public ProjectionRecoveryCoordinator(ProjectionRecoveryRepository states, Clock clock) {
    this.states = states;
    this.clock = clock;
  }

  public ProjectionRecoveryRepository.RecoveryState state(String projection) {
    return states.get(projection);
  }

  public boolean isReady(String projection) {
    return "READY".equals(state(projection).status());
  }

  public boolean criticalReady() {
    return CRITICAL_PROJECTIONS.stream().allMatch(this::isReady);
  }

  @Transactional
  public void rebuilding(String projection) {
    change(projection, "REBUILDING", state(projection).sourceVersion(), null);
  }

  @Transactional
  public void ready(String projection, long sourceVersion) {
    change(projection, "READY", Math.max(sourceVersion, state(projection).sourceVersion()), null);
  }

  @Transactional
  public void degraded(String projection, String error) {
    change(projection, "DEGRADED", state(projection).sourceVersion(), error);
  }

  private void change(String projection, String status, long sourceVersion, String error) {
    if (!CRITICAL_PROJECTIONS.contains(projection)) {
      throw new IllegalArgumentException("unknown projection " + projection);
    }
    for (int attempt = 0; attempt < 3; attempt++) {
      ProjectionRecoveryRepository.RecoveryState current = states.get(projection);
      var next = new ProjectionRecoveryRepository.RecoveryState(projection, status, sourceVersion,
          Instant.now(clock), error, current.version() + 1);
      if (states.update(next, current.version())) return;
    }
    throw new IllegalStateException("projection recovery state changed concurrently: " + projection);
  }
}
