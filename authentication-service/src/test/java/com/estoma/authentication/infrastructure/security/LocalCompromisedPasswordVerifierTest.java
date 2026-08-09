package com.estoma.authentication.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalCompromisedPasswordVerifierTest {

  private final LocalCompromisedPasswordVerifier verifier = new LocalCompromisedPasswordVerifier();

  @Test
  void rejectsCommonPasswordsWithoutNetworkLookup() {
    assertThat(verifier.isCompromised("PASSWORDPASSWORD")).isTrue();
    assertThat(verifier.isCompromised("a-long-unique-password")).isFalse();
  }
}
