package com.estoma.washexecution.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.application.port.OptimisticConcurrencyException;
import org.junit.jupiter.api.Test;

class TransientFailureClassifierTest {
  @Test
  void optimisticWriteRaceIsRetriedSoRedeliveryCanReturnVersionConflict() {
    assertThat(new TransientFailureClassifier().isTransient(
        new OptimisticConcurrencyException())).isTrue();
  }
}
