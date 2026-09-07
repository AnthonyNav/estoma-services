package com.estoma.platformbff.application.port;

import java.time.Instant;
import java.util.UUID;

public interface WashCancellationProjectionPort {
  void projectCancellation(UUID washExecutionId, long executionVersion, Instant cancelledAt);
}
