package com.estoma.washexecution.infrastructure.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "estoma.messaging")
public record MessagingProperties(
    Duration publisherConfirmTimeout,
    Duration outboxLease,
    int outboxClaimBatchSize,
    long outboxPollInterval,
    boolean outboxRelayEnabled,
    Duration inboxLease,
    long deferredReplayPollInterval,
    boolean deferredReplayEnabled,
    long recoveryPollInterval,
    boolean recoveryWorkerEnabled,
    Duration recoveryResultTimeout) {

  public MessagingProperties {
    if (publisherConfirmTimeout == null || publisherConfirmTimeout.isNegative() || publisherConfirmTimeout.isZero()) {
      publisherConfirmTimeout = Duration.ofSeconds(5);
    }
    if (outboxLease == null || outboxLease.isNegative() || outboxLease.isZero()) {
      outboxLease = Duration.ofSeconds(30);
    }
    if (outboxClaimBatchSize <= 0) outboxClaimBatchSize = 50;
    if (outboxPollInterval <= 0) outboxPollInterval = 1_000L;
    if (inboxLease == null || inboxLease.isNegative() || inboxLease.isZero()) {
      inboxLease = Duration.ofSeconds(30);
    }
    if (deferredReplayPollInterval <= 0) deferredReplayPollInterval = 1_000L;
    if (recoveryPollInterval <= 0) recoveryPollInterval = 1_000L;
    if (recoveryResultTimeout == null || recoveryResultTimeout.isNegative() || recoveryResultTimeout.isZero()) {
      recoveryResultTimeout = Duration.ofSeconds(30);
    }
  }
}
