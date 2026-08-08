package com.estoma.authentication.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.domain.credential.PasswordHash;
import org.junit.jupiter.api.Test;

class Argon2PasswordHasherTest {

  private final Argon2PasswordHasher hasher = new Argon2PasswordHasher();

  @Test
  void hashNeverStoresThePlainTextPassword() {
    PasswordHash hash = hasher.hash("correct horse battery staple");

    assertThat(hash.value()).doesNotContain("correct horse battery staple");
    assertThat(hash.algorithm()).isEqualTo("ARGON2ID");
  }

  @Test
  void matchesVerifiesTheCorrectPassword() {
    PasswordHash hash = hasher.hash("hunter2");

    assertThat(hasher.matches("hunter2", hash)).isTrue();
    assertThat(hasher.matches("wrong-password", hash)).isFalse();
  }

  @Test
  void sameSourcePasswordProducesDifferentHashesEachTime() {
    PasswordHash first = hasher.hash("same-password");
    PasswordHash second = hasher.hash("same-password");

    assertThat(first.value()).isNotEqualTo(second.value());
  }
}
