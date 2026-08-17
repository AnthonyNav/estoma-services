package com.estoma.authorization.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MessagingPropertiesTest {

  @Test
  void rejectsAClaimBatchThatCanOutliveItsLease() {
    assertThatIllegalArgumentException().isThrownBy(() -> new MessagingProperties(
        true,
        true,
        Duration.ofSeconds(5),
        50,
        Duration.ofMinutes(2),
        Duration.ofSeconds(1),
        true,
        Duration.ofSeconds(1),
        true,
        Duration.ofSeconds(30),
        Duration.ofSeconds(5),
        Duration.ofSeconds(30)));
  }
}
