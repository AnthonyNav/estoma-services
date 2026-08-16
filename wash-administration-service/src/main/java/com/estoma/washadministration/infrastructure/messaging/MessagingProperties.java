package com.estoma.washadministration.infrastructure.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "estoma.messaging")
public record MessagingProperties(
    Duration publisherConfirmTimeout,
    int outboxClaimBatchSize,
    int outboxMaxAttempts,
    long outboxPollInterval,
    boolean outboxRelayEnabled,
    long recoveryPollInterval,
    boolean recoveryWorkerEnabled,
    Duration recoveryResultTimeout) {

  public MessagingProperties {
    if (publisherConfirmTimeout == null) {
      publisherConfirmTimeout = Duration.ofSeconds(5);
    }
    if (outboxClaimBatchSize <= 0) {
      outboxClaimBatchSize = 50;
    }
    if (outboxMaxAttempts <= 0) {
      outboxMaxAttempts = 10;
    }
    if (outboxPollInterval <= 0) {
      outboxPollInterval = 1_000L;
    }
    if (recoveryPollInterval <= 0) {
      recoveryPollInterval = 1_000L;
    }
    if (recoveryResultTimeout == null || recoveryResultTimeout.isNegative() || recoveryResultTimeout.isZero()) {
      recoveryResultTimeout = Duration.ofSeconds(30);
    }
  }
}
