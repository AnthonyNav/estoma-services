package com.estoma.authentication.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionTest {

  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
  private final Instant expiresAt = now.plusSeconds(900);

  @Test
  void createNewIsValidUntilExpiry() {
    Session session = newSession();

    assertThat(session.isValid(now.plusSeconds(1))).isTrue();
    assertThat(session.isValid(expiresAt.plusSeconds(1))).isFalse();
  }

  @Test
  void rotatedReplacesTokenHashAndExpiry() {
    Session session = newSession();
    Instant newExpiry = expiresAt.plusSeconds(900);

    Session rotated = session.rotated("new-hash", now.plusSeconds(10), newExpiry);

    assertThat(rotated.refreshTokenHash()).isEqualTo("new-hash");
    assertThat(rotated.expiresAt()).isEqualTo(newExpiry);
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
  void rejectsExpiresAtNotAfterCreatedAt() {
    assertThatThrownBy(() -> Session.createNew(UUID.randomUUID(), UUID.randomUUID(), "hash", null, null, null, now, now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Session newSession() {
    return Session.createNew(UUID.randomUUID(), UUID.randomUUID(), "hash", "Chrome", "127.0.0.1", "UA", now, expiresAt);
  }
}
