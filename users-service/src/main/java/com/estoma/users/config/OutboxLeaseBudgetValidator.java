package com.estoma.users.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Fails application startup if the Outbox lease is too short for the
 * relay to actually finish a batch before the sweeper reclaims it
 * (docs/system-contract.md §8). The relay ({@code OutboxRelay}) publishes
 * one row at a time and blocks on its confirm, so a batch's worst case is
 * {@code claimBatchSize × publisherConfirmTimeout}; the lease must cover
 * that plus a safety margin, or the sweeper legitimately reclaims rows
 * the relay is still working on — fencing prevents corruption in that
 * case, but it still produces needless duplicate publications and
 * spurious {@code CLAIM_LOST_BEFORE_MARK} log lines.
 *
 * <p>{@code OutboxProperties} and {@code MessagingProperties} are
 * separate {@code @ConfigurationProperties} records, so this
 * cross-cutting check cannot live in either one's compact constructor.
 */
@Component
public class OutboxLeaseBudgetValidator {

  static final Duration LEASE_SAFETY_MARGIN = Duration.ofSeconds(30);

  private final OutboxProperties outboxProperties;
  private final MessagingProperties messagingProperties;

  public OutboxLeaseBudgetValidator(OutboxProperties outboxProperties, MessagingProperties messagingProperties) {
    this.outboxProperties = outboxProperties;
    this.messagingProperties = messagingProperties;
  }

  @PostConstruct
  public void validate() {
    Duration required = requiredLease(
        messagingProperties.publisherConfirmTimeout(), outboxProperties.claimBatchSize(), LEASE_SAFETY_MARGIN);
    Duration lease = outboxProperties.lease();
    if (lease.compareTo(required) < 0) {
      throw new IllegalStateException(
          "estoma.outbox.lease ("
              + lease
              + ") is shorter than the required lease budget ("
              + required
              + " = claim-batch-size ["
              + outboxProperties.claimBatchSize()
              + "] x publisher-confirm-timeout ["
              + messagingProperties.publisherConfirmTimeout()
              + "] + safety margin ["
              + LEASE_SAFETY_MARGIN
              + "]); the sweeper would reclaim rows the relay is still publishing");
    }
  }

  static Duration requiredLease(Duration publisherConfirmTimeout, int claimBatchSize, Duration safetyMargin) {
    return publisherConfirmTimeout.multipliedBy(claimBatchSize).plus(safetyMargin);
  }
}
