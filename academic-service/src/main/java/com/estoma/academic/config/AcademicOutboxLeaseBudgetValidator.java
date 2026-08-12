package com.estoma.academic.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Rejects a lease that cannot cover the configured worst-case relay batch. */
@Component
public class AcademicOutboxLeaseBudgetValidator {
  static final Duration SAFETY_MARGIN = Duration.ofSeconds(30);
  private final AcademicOutboxProperties properties;

  public AcademicOutboxLeaseBudgetValidator(AcademicOutboxProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  public void validate() {
    Duration required = requiredLease(properties.confirmTimeout(), properties.publishBackoff(), properties.claimBatchSize(), SAFETY_MARGIN);
    if (properties.lease().compareTo(required) < 0) {
      throw new IllegalStateException("estoma.outbox.lease " + properties.lease()
          + " is shorter than the required relay budget " + required);
    }
  }

  static Duration requiredLease(Duration confirmTimeout, Duration publishBackoff, int batchSize, Duration safetyMargin) {
    return confirmTimeout.plus(publishBackoff).multipliedBy(batchSize).plus(safetyMargin);
  }
}
