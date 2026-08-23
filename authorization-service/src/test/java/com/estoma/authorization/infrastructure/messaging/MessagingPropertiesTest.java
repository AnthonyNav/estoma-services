package com.estoma.authorization.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
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
        "",
        Duration.ofSeconds(30),
        Duration.ofSeconds(5),
        Duration.ofSeconds(30)));
  }

  @Test
  void bootstrapIsExplicitAndClosedToKnownProjectionTypes() {
    MessagingProperties properties = new MessagingProperties(
        true,
        true,
        Duration.ofSeconds(1),
        1,
        Duration.ofMinutes(2),
        Duration.ofSeconds(1),
        true,
        Duration.ofSeconds(1),
        true,
        "PERSON_STATUS,ACCOUNT_STATUS",
        Duration.ofSeconds(30),
        Duration.ofSeconds(5),
        Duration.ofSeconds(30));

    assertThat(properties.bootstrapRequested("PERSON_STATUS")).isTrue();
    assertThat(properties.bootstrapRequested("ACCOUNT_STATUS")).isTrue();
    assertThat(properties.bootstrapRequested("ACADEMIC_ELIGIBILITY")).isFalse();

    assertThatIllegalArgumentException().isThrownBy(() -> new MessagingProperties(
        true,
        true,
        Duration.ofSeconds(1),
        1,
        Duration.ofMinutes(2),
        Duration.ofSeconds(1),
        true,
        Duration.ofSeconds(1),
        true,
        "UNKNOWN",
        Duration.ofSeconds(30),
        Duration.ofSeconds(5),
        Duration.ofSeconds(30)));
  }
}
