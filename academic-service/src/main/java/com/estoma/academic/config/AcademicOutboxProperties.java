package com.estoma.academic.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("estoma.outbox")
@Validated
public record AcademicOutboxProperties(
    int claimBatchSize,
    Duration lease,
    int maxAttempts,
    Duration retryDelay,
    Duration confirmTimeout,
    Duration publishBackoff) {
  public AcademicOutboxProperties {
    if (claimBatchSize < 1 || lease == null || lease.isNegative() || lease.isZero() || maxAttempts < 1
        || retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()
        || confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.isZero()
        || publishBackoff == null || publishBackoff.isNegative()) {
      throw new IllegalArgumentException("invalid estoma.outbox configuration");
    }
    try {
      Duration perMessageBudget = confirmTimeout.plus(publishBackoff);
      if (perMessageBudget.isZero()
          || perMessageBudget.multipliedBy(claimBatchSize).compareTo(lease) >= 0) {
        throw new IllegalArgumentException("outbox publish budget must be smaller than its lease");
      }
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException("outbox publish budget is too large", ex);
    }
  }
}
