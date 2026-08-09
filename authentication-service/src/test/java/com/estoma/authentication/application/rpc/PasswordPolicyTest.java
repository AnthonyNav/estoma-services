package com.estoma.authentication.application.rpc;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.credential.PasswordHash;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  private final PasswordHasher hasher = new PasswordHasher() {
    @Override
    public PasswordHash hash(String password) {
      return new PasswordHash(password, "test", "{}");
    }

    @Override
    public boolean matches(String password, PasswordHash hash) {
      return password.equals(hash.value());
    }
  };
  private final PasswordPolicy policy = new PasswordPolicy(hasher, password -> "passwordpassword".equals(password));

  @Test
  void acceptsTheSupportedMaximumLength() {
    assertThatCode(() -> policy.validate("a".repeat(128), null)).doesNotThrowAnyException();
  }

  @Test
  void rejectsTooShortTooLongCommonAndCurrentPasswords() {
    assertInvalid("short", null);
    assertInvalid("a".repeat(129), null);
    assertInvalid("passwordpassword", null);
    assertInvalid("current-password-1", hasher.hash("current-password-1"));
  }

  private void assertInvalid(String password, PasswordHash currentHash) {
    assertThatThrownBy(() -> policy.validate(password, currentHash))
        .isInstanceOf(AuthenticationRejectedException.class);
  }
}
