package com.estoma.platformbff.application.wash;

import com.estoma.platformbff.application.port.ReassignmentCandidatesPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.wash.ReassignmentCandidatesResponse;
import com.estoma.platformbff.contracts.http.wash.ReassignmentCandidatesResponse.CandidateResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Side-effect-free presentation of a fresh, version-fenced owner snapshot. */
@Service
public class ReassignmentCandidatesService {
  private static final Duration OWNER_TTL = Duration.ofSeconds(10);

  private final LocalAuthorizationService authorization;
  private final ProjectionReadinessService readiness;
  private final ReassignmentCandidatesPort candidates;
  private final Clock clock;
  private final boolean enabled;

  public ReassignmentCandidatesService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, ReassignmentCandidatesPort candidates, Clock clock,
      @Value("${platform.wash.reassignment-candidates.enabled:false}") boolean enabled) {
    this.authorization = authorization;
    this.readiness = readiness;
    this.candidates = candidates;
    this.clock = clock;
    this.enabled = enabled;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ReassignmentCandidatesResponse candidates(UUID washExecutionId) {
    authorization.requireWashSupervisor();
    readiness.requireReady("wash-execution");
    if (!enabled) unavailable();

    var execution = candidates.findProjectedExecution(washExecutionId).orElse(null);
    if (execution == null || !"PENDING_REASSIGNMENT".equals(execution.status())) unavailable();
    var snapshot = candidates.findSnapshot(washExecutionId).orElse(null);
    var now = clock.instant();
    if (snapshot == null || snapshot.executionVersion() != execution.executionVersion()
        || now.isBefore(snapshot.snapshotGeneratedAt()) || !now.isBefore(snapshot.freshUntil())
        || !snapshot.freshUntil().equals(snapshot.snapshotGeneratedAt().plus(OWNER_TTL))) {
      unavailable();
    }

    var values = snapshot.candidates().stream()
        .map(value -> new CandidateResponse(value.cabinId(), value.cabinCode(), value.cabinName(),
            value.tankId(), value.tankCode(), value.tankName(), value.availableCapacity()))
        .toList();
    return new ReassignmentCandidatesResponse(washExecutionId, snapshot.executionVersion(),
        snapshot.snapshotGeneratedAt(), values.isEmpty() ? null : values.getFirst(), values);
  }

  private static void unavailable() {
    throw new ProjectionUnavailableException(
        "a fresh owner-derived reassignment candidate snapshot is unavailable");
  }
}
