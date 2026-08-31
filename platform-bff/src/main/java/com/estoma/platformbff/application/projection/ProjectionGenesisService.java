package com.estoma.platformbff.application.projection;

import com.estoma.platformbff.application.port.ProjectionGenesisPort;
import com.estoma.platformbff.application.port.ProjectionStatusRepository;
import com.estoma.platformbff.domain.projection.ProjectionState;
import com.estoma.platformbff.domain.projection.ProjectionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Establishes a zero-history baseline only when deployment explicitly attests a fresh environment. */
@Service
public class ProjectionGenesisService {
  static final String MARKER = "non-recoverable-v1";
  static final List<String> TARGETS = List.of(
      "person", "revocation", "academic", "communications", "wash-administration",
      "appointments", "wash-execution");

  private final ProjectionGenesisPort genesis;
  private final ProjectionStatusRepository statuses;
  private final Clock clock;

  public ProjectionGenesisService(ProjectionGenesisPort genesis, ProjectionStatusRepository statuses,
      Clock clock) {
    this.genesis = genesis;
    this.statuses = statuses;
    this.clock = clock;
  }

  @Transactional
  public boolean establishFreshBaseline() {
    // A normal restart after a successful one-shot bootstrap must be a no-op even if an
    // environment has not removed its deployment flag yet. The marker also prevents the current
    // READY state from being misreported as an unsafe second genesis attempt.
    if (genesis.exists(MARKER)) return false;
    for (String target : TARGETS) {
      ProjectionState state = genesis.findStatus(target)
          .orElseThrow(() -> new IllegalStateException("missing projection status: " + target));
      if (state.status() != ProjectionStatus.BOOTSTRAPPING || state.generation() != 0) {
        throw new IllegalStateException("projection genesis is unsafe for " + target);
      }
    }
    Instant now = clock.instant();
    if (!genesis.claim(MARKER, now)) return false;
    for (String target : TARGETS) {
      statuses.upsert(new ProjectionState(target, ProjectionStatus.READY, null, null, 0, now));
    }
    return true;
  }
}
