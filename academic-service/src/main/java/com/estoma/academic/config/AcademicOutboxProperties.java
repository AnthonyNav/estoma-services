package com.estoma.academic.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("estoma.outbox")
@Validated
public record AcademicOutboxProperties(int claimBatchSize, Duration lease, int maxAttempts, Duration retryDelay) {
  public AcademicOutboxProperties {
    if (claimBatchSize < 1 || lease == null || lease.isNegative() || lease.isZero() || maxAttempts < 1
        || retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()) {
      throw new IllegalArgumentException("invalid estoma.outbox configuration");
    }
  }
}
