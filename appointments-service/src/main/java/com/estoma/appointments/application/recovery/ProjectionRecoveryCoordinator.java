package com.estoma.appointments.application.recovery;

import com.estoma.appointments.application.port.out.ProjectionRecoveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectionRecoveryCoordinator {
  public static final Set<String> CRITICAL_PROJECTIONS = Set.of("academic", "authorization",
      "wash-administration", "wash-execution");
  private final ProjectionRecoveryRepository states;
  private final Clock clock;
  public ProjectionRecoveryCoordinator(ProjectionRecoveryRepository states, Clock clock) { this.states = states; this.clock = clock; }

  @Transactional
  public void rebuilding(String projection) { update(projection, "REBUILDING", null); }
  @Transactional
  public void ready(String projection, long sourceVersion) { update(projection, "READY", sourceVersion); }
  @Transactional
  public void degraded(String projection, String error) {
    ProjectionRecoveryRepository.RecoveryState current = states.get(projection);
    update(projection, "DEGRADED", current.sourceVersion(), error);
  }
  public boolean criticalReady() { return CRITICAL_PROJECTIONS.stream().allMatch(name -> "READY".equals(states.get(name).status())); }

  private void update(String projection, String status, Long sourceVersion) { update(projection, status, sourceVersion == null ? states.get(projection).sourceVersion() : sourceVersion, null); }
  private void update(String projection, String status, long sourceVersion, String error) {
    ProjectionRecoveryRepository.RecoveryState current = states.get(projection);
    states.update(new ProjectionRecoveryRepository.RecoveryState(projection, status, sourceVersion, Instant.now(clock), error, current.version() + 1), current.version());
  }
}
