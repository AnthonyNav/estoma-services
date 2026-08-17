package com.estoma.authorization.infrastructure.messaging;

import java.time.Duration;
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
    Duration recoveryResultTimeout,
    Duration retry1Ttl,
    Duration retry2Ttl) {

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
    recoveryResultTimeout = positive(recoveryResultTimeout, Duration.ofSeconds(30));
    retry1Ttl = positive(retry1Ttl, Duration.ofSeconds(5));
    retry2Ttl = positive(retry2Ttl, Duration.ofSeconds(30));
  }

  private static Duration positive(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }
}
