package com.estoma.users.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * batchSize=10, publisherConfirmTimeout=5s, safetyMargin=30s: required
 * lease = 10*5s + 30s = 80s (docs/system-contract.md §8).
 */
class OutboxLeaseBudgetValidatorTest {

  @Test
  void rejectsALeaseOneSecondShortOfTheBudget() {
    OutboxLeaseBudgetValidator validator = validatorWithLease(Duration.ofSeconds(79));

    assertThatThrownBy(validator::validate).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void acceptsALeaseExactlyAtTheBudget() {
    OutboxLeaseBudgetValidator validator = validatorWithLease(Duration.ofSeconds(80));

    assertThatCode(validator::validate).doesNotThrowAnyException();
  }

  @Test
  void acceptsALeaseComfortablyAboveTheBudget() {
    OutboxLeaseBudgetValidator validator = validatorWithLease(Duration.ofSeconds(120));

    assertThatCode(validator::validate).doesNotThrowAnyException();
  }

  private static OutboxLeaseBudgetValidator validatorWithLease(Duration lease) {
    OutboxProperties outboxProperties =
        new OutboxProperties(
            10, lease, 10, Duration.ofDays(7), Duration.ofDays(30), List.of(Duration.ofSeconds(5)));
    MessagingProperties messagingProperties = new MessagingProperties(Set.of("bff"), true, 3, Duration.ofSeconds(5));
    return new OutboxLeaseBudgetValidator(outboxProperties, messagingProperties);
  }
}
