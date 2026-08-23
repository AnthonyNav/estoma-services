package com.estoma.platformbff.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.platformbff.domain.operation.OperationStatus;
import org.junit.jupiter.api.Test;

class OperationStatusTest {
  @Test
  void onlyPendingIsNonTerminal() {
    assertThat(OperationStatus.PENDING.terminal()).isFalse();
    assertThat(OperationStatus.values())
        .filteredOn(status -> status != OperationStatus.PENDING)
        .allMatch(OperationStatus::terminal);
  }
}
