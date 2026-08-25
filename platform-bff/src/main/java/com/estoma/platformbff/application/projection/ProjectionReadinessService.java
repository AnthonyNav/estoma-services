package com.estoma.platformbff.application.projection;

import com.estoma.platformbff.application.port.ProjectionStatusRepository;
import com.estoma.platformbff.domain.projection.ProjectionStatus;
import org.springframework.stereotype.Service;

/** Authorization-dependent writes fail closed while its projection is not READY. */
@Service
public class ProjectionReadinessService {
  private final ProjectionStatusRepository statuses;

  public ProjectionReadinessService(ProjectionStatusRepository statuses) {
    this.statuses = statuses;
  }

  public boolean authorizationReady() {
    return statuses.findByName("authorization")
        .map(state -> state.status() == ProjectionStatus.READY)
        .orElse(false);
  }

  public void requireAuthorizationReady() {
    if (!authorizationReady()) {
      throw new ProjectionUnavailableException("authorization projection is not READY; fail closed");
    }
  }

  public void requireReady(String... projectionNames) {
    for (String projectionName : projectionNames) {
      boolean ready = statuses.findByName(projectionName)
          .map(state -> state.status() == ProjectionStatus.READY)
          .orElse(false);
      if (!ready) {
        throw new ProjectionUnavailableException(
            projectionName + " projection is not READY; endpoint is temporarily unavailable");
      }
    }
  }
}
