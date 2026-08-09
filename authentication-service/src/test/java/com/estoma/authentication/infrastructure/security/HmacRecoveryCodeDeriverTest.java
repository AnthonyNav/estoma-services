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

    assertThat(code).matches("\\d{6}");
    assertThat(deriver.matches(recoveryId, RecoveryCodeDerivationContext.HMAC_SHA256_V1, code)).isTrue();
    assertThat(deriver.matches(recoveryId, RecoveryCodeDerivationContext.HMAC_SHA256_V1, "000000")).isFalse();
  }

  @Test
  void rejectsLegacyAndUnknownContextsWithoutFallback() {
    UUID recoveryId = UUID.randomUUID();

    assertThatThrownBy(() -> deriver.derive(recoveryId, "legacy-sha256"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> deriver.matches(recoveryId, "unknown", "123456"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
