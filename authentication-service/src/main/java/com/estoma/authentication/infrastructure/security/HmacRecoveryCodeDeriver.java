package com.estoma.authentication.infrastructure.security;

import com.estoma.authentication.application.port.RecoveryCodeDeriver;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HMAC-derived six digit recovery codes; no code or recoverable token is stored. */
@Component
class HmacRecoveryCodeDeriver implements RecoveryCodeDeriver {

  private static final String ALGORITHM = "HmacSHA256";
  private final byte[] secret;

  HmacRecoveryCodeDeriver(
      @Value("${estoma.recovery.hmac-secret:}") String configuredSecret,
      @Value("${estoma.environment:local}") String environment) {
    if (configuredSecret.isBlank() && !"local".equalsIgnoreCase(environment) && !"test".equalsIgnoreCase(environment)) {
      throw new IllegalStateException("estoma.recovery.hmac-secret is required outside local and test environments");
    }
    this.secret = (configuredSecret.isBlank() ? "local-test-only-recovery-secret" : configuredSecret).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public String derive(UUID recoveryId) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      byte[] digest = mac.doFinal(("authentication-recovery-v1:" + recoveryId).getBytes(StandardCharsets.UTF_8));
      int value = Math.floorMod(ByteBuffer.wrap(digest, 0, Integer.BYTES).getInt(), 1_000_000);
      return "%06d".formatted(value);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC recovery code derivation is unavailable", e);
    }
  }

  @Override
  public boolean matches(UUID recoveryId, String candidate) {
    if (candidate == null) {
      return false;
    }
    return MessageDigest.isEqual(derive(recoveryId).getBytes(StandardCharsets.US_ASCII), candidate.getBytes(StandardCharsets.US_ASCII));
  }
}
