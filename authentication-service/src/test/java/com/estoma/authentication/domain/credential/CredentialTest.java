package com.estoma.authentication.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialTest {

  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void configureSetsHashAndChangedAt() {
    PasswordHash hash = new PasswordHash("$argon2id$...", "ARGON2ID", "{}");

    Credential credential = Credential.configure(UUID.randomUUID(), hash, now);

    assertThat(credential.hash()).isEqualTo(hash);
    assertThat(credential.passwordChangedAt()).isEqualTo(now);
  }

  @Test
  void changedReplacesHashAndChangedAt() {
    PasswordHash original = new PasswordHash("$argon2id$original", "ARGON2ID", "{}");
    PasswordHash rotated = new PasswordHash("$argon2id$rotated", "ARGON2ID", "{}");
    Credential credential = Credential.configure(UUID.randomUUID(), original, now);

    Credential changed = credential.changed(rotated, now.plusSeconds(60));

    assertThat(changed.hash()).isEqualTo(rotated);
    assertThat(changed.passwordChangedAt()).isEqualTo(now.plusSeconds(60));
    assertThat(changed.mustChangePassword()).isFalse();
    assertThat(changed.credentialVersion()).isEqualTo(2);
  }

  @Test
  void temporaryPasswordRequiresChangeUntilItExpires() {
    Credential credential = Credential.configure(UUID.randomUUID(), new PasswordHash("old", "ARGON2ID", "{}"), now)
        .temporaryPassword(new PasswordHash("temporary", "ARGON2ID", "{}"), now.plusSeconds(60), now);

    assertThat(credential.mustChangePassword()).isTrue();
    assertThat(credential.isValidTemporaryPassword(now.plusSeconds(1))).isTrue();
    assertThat(credential.isValidTemporaryPassword(now.plusSeconds(60))).isFalse();
  }

  @Test
  void rejectsBlankHashValue() {
    assertThatThrownBy(() -> new PasswordHash("", "ARGON2ID", "{}")).isInstanceOf(IllegalArgumentException.class);
  }
}
