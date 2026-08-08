package com.estoma.authentication.infrastructure.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed {@code estoma.messaging.*} configuration (system_contract.md
 * "Outbox Operational Policy"). {@code outboxRelayEnabled} has no
 * compact-constructor fallback like the other fields below: its default
 * ({@code true}) is set once in {@code application.yml}, not here, since
 * a bound {@code false} would be indistinguishable from an absent value
 * at this layer. Business-logic integration tests turn it off (see
 * {@code application-test.yml}) so a stray scheduled tick never competes
 * with a test that calls {@link
 * com.estoma.authentication.infrastructure.messaging.outbound.OutboxRelay#relay}
 * directly.
 */
@ConfigurationProperties(prefix = "estoma.messaging")
public record MessagingProperties(Duration publisherConfirmTimeout, int outboxClaimBatchSize, int outboxMaxAttempts, boolean outboxRelayEnabled) {

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
  }
}
