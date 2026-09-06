package com.estoma.platformbff.application.port;

import java.time.Instant;
import java.util.UUID;

/** Projects the terminal materials and assignment release carried by WashExecutionCompleted. */
public interface WashCompletionProjectionPort {
  void projectCompletion(
      UUID washExecutionId,
      int finalPackageCount,
      int finalGreenPaperCassette8Count,
      int finalGreenPaperCassette10Count,
      int finalWitnessTapePortionCount,
      long executionVersion,
      Instant completedAt);
}
