package com.estoma.users.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.users.config.OutboxProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Verifies the fixed backoff ladder (docs/system-contract.md §8). */
class ConfiguredOutboxBackoffPolicyTest {

  private final OutboxProperties properties = defaultProperties();
  private final ConfiguredOutboxBackoffPolicy policy = new ConfiguredOutboxBackoffPolicy(properties);

  @ParameterizedTest
  @CsvSource({
    "1, PT5S",
    "2, PT30S",
    "3, PT2M",
    "4, PT5M",
    "5, PT10M",
    "6, PT15M",
    "7, PT15M", // beyond the list: reuses the last configured delay
    "100, PT15M",
  })
  void delayForAttemptFollowsTheConfiguredLadder(int attempts, Duration expected) {
    assertThat(policy.delayForAttempt(attempts)).isEqualTo(expected);
  }

  @Test
  void delayForAttemptRejectsNonPositiveAttempts() {
    assertThatThrownBy(() -> policy.delayForAttempt(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.delayForAttempt(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void outboxPropertiesRejectsAnEmptyRetryDelayList() {
    assertThatThrownBy(() -> new OutboxProperties(10, Duration.ofMinutes(2), 10, Duration.ofDays(7), Duration.ofDays(30), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void outboxPropertiesRejectsANonPositiveRetryDelay() {
    assertThatThrownBy(
            () ->
                new OutboxProperties(
                    10,
                    Duration.ofMinutes(2),
                    10,
                    Duration.ofDays(7),
                    Duration.ofDays(30),
                    List.of(Duration.ofSeconds(5), Duration.ZERO)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static OutboxProperties defaultProperties() {
    return new OutboxProperties(
        10,
        Duration.ofMinutes(2),
        10,
        Duration.ofDays(7),
        Duration.ofDays(30),
        List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(5),
            Duration.ofMinutes(10),
            Duration.ofMinutes(15)));
  }
}
