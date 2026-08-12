package com.estoma.academic.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AcademicOutboxLeaseBudgetValidatorTest {
  @Test
  void rejectsLeaseShorterThanWorstCaseBatchBudget() {
    assertThatThrownBy(() -> new AcademicOutboxProperties(10, Duration.ofSeconds(50), 3,
        Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("publish budget");
  }

  @Test
  void computesBudgetWithBackoffAndSafetyMargin() {
    assertThat(AcademicOutboxLeaseBudgetValidator.requiredLease(Duration.ofSeconds(5),
        Duration.ofMillis(100), 10, Duration.ofSeconds(30)))
        .isEqualTo(Duration.ofSeconds(81));
  }
}
