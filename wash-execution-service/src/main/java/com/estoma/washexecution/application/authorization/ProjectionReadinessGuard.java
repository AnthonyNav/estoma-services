package com.estoma.washexecution.application.authorization;

import com.estoma.washexecution.application.command.CommandRejectedException;
import com.estoma.washexecution.application.port.ProjectionRecoveryRepository;

public final class ProjectionReadinessGuard {
  public static final String APPOINTMENTS = "appointments";
  public static final String WASH_ADMINISTRATION = "wash-administration";
  public static final String AUTHORIZATION = "authorization";

  private final ProjectionRecoveryRepository recovery;

  public ProjectionReadinessGuard(ProjectionRecoveryRepository recovery) {
    this.recovery = recovery;
  }

  public boolean isReady(String projectionName) {
    return recovery.find(projectionName)
        .map(state -> "READY".equals(state.status()))
        .orElse(false);
  }

  public void requireReady(String projectionName, String errorCode) {
    if (!isReady(projectionName)) {
      throw new CommandRejectedException(errorCode);
    }
  }
}
