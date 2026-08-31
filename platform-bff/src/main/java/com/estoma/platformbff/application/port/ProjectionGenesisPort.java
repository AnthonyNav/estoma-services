package com.estoma.platformbff.application.port;

import com.estoma.platformbff.domain.projection.ProjectionState;
import java.time.Instant;
import java.util.Optional;

/** Durable coordination boundary for the one-shot projection genesis baseline. */
public interface ProjectionGenesisPort {
  Optional<ProjectionState> findStatus(String projectionName);

  /** A successful marker is durable proof that this database already established its baseline. */
  boolean exists(String markerName);

  /** Returns true only to the transaction that owns the one-shot bootstrap. */
  boolean claim(String markerName, Instant establishedAt);
}
