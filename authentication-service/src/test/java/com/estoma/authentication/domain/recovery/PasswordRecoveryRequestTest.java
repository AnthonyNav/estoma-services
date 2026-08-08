package com.estoma.authentication.domain.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasswordRecoveryRequestTest {

  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
  private final Instant expiresAt = now.plusSeconds(1800);

  @Test
  void createNewIsUnusedAndNotExpiredBeforeDeadline() {
    PasswordRecoveryRequest request = newRequest();

    assertThat(request.isUsed()).isFalse();
    assertThat(request.isExpired(now.plusSeconds(1))).isFalse();
    assertThat(request.isExpired(expiresAt.plusSeconds(1))).isTrue();
  }

  @Test
  void attemptFailedIncrementsCount() {
    PasswordRecoveryRequest request = newRequest().attemptFailed().attemptFailed();

    assertThat(request.failedAttempts()).isEqualTo(2);
  }

  @Test
  void usedSetsUsedAt() {
    PasswordRecoveryRequest used = newRequest().used(now.plusSeconds(10));

    assertThat(used.isUsed()).isTrue();
    assertThat(used.usedAt()).isEqualTo(now.plusSeconds(10));
  }

  private PasswordRecoveryRequest newRequest() {
    return PasswordRecoveryRequest.createNew(UUID.randomUUID(), UUID.randomUUID(), "token-hash", now, expiresAt);
  }
}
