package com.estoma.platformbff.application.port;

import java.time.Instant;
import java.util.UUID;

/** Independent monotonic projection for the materials declared by WashExitSubmitted. */
public interface WashExitMaterialsProjectionPort {
  void projectSubmittedExitMaterials(
      UUID washExecutionId,
      int packageCount,
      int greenPaperCassette8Count,
      int greenPaperCassette10Count,
      int witnessTapePortionCount,
      long executionVersion,
      Instant updatedAt);
}
