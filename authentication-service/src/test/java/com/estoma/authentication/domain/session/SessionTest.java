package com.estoma.authentication.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionTest {

  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
  private final Instant idleExpiresAt = now.plusSeconds(900);

  @Test
  void createNewIsValidUntilExpiry() {
    Session session = newSession();

    assertThat(session.isValid(now.plusSeconds(1))).isTrue();
    assertThat(session.isValid(idleExpiresAt.plusSeconds(1))).isFalse();
  }

  @Test
  void rotatedReplacesTokenHashAndExpiry() {
    Session session = newSession();
    Instant newExpiry = idleExpiresAt.plusSeconds(900);

    Session rotated = session.rotated("new-hash", now.plusSeconds(10), newExpiry, now.plusSeconds(40));

    assertThat(rotated.refreshTokenHash()).isEqualTo("new-hash");
    assertThat(rotated.idleExpiresAt()).isEqualTo(newExpiry);
    assertThat(rotated.previousRefreshTokenHash()).isEqualTo("hash");
    assertThat(rotated.matchesRefreshTokenHash("hash", now.plusSeconds(20))).isTrue();
    assertThat(rotated.lastUsedAt()).isEqualTo(now.plusSeconds(10));
  }

  @Test
  void revokedSessionIsNoLongerValid() {
    Session session = newSession();

    Session revoked = session.revoked("password_changed", now.plusSeconds(5));

    assertThat(revoked.isValid(now.plusSeconds(6))).isFalse();
    assertThat(revoked.revocationReason()).isEqualTo("password_changed");
  }

  @Test
  void rejectsIdleExpiryNotAfterCreatedAt() {
    assertThatThrownBy(() -> Session.createNormal(UUID.randomUUID(), UUID.randomUUID(), "hash", null, null, null, now, now, now.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Session newSession() {
    return Session.createNormal(UUID.randomUUID(), UUID.randomUUID(), "hash", "Chrome", "127.0.0.1", "UA", now, idleExpiresAt, now.plusSeconds(1800));
  }
}
