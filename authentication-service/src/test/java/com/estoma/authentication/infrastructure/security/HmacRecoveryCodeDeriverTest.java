package com.estoma.authentication.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authentication.domain.recovery.RecoveryCodeDerivationContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HmacRecoveryCodeDeriverTest {

  private final HmacRecoveryCodeDeriver deriver = new HmacRecoveryCodeDeriver("test-secret", "test");

  @Test
  void derivesAndMatchesOnlyTheCanonicalHmacContext() {
    UUID recoveryId = UUID.randomUUID();

    String code = deriver.derive(recoveryId, RecoveryCodeDerivationContext.HMAC_SHA256_V1);
    String wrongCode = wrongCodeFor(code);

    assertThat(code).matches("\\d{6}");
    assertThat(deriver.matches(recoveryId, RecoveryCodeDerivationContext.HMAC_SHA256_V1, code)).isTrue();
    assertThat(deriver.matches(recoveryId, RecoveryCodeDerivationContext.HMAC_SHA256_V1, wrongCode)).isFalse();
  }

  @Test
  void rejectsLegacyAndUnknownContextsWithoutFallback() {
    UUID recoveryId = UUID.randomUUID();

    assertThatThrownBy(() -> deriver.derive(recoveryId, "legacy-sha256"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> deriver.matches(recoveryId, "unknown", "123456"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private String wrongCodeFor(String validCode) {
    char replacement = validCode.charAt(0) == '0' ? '1' : '0';
    return replacement + validCode.substring(1);
  }
}
