package com.estoma.authorization.infrastructure.messaging;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "estoma.messaging")
public record MessagingProperties(
    boolean topologyDeclare,
    boolean listenersEnabled,
    Duration publisherConfirmTimeout,
    int outboxClaimBatchSize,
    Duration outboxLease,
    Duration outboxPollInterval,
    boolean outboxRelayEnabled,
    Duration recoveryPollInterval,
    boolean recoveryWorkerEnabled,
    String recoveryBootstrapProjections,
    Duration recoveryResultTimeout,
    Duration retry1Ttl,
    Duration retry2Ttl) {

  private static final Set<String> RECOVERY_PROJECTIONS = Set.of(
      "PERSON_STATUS", "ACCOUNT_STATUS", "ACADEMIC_ELIGIBILITY");

  public MessagingProperties {
    publisherConfirmTimeout = positive(publisherConfirmTimeout, Duration.ofSeconds(5));
    outboxClaimBatchSize = outboxClaimBatchSize > 0 ? outboxClaimBatchSize : 10;
    outboxLease = positive(outboxLease, Duration.ofMinutes(2));
    Duration minimumLease = publisherConfirmTimeout.multipliedBy(outboxClaimBatchSize + 1L);
    if (outboxLease.compareTo(minimumLease) < 0) {
      throw new IllegalArgumentException("outboxLease must cover the claimed batch and one confirmation margin");
    }
    outboxPollInterval = positive(outboxPollInterval, Duration.ofSeconds(1));
    recoveryPollInterval = positive(recoveryPollInterval, Duration.ofSeconds(1));
    recoveryBootstrapProjections = recoveryBootstrapProjections == null
        ? ""
        : recoveryBootstrapProjections.trim();
    validateRecoveryBootstrapProjections(recoveryBootstrapProjections);
    recoveryResultTimeout = positive(recoveryResultTimeout, Duration.ofSeconds(30));
    retry1Ttl = positive(retry1Ttl, Duration.ofSeconds(5));
    retry2Ttl = positive(retry2Ttl, Duration.ofSeconds(30));
  }

  public boolean bootstrapRequested(String projectionType) {
    if (recoveryBootstrapProjections.isBlank()) {
      return false;
    }
    for (String configured : recoveryBootstrapProjections.split(",")) {
      if (projectionType.equals(configured.trim())) {
        return true;
      }
    }
    return false;
  }

  private static void validateRecoveryBootstrapProjections(String configured) {
    if (configured.isBlank()) {
      return;
    }
    for (String value : configured.split(",")) {
      String projection = value.trim();
      if (projection.isEmpty() || !RECOVERY_PROJECTIONS.contains(projection)) {
        throw new IllegalArgumentException(
            "recoveryBootstrapProjections contains unsupported projection: " + projection);
      }
    }
  }

  private static Duration positive(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }
}
