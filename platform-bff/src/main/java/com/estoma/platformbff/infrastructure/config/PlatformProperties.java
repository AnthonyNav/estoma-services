package com.estoma.platformbff.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(
    String componentId,
    SecurityRpc securityRpc,
    Jwt jwt,
    Retention retention,
    Genesis genesis) {

  @ConstructorBinding
  public PlatformProperties {
    genesis = genesis == null ? new Genesis(false, false) : genesis;
  }

  /** Keeps direct test/bootstrap construction compatible with the pre-genesis configuration. */
  public PlatformProperties(String componentId, SecurityRpc securityRpc, Jwt jwt, Retention retention) {
    this(componentId, securityRpc, jwt, retention, new Genesis(false, false));
  }

  public record SecurityRpc(Duration requestTtl, Duration timeout, int retryAttempts) {
  }

  public record Jwt(String issuer, String audience, String jwkSet) {
  }

  public record Retention(
      int operationDays,
      int idempotencyHours,
      int outboxPublishedDays,
      int outboxFailedDays,
      int inboxDays,
      int recoveryJobDays) {
  }

  /** One-shot bootstrap is deliberately opt-in and must be removed by deployment after use. */
  public record Genesis(boolean enabled, boolean environmentFresh) {
  }
}
