package com.estoma.platformbff.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(
    String componentId,
    SecurityRpc securityRpc,
    Jwt jwt,
    Retention retention) {

  public record SecurityRpc(Duration requestTtl, Duration timeout, int retryAttempts) {
  }

  public record Jwt(String issuer, String audience, String jwkSet) {
  }

  public record Retention(int operationDays, int idempotencyHours) {
  }
}
